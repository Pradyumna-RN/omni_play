package labs.rtensor.music

import android.content.Context
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import labs.rtensor.music.ui.CoverAccent
import labs.rtensor.music.ui.CoverColours
import labs.rtensor.music.ui.Ink
import labs.rtensor.music.ui.MinMarkContrast
import labs.rtensor.music.ui.MinTextContrast
import labs.rtensor.music.ui.Paper
import labs.rtensor.music.ui.PaperMuted
import labs.rtensor.music.ui.accentOnTone
import labs.rtensor.music.ui.contrastRatio
import labs.rtensor.music.ui.onAccentFor
import labs.rtensor.music.ui.playerTones
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** "From the cover" against covers shaped like real artwork ([CoverFixtures]), loaded through the app's own Coil image loader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverAccentArtworkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L

    @Before fun setUp() { CoverAccent.reset(); CoverAccent.clock = { now } }
    @After fun tearDown() { CoverAccent.reset(); CoverAccent.clock = { android.os.SystemClock.elapsedRealtime() } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> await(block: suspend () -> T): T {
        val job = CoroutineScope(Dispatchers.Main).async { block() }
        val end = System.currentTimeMillis() + 20_000
        while (!job.isCompleted && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("load finished", job.isCompleted)
        return job.getCompleted()
    }
    private fun load(url: String): CoverColours? = await { CoverAccent.load(context, url) }
    private fun fileUrl(name: String) = File(context.cacheDir, "covers/$name").apply { parentFile!!.mkdirs(); writeBytes(CoverFixtures.bytes(name)) }.toURI().toString()
    private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    private fun hueDistance(a: Float, b: Float) = abs(a - b).let { min(it, 360 - it) }

    @Test fun everyCoverGivesAReadableAccentAndPlayerTone() {
        var hits = 0
        val missing = mutableListOf<String>()
        for (name in CoverFixtures.names) {
            val colours = load(fileUrl(name))
            val found = colours?.accent?.isSpecified == true
            if (found) hits++
            println("cover-accent $name -> ${colours?.let { "accent=%06X deep=%06X".format(it.accent.toArgb() and 0xFFFFFF, it.deep.toArgb() and 0xFFFFFF) } ?: "none"}")
            if (!found) { missing += name; continue }
            val accent = colours!!.accent
            assertTrue("$name accent on paper", contrastRatio(accent, Paper) >= MinMarkContrast)
            assertTrue("$name accent on ink", contrastRatio(accent, Ink) >= MinMarkContrast)
            assertTrue("$name text on accent", contrastRatio(accent, onAccentFor(accent)) >= MinTextContrast)
            val tones = playerTones(colours.deep)
            for (ground in listOf(tones.top, tones.bottom)) {
                assertTrue("$name paper on player", contrastRatio(Paper, ground) >= 7.0)
                assertTrue("$name muted paper on player", contrastRatio(PaperMuted, ground) >= MinTextContrast)
            }
            val lifted = accentOnTone(accent, tones.top)
            assertTrue("$name accent on player", contrastRatio(lifted, tones.top) >= MinMarkContrast)
            assertTrue("$name text on player accent", contrastRatio(lifted, onAccentFor(lifted)) >= MinTextContrast)
        }
        println("cover-accent hit rate $hits/${CoverFixtures.names.size}, none for $missing")
        assertEquals("covers without colours: $missing", emptyList<String>(), missing)
    }

    @Test fun colourfulCoversKeepTheirColour() {
        // Hues from what each cover is mostly made of: the red field, the amber field and moon, the teal
        // field (not its small salmon logos) and the blue sky.
        val expected = mapOf("song-red-numeral.jpg" to 4f, "song-amber-portrait.jpg" to 33f, "song-orange-moon.jpg" to 31f,
            "atv-teal-logo.jpg" to 180f, "song-teal-logo.jpg" to 180f, "spotify-blue-photo.jpg" to 205f)
        for ((name, hue) in expected) {
            val accent = CoverAccent.extract(CoverFixtures.small(name))!!
            assertTrue("$name hue ${hsl(accent)[0]} near $hue", hueDistance(hsl(accent)[0], hue) <= 16f)
            assertTrue("$name keeps its colour", hsl(accent)[1] >= .2f)
        }
    }

    @Test fun youtubeBarsAreCroppedBeforeTheColoursAreTaken() {
        // A YouTube Music "art track" thumbnail is the square cover between flat side panels.
        for (name in listOf("atv-teal-logo.jpg", "atv-black-cream.jpg", "atv-amber-portrait.jpg")) {
            val bitmap = CoverFixtures.small(name)
            val region = CoverAccent.artRegion(bitmap)
            assertEquals("$name keeps its full height", bitmap.height, region.height())
            assertTrue("$name loses the side panels: $region of ${bitmap.width}", region.width() <= bitmap.width * .7f)
            assertTrue("$name keeps the cover: $region", region.width() >= bitmap.height * .7f)
        }
        // hqdefault adds black letterbox bars above and below as well.
        val boxed = CoverFixtures.small("hq-atv-gold-helmet.jpg")
        val region = CoverAccent.artRegion(boxed)
        assertTrue("letterbox bars cropped: $region", region.top > 0 && region.bottom < boxed.height)
        assertTrue("pillarbox panels cropped: $region", region.left > 0 && region.right < boxed.width)
        // A square cover without bars keeps (nearly) all of itself.
        val square = CoverFixtures.small("song-teal-logo.jpg")
        assertTrue(CoverAccent.artRegion(square).let { it.width() * it.height() } >= square.width * square.height * .85f)
        // The same song's art-track thumbnail and its square cover agree on the colour family.
        for (song in listOf("teal-logo", "amber-portrait", "gold-helmet")) {
            val a = hsl(CoverAccent.extract(CoverFixtures.small("atv-$song.jpg"))!!)[0]
            val b = hsl(CoverAccent.extract(CoverFixtures.small("song-$song.jpg"))!!)[0]
            assertTrue("$song thumbnail hue $a vs cover hue $b", hueDistance(a, b) <= 30f)
        }
    }

    @Test fun aFailedLoadIsRetriedInsteadOfRememberedForTheSession() {
        CoverServer().use { server ->
            val url = server.url("song-amber-portrait.jpg")
            server.online = false
            assertNull("an HTTP error gives no colours yet", load(url))
            assertNull("and nothing is stored for the artwork", CoverAccent.cached(url))
            val tried = server.requests.get()
            assertTrue(tried >= 1)
            // Straight away again: the short-lived miss answers without another request.
            assertNull(load(url))
            assertEquals(tried, server.requests.get())
            // The connection comes back; once the miss has expired the cover loads.
            server.online = true
            now += CoverAccent.MissMs
            val colours = load(url)
            assertTrue("colours once the artwork loads", colours?.accent?.isSpecified == true)
            assertEquals(colours, CoverAccent.cached(url))
            // Worked out once: later calls do not fetch again.
            val after = server.requests.get()
            assertEquals(colours, load(url))
            assertEquals(after, server.requests.get())
        }
    }

    @Test fun skippingAwayMidLoadStoresNothing() {
        CoverServer().use { server ->
            val url = server.url("song-orange-moon.jpg")
            server.delayMs = 1_500
            val job = CoroutineScope(Dispatchers.Main).async { CoverAccent.load(context, url) }
            val end = System.currentTimeMillis() + 5_000
            while (server.requests.get() == 0 && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertEquals("the cover was being fetched", 1, server.requests.get())
            // The song changes: the effect that asked for this cover is cancelled.
            job.cancel()
            while (!job.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertTrue(job.isCancelled)
            assertNull("no colours stored", CoverAccent.cached(url))
            // Neither "no colour" nor a miss: coming back to the song loads at once, without waiting out a miss.
            server.delayMs = 0
            assertTrue(load(url)?.accent?.isSpecified == true)
        }
    }

    @Test fun aMissingFileIsAMissNotAPermanentNoColour() {
        val file = File(context.cacheDir, "covers/later.jpg").apply { parentFile!!.mkdirs(); delete() }
        val url = file.toURI().toString()
        assertNull(load(url))
        file.writeBytes(CoverFixtures.bytes("spotify-blue-photo.jpg"))
        now += CoverAccent.MissMs
        assertTrue(load(url)?.accent?.isSpecified == true)
    }

    @Test fun greyscaleCoversGetANeutralAccentRatherThanNone() {
        for (name in listOf("song-bw-portrait.jpg", "video-bw-photo.jpg")) {
            val colours = CoverAccent.colours(CoverFixtures.small(name))!!
            assertTrue("$name accent is neutral", hsl(colours.accent)[1] < .2f)
            assertTrue(CoverAccent.fitsBothThemes(colours.accent))
            assertTrue(contrastRatio(PaperMuted, colours.deep) >= MinTextContrast)
        }
    }
}
