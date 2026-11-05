package labs.rtensor.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * "From the cover": the most vivid colour in the current song's artwork. Only the accent
 * changes; backgrounds stay paper and ink. Extraction runs off the main thread and each
 * artwork is worked out once per process.
 */
internal object CoverAccent {
    /** Below this HSL saturation a colour reads as grey and Signal is used instead. */
    private const val MinSaturation = .25f
    /** Stands for "this cover has no usable colour" in the cache. */
    private val NONE = CoverColours(Color.Unspecified, Color.Unspecified)
    /** Artwork URL → extracted colours, or [NONE]. */
    private val cache = LruCache<String, CoverColours>(64)

    /** True when [colour] reads as a mark on paper and on ink, and carries readable text. */
    fun fitsBothThemes(colour: Color): Boolean =
        contrastRatio(colour, Paper) >= MinMarkContrast && contrastRatio(colour, Ink) >= MinMarkContrast &&
            contrastRatio(colour, onAccentFor(colour)) >= MinTextContrast

    /** Moves lightness only, keeping the cover's hue and saturation, until [fitsBothThemes]. */
    fun adjust(colour: Color): Color? = closestLightness(colour, ::fitsBothThemes)

    /** A vivid swatch from [bitmap], adjusted for contrast; null for blank or greyscale art. */
    fun extract(bitmap: Bitmap): Color? = colours(bitmap)?.accent

    /** The accent and the deep player tone from [bitmap]; null for blank or greyscale art. */
    fun colours(bitmap: Bitmap): CoverColours? = try {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch?.takeIf { it.hsl[1] >= MinSaturation && it.hsl[2] in .1f..0.9f }
        val accent = swatch?.let { adjust(Color(it.rgb)) }
        val deepSource = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: swatch
        val deep = deepSource?.let { deepTone(Color(it.rgb)) }
        if (accent != null && deep != null) CoverColours(accent, deep) else null
    } catch (_: Exception) { null }

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

    suspend fun load(context: Context, url: String): CoverColours {
        cache.get(url)?.let { return it }
        val found = try {
            // A small software bitmap is enough for Palette and shares Coil's disk cache.
            val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            bitmap?.let {
                withContext(Dispatchers.Default) {
                    val readable = if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
                    colours(readable)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return (found ?: NONE).also { cache.put(url, it) }
    }
}

/** The colours for [artwork], or null (use Signal and paper/ink) while loading, without artwork or on failure. */
@Composable
internal fun rememberCoverColours(artwork: String?): CoverColours? {
    val context = LocalContext.current
    val url = artwork?.takeIf { it.isNotBlank() }?.let { ArtworkUrl.sized(it, 120) }
    // The previous song's colour stays until the next one is known, so skipping animates from
    // colour to colour instead of flashing back to Signal in between.
    var colour by remember { mutableStateOf(url?.let(CoverAccent::cached)) }
    LaunchedEffect(url) { colour = if (url == null) null else CoverAccent.load(context.applicationContext, url) }
    return colour?.takeIf { it.accent.isSpecified }
}
