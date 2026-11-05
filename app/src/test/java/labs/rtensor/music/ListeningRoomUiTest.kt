package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import labs.rtensor.dsp.AudioLevelFrame
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.dsp.SpeakerPosition.*
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The room at the top of the Listening room's Home, and its song grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningRoomUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val surround = listOf(FL, FR, FC, LFE, BL, BR)
    private val atmos = listOf(FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR)
    private fun playing(audio: AudioProcessingState) = MusicState(current = track, queue = listOf(track), queueIndex = 0, playing = true,
        durationMs = 224000, reducedMotion = true, theme = "dark", audioProcessing = audio)

    private val isSpeaker = SemanticsMatcher("room speaker") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("room-speaker-") == true }
    private fun speakers() = compose.onAllNodes(isSpeaker, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix("room-speaker-") to it.boundsInRoot }

    @Test fun theRoomDrawsTheSpeakersOfWhatYouHear() {
        val state = mutableStateOf(playing(AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        compose.setContent { MusicApp(state.value) }
        // 5.1, processed: six speakers, placed as in the channel monitor (left on the left).
        assertEquals(surround.map { it.name }.toSet(), speakers().map { it.first }.toSet())
        val at = speakers().toMap()
        assertTrue(at.getValue("FL").center.x < at.getValue("FC").center.x && at.getValue("FC").center.x < at.getValue("FR").center.x)
        assertTrue("rears behind the fronts", at.getValue("BL").center.y > at.getValue("FL").center.y)
        compose.onNodeWithText("you · 5.1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your room: 6 speakers, 5.1", substring = true).assertExists()
        // Atmos 7.1.4: twelve speakers.
        compose.runOnIdle { state.value = playing(AudioProcessingState(available = true, report = DownmixReport(48000, atmos))) }
        assertEquals(12, speakers().size)
        compose.onNodeWithText("you · 7.1.4").assertIsDisplayed()
        // Plain stereo: the pair.
        compose.runOnIdle { state.value = playing(AudioProcessingState(available = true,
            report = DownmixReport(48000, listOf(FL, FR), processingEnabled = false, comparisonAvailable = false))) }
        assertEquals(listOf("FL", "FR"), speakers().map { it.first }.sorted())
        compose.onNodeWithText("you · stereo").assertIsDisplayed()
        // Processing off for a 5.1 source: the plain stereo pair, and the song says original.
        compose.runOnIdle { state.value = playing(AudioProcessingState(available = true, selectedProcessed = false,
            report = DownmixReport(48000, surround, processingEnabled = false, listeningMode = ListeningMode.ORIGINAL))) }
        assertEquals(2, speakers().size)
        compose.onNodeWithContentDescription("Open the player: Afterimage, Serein · original").assertExists()
        // Processing unavailable: the pair too.
        compose.runOnIdle { state.value = playing(AudioProcessingState(available = false, unavailableReason = "No DSP")) }
        assertEquals(2, speakers().size)
    }

    @Test fun theRoomShowsTheSongAndOpensThePlayer() {
        compose.setContent { MusicApp(playing(AudioProcessingState(available = true, report = DownmixReport(48000, surround)))) }
        compose.onNodeWithText("Pick something to fill the room").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open the player: Afterimage, Serein · processed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Now playing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cover for Afterimage. Swipe left for next, right for previous.").assertExists()
    }

    @Test fun anEmptyRoomSaysWhatToDo() {
        compose.setContent { MusicApp(MusicState(reducedMotion = true, theme = "dark")) }
        compose.onNodeWithText("Pick something to fill the room").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your room, quiet").assertExists()
        compose.onNodeWithContentDescription("Open the player", substring = true).assertDoesNotExist()
        assertEquals(2, speakers().size)
        // The existing Home sections follow the room unchanged.
        compose.onNodeWithText("Room to listen").assertIsDisplayed()
        compose.onNodeWithText("Find music").assertExists()
    }

    @Test fun liveLevelsAreAskedForOnlyWhenTheyCanMoveTheLights() {
        val levels = MutableStateFlow<AudioLevelFrame?>(null)
        val audio = AudioProcessingState(available = true, report = DownmixReport(48000, surround))
        val requests = mutableListOf<Boolean>()
        // Without Developer options metering never runs, so the room never asks.
        val plain = mutableStateOf(playing(audio))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicApp(plain.value, audioLevels = levels, onMetering = requests::add) }
        fun frames() { repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
        frames()
        assertTrue(requests.isEmpty())
        // Reduced motion keeps the lights steady: nothing to measure.
        compose.runOnIdle { plain.value = plain.value.copy(developerOptions = true) }
        frames()
        assertFalse("reduced motion $requests", requests.any { it })
        // Playing with motion: the room asks for levels; paused, it lets go.
        compose.runOnIdle { plain.value = plain.value.copy(reducedMotion = false) }
        frames()
        assertTrue("playing with motion $requests", requests.last())
        compose.runOnIdle { plain.value = plain.value.copy(playing = false) }
        frames()
        assertFalse("paused $requests", requests.last())
    }

    @Test fun newSongsShowAsATwoColumnGridAndStillPlayAlone() {
        val songs = (1..6).map { FeedEntry("yt:song$it", "Song $it", "Artist $it", "", "song", "", "song0000$it") }
        val played = mutableListOf<Pair<Track, List<Track>>>()
        compose.setContent { MusicApp(MusicState(feed = listOf(FeedSection("Picks", songs)), reducedMotion = true, theme = "dark"),
            onPlay = { t, q -> played += t to q }) }
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("unheard")
        compose.onNodeWithText("New to your ears").assertIsDisplayed()
        // The last three feed songs: two side by side, the third starting the next row.
        val a = compose.onNodeWithText("Song 4").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Song 5").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithText("Song 6").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top, .5f); assertTrue(b.left > a.right)
        assertTrue(c.top > a.bottom); assertEquals(a.left, c.left, .5f)
        compose.onNodeWithText("Song 6").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Song 6", played.single().first.title); assertEquals(1, played.single().second.size) }
    }
}
