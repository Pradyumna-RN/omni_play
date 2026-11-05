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
import androidx.compose.ui.graphics.toArgb
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
            // Named once: the name is the label itself, with no "tab" word added (TalkBack adds the role).
            tab.assert(SemanticsMatcher("named $name") { it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] == listOf(name) })
            assertTrue("$name tab is 48dp tall", tab.fetchSemanticsNode().boundsInRoot.height >= 48f)
        }
        compose.onNodeWithContentDescription("Saved").assertIsSelected()
        compose.onNodeWithContentDescription("History").performClick().assertIsSelected()
    }

    @Test fun theDockMarksTheChosenTabWithOneLight() {
        compose.setContent { MusicApp(MusicState(theme = "dark", reducedMotion = true, current = track)) }
        val image = snapshot()
        val home = compose.onNodeWithContentDescription("Home").fetchSemanticsNode().boundsInWindow
        val light = labs.rtensor.music.ui.accentColours(AccentChoice.COVER, true, null).onDock
        // Count separate lit runs across the top of the chosen tab: one light, not three dots.
        var runs = 0; var inRun = false
        val y = compose.onAllNodesWithTag("tab-light", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInWindow.center.y.toInt()
        for (x in home.left.toInt() until home.right.toInt()) {
            val c = image.getPixel(x, y)
            val lit = listOf(16, 8, 0).all { kotlin.math.abs((c shr it and 0xFF) - (light.toArgb() shr it and 0xFF)) <= 40 }
            if (lit && !inRun) runs++
            inRun = lit
        }
        image.recycle()
        assertEquals(1, runs)
    }

    @Test fun errorsLookLikeErrorsSayWhatHappenedAndAreAnnounced() {
        for (look in LookChoice.entries) {
            assertEquals("The music service wouldn't play this song", labs.rtensor.music.ui.friendlyError("YouTube rejected the audio stream (HTTP 403)."))
            assertNull(labs.rtensor.music.ui.friendlyError("Nothing found"))
        }
        compose.setContent { MusicApp(MusicState(theme = "light", reducedMotion = true, current = track, error = "YouTube rejected the audio stream (HTTP 403).")) }
        compose.onNodeWithText("The music service wouldn't play this song").assertIsDisplayed()
        compose.onNodeWithText("YouTube rejected the audio stream (HTTP 403).").assertIsDisplayed()
        compose.onNodeWithContentDescription("Error").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite)).assertExists()
        // Not the Play colour: the banner's fill is the quiet panel, not the accent.
        val image = snapshot()
        val banner = compose.onNodeWithText("The music service wouldn't play this song").fetchSemanticsNode().boundsInWindow
        val fill = image.getPixel((banner.right + 30).toInt().coerceAtMost(image.width - 30), banner.top.toInt() - 4)
        image.recycle()
        assertNotEquals(labs.rtensor.music.ui.SignalAccent.toArgb(), fill)
    }

    @Test fun listeningRoomWordingIsPlainAndClassicKeepsItsOwn() {
        val now = System.currentTimeMillis()
        assertEquals("5 min ago", labs.rtensor.music.ui.whenLabel(now - 5 * 60_000L, now, classic = false))
        assertEquals("Just now", labs.rtensor.music.ui.whenLabel(now, now, classic = false))
        assertEquals("NOW", labs.rtensor.music.ui.whenLabel(now, now, classic = true))
        val state = androidx.compose.runtime.mutableStateOf(MusicState(theme = "dark", reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "settings") }
        // Settings is named once, by the header, and that name is the page heading.
        compose.onAllNodesWithText("Settings").assertCountEquals(1)
        compose.onNode(hasText("Settings") and isHeading()).assertExists()
        compose.onNodeWithText("Channel monitor, and live speaker levels on Home and in the player").performScrollTo().assertExists()
        compose.runOnIdle { state.value = state.value.copy(look = LookChoice.CLASSIC) }
        compose.onNodeWithText("Square controls, monospace labels and Signal orange.").performScrollTo().assertExists()
        compose.onNodeWithText("0.3.32", substring = true).assertDoesNotExist()
    }

    /** The clock moves the progress lines ten times a second, not on every frame. */
    @Test fun thePlaybackClockTicksAboutTenTimesASecondNotEveryFrame() {
        compose.mainClock.autoAdvance = false
        val position = kotlinx.coroutines.flow.MutableStateFlow(72_000L)
        compose.setContent { MusicApp(MusicState(theme = "dark", reducedMotion = false, current = track, playing = true, positionMs = 72_000, durationMs = 224_000),
            initialPage = "now", playbackPosition = position) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
        val line = compose.onNodeWithContentDescription("Playback position")
        fun fraction() = line.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        val seen = mutableSetOf<Float>()
        // One second of 16ms frames: the shown time changes about ten times, not sixty.
        repeat(62) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle(); seen += fraction() }
        assertTrue("updates in a second: ${seen.size}", seen.size in 5..12)
        assertTrue("and it still moves", seen.max() > seen.min())
    }
}
