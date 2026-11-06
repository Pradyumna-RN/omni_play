package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.SignalAccent
import labs.rtensor.music.ui.accentColours
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Classic is the 0.3.32 Signal look: its labels, its square controls and its player, with today's behaviour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassicLookUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)
    private val surround = listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun classic() = MusicState(look = LookChoice.CLASSIC, reducedMotion = true, theme = "light")
    private fun song(id: String) = FeedEntry("yt:$id", "Song $id", "Artist $id", "", "song", "", id)

    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        return image!!
    }
    private fun close(a: Int, b: Color, tolerance: Int = 4): Boolean {
        val c = b.toArgb()
        return listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (c shr it and 0xFF)) <= tolerance }
    }

    @Test fun classicHomeAndTabsUseFullStopHeadlinesAndNoRoom() {
        compose.setContent { MusicApp(classic()) }
        compose.onNodeWithText("For your ears.").assertIsDisplayed()
        compose.onNodeWithText("Pick something to fill the room").assertDoesNotExist()
        compose.onNodeWithContentDescription("Your room", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Explore.").assertIsDisplayed()
        compose.onNodeWithText("START WITH A SONG").assertIsDisplayed()
        compose.onNodeWithText("Find your\nnext repeat.").assertExists()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Library.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test fun classicHomeSectionsKeepTheirUppercaseMonoLabelsAndControls() {
        var refreshed = 0
        val played = mutableListOf<Pair<Track, List<Track>>>()
        val feed = listOf(FeedSection("Picks", listOf(song("ccccccccccc"), song("ddddddddddd"))))
        compose.setContent {
            MusicApp(classic().copy(history = listOf(track, other), feed = feed, feedError = "Feed unavailable"), initialPage = "explore",
                onRefreshFeed = { refreshed++ }, onPlay = { t, q -> played += t to q })
        }
        compose.onNodeWithText("Explore.").assertIsDisplayed()
        compose.onNodeWithText("LAST PLAYED").assertIsDisplayed()
        compose.onNodeWithText("RETRY").performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
        // The unheard songs stay in Classic's accent block, as a list.
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("unheard")
        compose.onNodeWithText("NEW HERE").assertIsDisplayed()
        compose.onNodeWithText("New to your ears").assertIsDisplayed()
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("hero")
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(listOf(track to listOf(track)), played) }
    }

    @Test fun classicPlayerKeepsTheWidePlayButtonAndTheTwoSegmentSwitch() {
        val state = mutableStateOf(classic().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true,
            durationMs = 224000, positionMs = 72000, routeLabel = "YouTube · AAC compressed 48.0 kHz · stereo · unchanged",
            audioProcessing = AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        val changes = mutableListOf<Boolean>(); var toggles = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onToggle = { toggles++ }, onProcessing = {
            changes += it; state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = it))
        }) }
        compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
        // One wide Play/Pause button that shows its word, between square Previous and Next.
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithContentDescription("Previous").assertIsEnabled()
        compose.onNodeWithContentDescription("Next").assertIsEnabled()
        compose.onNodeWithContentDescription("Save").assertExists()
        // No codec badge in Classic: the route label below still names the format.
        compose.onNodeWithContentDescription("Audio format", substring = true).assertDoesNotExist()
        // ORIGINAL | PROCESSED: two selectable segments, not a switch.
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("ORIGINAL").assertExists(); compose.onNodeWithText("PROCESSED").assertExists()
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Original audio").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf(false), changes) }
        compose.onNodeWithContentDescription("Original audio").assertIsSelected()
        compose.onNodeWithText("Switching to original…").assertIsDisplayed()
        compose.onNodeWithText("1 SONG").performScrollTo().assertIsDisplayed()
    }

    @Test fun classicAlwaysUsesTheSignalOrange() {
        val state = mutableStateOf(classic().copy(current = track, durationMs = 224000, accent = AccentChoice.COBALT))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        fun fill(): Int {
            val play = compose.onNodeWithText("Play").performScrollTo().fetchSemanticsNode().boundsInWindow
            val image = snapshot()
            return image.getPixel((play.left + 8).toInt(), play.center.y.toInt()).also { image.recycle() }
        }
        assertTrue("Classic ignores the chosen Cobalt", close(fill(), SignalAccent))
        // The Listening room honours the same stored choice.
        compose.runOnIdle { state.value = state.value.copy(look = LookChoice.ROOM) }
        val play = compose.onNodeWithContentDescription("Play").performScrollTo().fetchSemanticsNode().boundsInWindow
        val image = snapshot()
        assertTrue("Listening room uses Cobalt", close(image.getPixel((play.center.x - 22).toInt(), play.center.y.toInt()), accentColours(AccentChoice.COBALT, false, null).accent))
        image.recycle()
    }
}
