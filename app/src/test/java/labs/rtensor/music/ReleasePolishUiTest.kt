package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MinTextContrast
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.contrastRatio
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Release review fixes: what users and TalkBack actually get. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleasePolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)

    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle { val v = compose.activity.window.decorView; image = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }
        return image!!
    }

    @Test fun thePlayingSongIsMarkedInListsInEveryLook() {
        val state = androidx.compose.runtime.mutableStateOf(MusicState(reducedMotion = true, accent = AccentChoice.SIGNAL, current = track,
            query = "x", searched = true, results = listOf(track, other)))
        compose.setContent { MusicApp(state.value) }
        for (look in LookChoice.entries) for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(look = look, theme = theme) }
            // Not by colour alone: the playing row says so, the other row doesn't.
            compose.onAllNodesWithText("Playing · ").assertCountEquals(1)
            compose.onNode(hasText("Afterimage") and hasText("Playing · ") and hasClickAction()).assertExists()
            compose.onNode(hasText("Soft Signals") and hasText("Playing · ") and hasClickAction()).assertDoesNotExist()
        }
    }

    @Test fun theSeekLineStandsOutWithAFixedAccentOnPaper() {
        compose.setContent { MusicApp(MusicState(theme = "light", reducedMotion = true, accent = AccentChoice.SIGNAL, current = track,
            positionMs = 112000, durationMs = 224000), initialPage = "now") }
        val line = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInWindow
        val image = snapshot()
        // Just left of the thumb, on the played part of the line.
        val played = image.getPixel((line.left + line.width * .5f - 20).toInt(), line.center.y.toInt())
        val paper = image.getPixel((line.left + 2).toInt(), (line.top + 2).toInt())
        image.recycle()
        val ratio = contrastRatio(androidx.compose.ui.graphics.Color(played), androidx.compose.ui.graphics.Color(paper))
        assertTrue("played line against the page: $ratio", ratio >= 3.0)
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
    fun largeTextNeverClipsTheSelectorsOrThePill() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                MusicApp(MusicState(theme = "dark", reducedMotion = true), initialPage = "settings")
            }
        }
        for (name in listOf("Listening room look", "Soft look", "Classic look", "Device theme")) {
            val option = compose.onNodeWithContentDescription(name).performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue("$name is at least 48dp tall", option.height >= 48f)
        }
        // The label sits wholly inside its option, even at twice the text size.
        val option = compose.onNodeWithContentDescription("Listening room look").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Listening room", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("label is not cut off", layouts.single().hasVisualOverflow)
        assertTrue(label.boundsInRoot.bottom <= option.bottom + .5f && label.boundsInRoot.top >= option.top - .5f)
    }

    @Test fun libraryTabsAreFullSizeAndReadOnce() {
        compose.setContent { MusicApp(MusicState(theme = "light", reducedMotion = true), initialPage = "library") }
        for (name in listOf("Saved", "History")) {
            val tab = compose.onNodeWithContentDescription(name)
            tab.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
            // Read once: no "Saved tab, tab", and the label adds nothing.
            tab.assert(SemanticsMatcher("no repeated text") { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.isEmpty() })
            assertTrue("$name tab is 48dp tall", tab.fetchSemanticsNode().boundsInRoot.height >= 48f)
        }
        compose.onNodeWithContentDescription("Saved").assertIsSelected()
        compose.onNodeWithContentDescription("History").performClick().assertIsSelected()
    }
}
