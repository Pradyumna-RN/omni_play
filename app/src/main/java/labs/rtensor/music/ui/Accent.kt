package labs.rtensor.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.ArtworkUrl
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The original Signal orange: the default accent and the fallback for every unknown case. */
internal val SignalAccent = Color(0xFFFF5A1F)
internal val Cobalt = Color(0xFF2F4BFF)
internal val Moss = Color(0xFF3E7A4C)
internal val Oxblood = Color(0xFF8C2A2A)
internal val White = Color(0xFFFFFFFF)

/** Text on the accent must meet WCAG AA for normal text. */
internal const val MinTextContrast = 4.5
/** Lines, marks and fills must meet WCAG 1.4.11 against what they sit on. */
internal const val MinMarkContrast = 3.0

/**
 * The accent in use. [accent] is the fill on the page; [onAccent] is the colour for text and
 * icons placed on that fill; [onDock] is the accent as drawn on the always-black dock and
 * panels, made light enough to be seen there (Ink becomes paper, a deep red is lifted).
 */
internal data class AccentColours(val accent: Color, val onAccent: Color, val onDock: Color)

internal val LocalAccent = compositionLocalOf { accentColours(AccentChoice.SIGNAL, dark = false, cover = null) }

/** What "From the cover" takes from one artwork: the accent, and a deep tone for the player. */
internal data class CoverColours(val accent: Color, val deep: Color)

/**
 * The player's background with "From the cover": a vertical gradient from [top], the cover's
 * deep tone, to [bottom], a darker shade of it. Paper and muted paper text read on both.
 */
internal data class PlayerTones(val top: Color, val bottom: Color)

/** Set while the player shows a cover gradient; null means today's paper/ink. */
internal val LocalPlayerTones = staticCompositionLocalOf<PlayerTones?> { null }

internal fun playerTones(deep: Color): PlayerTones {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(deep.toArgb(), it) }
    return PlayerTones(deep, Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], hsl[2] * .5f))))
}

/** The accent as used on a player tone: lightened, if needed, to stand out from it and to carry text. */
internal fun accentOnTone(accent: Color, tone: Color): Color =
    closestLightness(accent) { contrastRatio(it, tone) >= MinMarkContrast && contrastRatio(it, onAccentFor(it)) >= MinTextContrast } ?: Paper

/** WCAG 2 contrast ratio between two opaque colours, 1..21. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + .05) / (min(la, lb) + .05)
}

/** Ink or white, whichever reads better on [accent]. */
internal fun onAccentFor(accent: Color): Color = if (contrastRatio(accent, Ink) >= contrastRatio(accent, White)) Ink else White

/** The colour a fixed choice paints. Ink has no hue: it is ink on paper and paper on ink. */
internal fun fixedAccent(choice: AccentChoice, dark: Boolean): Color? = when (choice) {
    AccentChoice.SIGNAL -> SignalAccent
    AccentChoice.COBALT -> Cobalt
    AccentChoice.MOSS -> Moss
    AccentChoice.OXBLOOD -> Oxblood
    AccentChoice.INK -> if (dark) Paper else Ink
    AccentChoice.COVER -> null
}

internal fun accentColours(choice: AccentChoice, dark: Boolean, cover: Color?): AccentColours {
    val accent = fixedAccent(choice, dark) ?: cover?.takeIf { it.isSpecified } ?: SignalAccent
    return AccentColours(accent, onAccentFor(accent), visibleOnDock(accent))
}

/** The accent as a line or mark on the black dock: unchanged when it already shows there. */
internal fun visibleOnDock(accent: Color): Color {
    if (contrastRatio(accent, Ink) >= MinMarkContrast) return accent
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(accent.toArgb(), it) }
    // A colourless accent (Ink) would only turn grey; paper is the Signal light on the dock.
    if (hsl[1] < .12f) return Paper
    return closestLightness(accent) { contrastRatio(it, Ink) >= MinMarkContrast } ?: Paper
}

/**
 * The colour with the same hue and saturation whose HSL lightness is nearest to [colour]'s and
 * satisfies [accept], or null if none does.
 */
internal fun closestLightness(colour: Color, accept: (Color) -> Boolean): Color? {
    if (accept(colour)) return colour
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour.toArgb(), it) }
    var best: Color? = null
    var distance = Float.MAX_VALUE
    for (step in 0..1000) {
        val l = step / 1000f
        val d = abs(l - hsl[2])
        if (d >= distance) continue
        val candidate = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], l)))
        if (accept(candidate)) { best = candidate; distance = d }
    }
    return best
}

/**
 * "From the cover": the most vivid colour in the current song's artwork, or failing that its
 * strongest muted or neutral tone, so every real cover gives the player a colour. Extraction
 * runs off the main thread; each artwork that decodes is worked out once per process, while a
 * load that fails (offline, an HTTP error) is only remembered for [MissMs] and then tried again.
 */
internal object CoverAccent {
    /**
     * Below this chroma (the spread between a colour's strongest and weakest channel, 0..1) a
     * swatch reads as grey, or as noise in near-black. A cover with nothing above it gets a
     * neutral accent and a charcoal player tone.
     */
    private const val MinChroma = .08f
    /** A failed load is not retried before this, so a broken URL is not fetched on every recomposition. */
    const val MissMs = 5_000L
    /** Stands for "this artwork decoded but has no pixels to take a colour from" (fully transparent). */
    private val NONE = CoverColours(Color.Unspecified, Color.Unspecified)
    /** Artwork URL → extracted colours, or [NONE]. Only artwork that actually decoded is stored here. */
    private val cache = LruCache<String, CoverColours>(64)
    /** Artwork URL → when its last load failed. Short-lived: see [MissMs]. */
    private val misses = HashMap<String, Long>()
    /** Milliseconds since boot; replaceable by tests. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** True when [colour] reads as a mark on paper and on ink, and carries readable text. */
    fun fitsBothThemes(colour: Color): Boolean =
        contrastRatio(colour, Paper) >= MinMarkContrast && contrastRatio(colour, Ink) >= MinMarkContrast &&
            contrastRatio(colour, onAccentFor(colour)) >= MinTextContrast

    /**
     * Moves lightness only, keeping the cover's hue and saturation, until [fitsBothThemes]. If no
     * lightness works at that saturation, the colour is calmed step by step rather than dropped.
     */
    fun adjust(colour: Color): Color? {
        closestLightness(colour, ::fitsBothThemes)?.let { return it }
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour.toArgb(), it) }
        for (step in 1..10) {
            val calmer = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1] * (1 - step / 10f), hsl[2])))
            closestLightness(calmer, ::fitsBothThemes)?.let { return it }
        }
        return null
    }

    /** The accent from [bitmap], adjusted for contrast; null only for art with no visible pixels. */
    fun extract(bitmap: Bitmap): Color? = colours(bitmap)?.accent

    /**
     * The accent and the deep player tone from [bitmap]; null only for art with no visible pixels.
     * Letterbox and pillarbox bars (YouTube thumbnails of square covers) are left out first.
     */
    fun colours(bitmap: Bitmap): CoverColours? = try {
        val art = artRegion(bitmap)
        val palette = Palette.from(bitmap).setRegion(art.left, art.top, art.right, art.bottom).maximumColorCount(16).generate()
        // The colour that fills the most of the cover, weighted by how colourful it is: a small
        // vivid logo does not outweigh a cover that is mostly one deep colour, and grey or
        // near-black JPEG noise never wins over a real colour.
        val colourful = palette.swatches.filter { chroma(it.rgb) >= MinChroma }.maxByOrNull { it.population * chroma(it.rgb) * chroma(it.rgb) }
        val source = colourful?.rgb ?: palette.dominantSwatch?.rgb ?: averageColour(bitmap, art)
        if (source == null) null else {
            val accent = adjust(Color(source)) ?: Color(source)
            val deepSource = listOfNotNull(palette.darkVibrantSwatch, palette.darkMutedSwatch).firstOrNull { chroma(it.rgb) >= MinChroma }?.rgb ?: source
            CoverColours(accent, deepTone(Color(deepSource)))
        }
    } catch (_: Exception) { null }

    /**
     * The part of [bitmap] that is the artwork: uniform bars on opposite edges (black letterbox
     * bars, or the flat side panels YouTube puts beside a square cover) are cropped away. Each
     * side gives up at most 30%, so a cover with plain edges keeps most of itself.
     */
    internal fun artRegion(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        fun uniform(count: Int, at: (Int) -> Int): Boolean {
            var r = 0L; var g = 0L; var b = 0L
            for (i in 0 until count) { val c = at(i); r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF }
            val mr = r / count; val mg = g / count; val mb = b / count
            var close = 0
            for (i in 0 until count) {
                val c = at(i)
                if (abs((c shr 16 and 0xFF) - mr) <= 20 && abs((c shr 8 and 0xFF) - mg) <= 20 && abs((c and 0xFF) - mb) <= 20) close++
            }
            return close >= count * .95
        }
        var top = 0; var bottom = h; var left = 0; var right = w
        val maxRows = (h * .3f).toInt()
        var t = 0; while (t < maxRows && uniform(w) { pixels[t * w + it] }) t++
        var b = 0; while (b < maxRows && uniform(w) { pixels[(h - 1 - b) * w + it] }) b++
        if (t > 0 && b > 0) { top = t; bottom = h - b }
        val rows = bottom - top
        val maxCols = (w * .3f).toInt()
        var l = 0; while (l < maxCols && uniform(rows) { pixels[(top + it) * w + l] }) l++
        var r = 0; while (r < maxCols && uniform(rows) { pixels[(top + it) * w + (w - 1 - r)] }) r++
        if (l > 0 && r > 0) { left = l; right = w - r }
        return Rect(left, top, right, bottom)
    }

    private fun chroma(rgb: Int): Float {
        val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
        return (max(r, max(g, b)) - min(r, min(g, b))) / 255f
    }

    /** The mean of the opaque pixels in [region], for art Palette filters out entirely (pure black or white). */
    private fun averageColour(bitmap: Bitmap, region: Rect): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            val c = bitmap[x, y]
            if (c ushr 24 < 128) continue
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
        }
        return if (n == 0L) null else (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /**
     * A dark, calmer version of [colour] for the player background: saturation capped so it
     * stays a tone rather than a neon, lightness lowered until muted paper text passes AA.
     */
    fun deepTone(colour: Color): Color {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour.toArgb(), it) }
        val calm = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], min(hsl[1], .55f), min(hsl[2], .22f))))
        return closestLightness(calm) { contrastRatio(PaperMuted, it) >= MinTextContrast && contrastRatio(Paper, it) >= 7.0 } ?: Ink
    }

    /** null: not worked out yet. Otherwise the cover's colours, or ones with unspecified colours. */
    fun cached(url: String): CoverColours? = cache.get(url)

    /** For tests and previews: work out [bitmap]'s colours now and remember them for [url]. */
    fun prime(url: String, bitmap: Bitmap) { cache.put(url, colours(bitmap) ?: NONE) }

    /** For tests: forget everything worked out or missed so far. */
    fun reset() { cache.evictAll(); synchronized(misses) { misses.clear() } }

    /**
     * The colours for [url]. null means the artwork could not be loaded right now (offline, an
     * HTTP error, a missing file); nothing is stored for long, so a later call tries again.
     */
    suspend fun load(context: Context, url: String): CoverColours? {
        cache.get(url)?.let { return it }
        synchronized(misses) { misses[url] }?.let { failed -> if (clock() - failed < MissMs) return null }
        val bitmap = try { fetch(context, url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        // A load cut short because the song changed is not a failure of this artwork.
        currentCoroutineContext().ensureActive()
        if (bitmap == null) {
            synchronized(misses) { misses[url] = clock() }
            return null
        }
        val found = withContext(Dispatchers.Default) { colours(bitmap) } ?: NONE
        cache.put(url, found)
        synchronized(misses) { misses.remove(url) }
        return found
    }

    /** A small software bitmap of [url]; it shares Coil's disk cache with the artwork on screen. */
    private suspend fun fetch(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
        val result = context.imageLoader.execute(request)
        if (result is ErrorResult) {
            (result.throwable as? CancellationException)?.let { throw it }
            return null
        }
        val drawable = (result as? SuccessResult)?.drawable ?: return null
        (drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
            return if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        }
        // Any other drawable (a crossfade, a vector) is drawn into a software bitmap.
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        return createBitmap(w, h).also { drawable.setBounds(0, 0, w, h); drawable.draw(Canvas(it)) }
    }
}

/** Waits between attempts at artwork that failed to load: quick at first, then once a minute. */
private val CoverRetryMs = longArrayOf(CoverAccent.MissMs, 10_000, 20_000, 40_000, 60_000)

/**
 * The colours for [artwork], or null (use Signal and paper/ink) while loading or without
 * artwork. Artwork that fails to load is tried again while the song is playing, so a song
 * started offline or on a bad connection still takes its colours once the cover arrives.
 */
@Composable
internal fun rememberCoverColours(artwork: String?): CoverColours? {
    val context = LocalContext.current
    val url = artwork?.takeIf { it.isNotBlank() }?.let { ArtworkUrl.sized(it, 120) }
    // The previous song's colour stays until the next one is known, so skipping animates from
    // colour to colour instead of flashing back to Signal in between.
    var colour by remember { mutableStateOf(url?.let(CoverAccent::cached)) }
    LaunchedEffect(url) {
        if (url == null) { colour = null; return@LaunchedEffect }
        var attempt = 0
        while (true) {
            val found = CoverAccent.load(context.applicationContext, url)
            if (found != null) { colour = found; break }
            colour = null
            delay(CoverRetryMs[min(attempt++, CoverRetryMs.lastIndex)])
        }
    }
    return colour?.takeIf { it.accent.isSpecified }
}
