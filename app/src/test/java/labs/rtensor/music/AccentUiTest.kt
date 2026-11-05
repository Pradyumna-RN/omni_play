package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.accentColours
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=jNQXAC9IVRw", durationMs = 224000)
    private val fixed = AccentChoice.entries - AccentChoice.COVER

    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        return image!!
    }
    private fun save(image: Bitmap, name: String) {
        val file = File("build/reports/screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun close(a: Int, b: Color, tolerance: Int = 4): Boolean {
        val c = b.toArgb()
        return listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (c shr it and 0xFF)) <= tolerance }
    }
    private fun pixel(image: Bitmap, x: Float, y: Float) = image.getPixel(x.toInt(), y.toInt())
    private fun anyPixel(image: Bitmap, r: Rect, colour: Color): Boolean {
        for (x in r.left.toInt() until r.right.toInt()) for (y in r.top.toInt() until r.bottom.toInt())
            if (close(image.getPixel(x, y), colour, 24)) return true
        return false
    }
    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInWindow

    @Test fun settingsSwatchesAreLabelledSelectableTargetsThatChangeTheAccent() {
        val state = mutableStateOf(MusicState(theme = "light", reducedMotion = true))
        val chosen = mutableListOf<AccentChoice>()
        compose.setContent { MusicApp(state.value, initialPage = "settings", onAccent = { chosen += it; state.value = state.value.copy(accent = it) }) }
        compose.onNodeWithText("Accent colour").performScrollTo().assertIsDisplayed()
        for (choice in AccentChoice.entries) {
            val node = compose.onNodeWithContentDescription("${choice.label} accent colour").performScrollTo()
            val size = node.getUnclippedBoundsInRoot()
            assertTrue("${choice.label} target is at least 48dp", (size.right - size.left).value >= 48f && (size.bottom - size.top).value >= 48f)
            node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
        }
        // Nobody has chosen yet, so the default "From the cover" is selected.
        compose.onNodeWithContentDescription("From the cover accent colour").assertIsSelected()
        compose.onNodeWithContentDescription("Signal accent colour").assertIsNotSelected()
        compose.onNodeWithContentDescription("Cobalt accent colour").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf(AccentChoice.COBALT), chosen) }
        compose.onNodeWithContentDescription("Cobalt accent colour").assertIsSelected()
        compose.onNodeWithContentDescription("Signal accent colour").assertIsNotSelected()
        save(snapshot(), "settings-accent-cobalt")
        compose.onNodeWithContentDescription("From the cover accent colour").performClick()
        compose.runOnIdle { assertEquals(AccentChoice.COVER, state.value.accent) }
        compose.onNodeWithText("Taken from the artwork of the song that is playing. Signal is used when there is none.").assertExists()
    }

    /** Renders the player for each fixed accent in both themes and checks the Play fill, label and heart. */
    @Test fun playButtonAndHeartUseTheChosenAccentWithReadableContent() {
        val state = mutableStateOf(MusicState(current = track, saved = listOf(track), durationMs = 224000, positionMs = 72000, reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        for (choice in fixed) for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(accent = choice, theme = theme) }
            val expected = accentColours(choice, theme == "dark", null)
            val play = compose.onNodeWithContentDescription("Play").performScrollTo()
            val image = snapshot()
            val button = bounds(play)
            // The round button: its fill beside the glyph, and the glyph in the on-accent colour.
            assertTrue("$choice $theme Play fill", close(pixel(image, button.center.x - 22, button.center.y), expected.accent))
            assertTrue("$choice $theme Play glyph", anyPixel(image, Rect(button.center.x - 12, button.center.y - 12, button.center.x + 12, button.center.y + 12), expected.onAccent))
            val heart = bounds(compose.onNodeWithContentDescription("Saved"))
            assertTrue("$choice $theme heart fill", close(pixel(image, heart.left + 6, heart.top + 6), expected.accent))
            assertTrue("$choice $theme heart icon", anyPixel(image, Rect(heart.center.x - 12, heart.center.y - 12, heart.center.x + 12, heart.center.y + 12), expected.onAccent))
            save(image, "player-accent-${choice.wire}-$theme")
            image.recycle()
        }
    }

    /** The dock is black in both themes: the chosen tab's three lights must show for every accent, Ink included. */
    @Test fun dockTabLightsStayVisibleForEveryAccent() {
        val state = mutableStateOf(MusicState(current = track, durationMs = 224000, reducedMotion = true))
        compose.setContent { MusicApp(state.value) }
        for (choice in fixed) for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(accent = choice, theme = theme) }
            val image = snapshot()
            val expected = accentColours(choice, theme == "dark", null).onDock
            // The lights sit above the label, in the top part of the tab.
            fun lights(tab: Rect) = Rect(tab.center.x - 12, tab.top + 8, tab.center.x + 12, tab.top + 22)
            val home = bounds(compose.onNodeWithContentDescription("Home"))
            assertTrue("$choice $theme lights on the chosen tab", anyPixel(image, lights(home), expected))
            assertFalse("$choice $theme no lights on other tabs", anyPixel(image, lights(bounds(compose.onNodeWithContentDescription("Explore"))), expected))
            if (choice == AccentChoice.INK && theme == "light") save(image, "home-accent-ink-light-dock")
            image.recycle()
        }
    }
}
