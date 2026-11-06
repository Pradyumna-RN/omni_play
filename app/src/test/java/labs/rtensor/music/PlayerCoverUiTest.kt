package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.CoverAccent
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.Paper
import labs.rtensor.music.ui.playerTones
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The player with "From the cover": real artwork files, the gradient and the lifted accent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerCoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    /** Three synthetic covers: night-blue with an orange sun, green stripes, magenta blocks. */
    private fun covers(): List<Pair<String, Bitmap>> {
        fun art(draw: Canvas.(Paint) -> Unit) = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { Canvas(it).draw(Paint(Paint.ANTI_ALIAS_FLAG)) }
        return listOf(
            "night" to art { p ->
                p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xFF0B1A4A.toInt(), 0xFF1F3C9E.toInt(), Shader.TileMode.CLAMP); drawRect(0f, 0f, 300f, 300f, p)
                p.shader = null; p.color = 0xFFFF7A1A.toInt(); drawCircle(200f, 110f, 55f, p)
            },
            "fern" to art { p ->
                for (i in 0 until 6) { p.color = if (i % 2 == 0) 0xFF1E5631.toInt() else 0xFF76C043.toInt(); drawRect(0f, i * 50f, 300f, i * 50f + 50f, p) }
            },
            "orchid" to art { p ->
                p.color = 0xFF2A0B33.toInt(); drawRect(0f, 0f, 300f, 300f, p)
                p.color = 0xFFD81B9A.toInt(); drawRect(40f, 40f, 180f, 180f, p)
                p.color = 0xFF7B1FA2.toInt(); drawRect(150f, 150f, 280f, 280f, p)
            },
        )
    }

    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        return image!!
    }
    private fun close(a: Int, b: Color, tolerance: Int = 6): Boolean {
        val c = b.toArgb()
        return listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (c shr it and 0xFF)) <= tolerance }
    }

    @Test fun playerTakesItsGroundAndAccentFromEachCover() {
        val tracks = covers().map { (name, bitmap) ->
            val file = folder.newFile("$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val url = file.toURI().toString()
            CoverAccent.prime(url, bitmap)
            Track("fixture:$name", "Afterimage ($name)", "Serein", "https://www.youtube.com/watch?v=jNQXAC9IVRw", artwork = url, durationMs = 224000) to name
        }
        val state = mutableStateOf(MusicState(look = labs.rtensor.music.data.LookChoice.ROOM, current = tracks[0].first, saved = listOf(tracks[0].first), durationMs = 224000, positionMs = 72000,
            reducedMotion = true, theme = "light", routeLabel = "YouTube · AAC compressed 48.0 kHz · stereo · unchanged"))
        assertEquals(AccentChoice.COVER, state.value.accent)
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        for ((track, name) in tracks) {
            compose.runOnIdle { state.value = state.value.copy(current = track, saved = listOf(track)) }
            val colours = CoverAccent.cached(track.artwork)!!
            val tones = playerTones(colours.deep)
            // Let Coil decode the artwork file before the render.
            repeat(40) { Thread.sleep(25); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            val image = snapshot()
            val position = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInWindow
            // Left gutter beside the seek line: the gradient, between the top tone and the darker one.
            val ground = image.getPixel(4, position.center.y.toInt())
            val lum = { c: Int -> ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) }
            assertTrue("$name ground is a cover tone, not paper", !close(ground, Paper, 20))
            assertTrue("$name ground lies within the gradient", lum(ground) <= lum(tones.top.toArgb()) + 6 && lum(ground) >= lum(tones.bottom.toArgb()) - 6)
            val file = File("build/reports/screenshots/player-cover-$name.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun fixedAccentsAndMissingArtworkKeepPaperAndInk() {
        val (name, bitmap) = covers()[1]
        val file = folder.newFile("$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        CoverAccent.prime(file.toURI().toString(), bitmap)
        val withArt = Track("fixture:art", "Afterimage", "Serein", "https://www.youtube.com/watch?v=jNQXAC9IVRw", artwork = file.toURI().toString(), durationMs = 224000)
        val state = mutableStateOf(MusicState(look = labs.rtensor.music.data.LookChoice.ROOM, current = withArt, durationMs = 224000, reducedMotion = true, theme = "light", accent = AccentChoice.MOSS))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        fun ground(): Int {
            val image = snapshot()
            val y = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInWindow.center.y.toInt()
            return image.getPixel(4, y).also { image.recycle() }
        }
        assertTrue("a fixed accent keeps paper", close(ground(), Paper, 2))
        compose.runOnIdle { state.value = state.value.copy(accent = AccentChoice.COVER, current = withArt.copy(id = "fixture:none", artwork = "")) }
        assertTrue("no artwork keeps paper", close(ground(), Paper, 2))
    }
}
