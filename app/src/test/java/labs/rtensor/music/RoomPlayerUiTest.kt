package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.dsp.SpeakerPosition.*
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

/** The Listening room player: poster title, artist and badge, the pill switch and the round Play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomPlayerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val surround = listOf(FL, FR, FC, LFE, BL, BR)
    private fun player(audio: AudioProcessingState) = MusicState(current = track, queue = listOf(track), queueIndex = 0, playing = true,
        positionMs = 72000, durationMs = 224000, reducedMotion = true, theme = "dark",
        routeLabel = "YouTube · AAC compressed 48.0 kHz · stereo · unchanged", audioProcessing = audio)

    @Test fun posterTitleReadsAsWrittenWithArtistAndBadge() {
        compose.setContent { MusicApp(player(AudioProcessingState()), initialPage = "now") }
        compose.onNodeWithText("Afterimage").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        compose.onNodeWithText("Serein").assertIsDisplayed()
        compose.onNodeWithContentDescription("Audio format AAC · 48 kHz").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun longTitlesShrinkToTwoLinesWithoutMovingTheControls() {
        val state = mutableStateOf(player(AudioProcessingState()))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        val play = compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(current = track.copy(title = "An unusually long recording title that needs several lines")) }
        compose.waitForIdle()
        assertEquals(play.top, compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot.top, .5f)
    }

    @Test fun thePillSwitchKeepsTheProcessingBehaviour() {
        val state = mutableStateOf(player(AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        val changes = mutableListOf<Boolean>(); var seeks = 0; var toggles = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++ }, onToggle = { toggles++ }, onProcessing = {
            changes += it; state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = it))
        }) }
        val pill = compose.onNodeWithContentDescription("Processed audio").performScrollTo()
        // Both options show; one switch for TalkBack.
        pill.assertIsOn().assertIsEnabled()
        compose.onNodeWithText("Original", substring = true).assertExists()
        compose.onNodeWithText("Processed · 5.1", substring = true).assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        pill.performClick().assertIsOff()
        compose.onNodeWithText("Switching to original…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(processingEnabled = false, listeningMode = ListeningMode.ORIGINAL))) }
        compose.onNodeWithText("Front L/R only · 6-channel source").performScrollTo().assertIsDisplayed()
        pill.performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(false, true), changes); assertEquals(0, seeks); assertEquals(0, toggles) }
    }

    @Test fun thePillIsDisabledWithTheSameExplanationWhenProcessingIsMissing() {
        compose.setContent { MusicApp(player(AudioProcessingState(available = false, unavailableReason = "The DSP library is not included")), initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Processing unavailable · The DSP library is not included").performScrollTo().assertIsDisplayed()
    }

    @Test fun theMeterNeverAsksForLevelsWithoutDeveloperOptionsOrWithReducedMotion() {
        val requests = mutableListOf<Boolean>()
        val state = mutableStateOf(player(AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        compose.setContent { MusicApp(state.value, initialPage = "now", audioLevels = kotlinx.coroutines.flow.MutableStateFlow(null), onMetering = requests::add) }
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        compose.runOnIdle { state.value = state.value.copy(developerOptions = true) }
        compose.waitForIdle()
        assertFalse("reduced motion keeps the meter still: $requests", requests.any { it })
        // A meter that cannot move is not drawn at all (it would look broken).
        compose.onNodeWithTag("level-meter", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theMeterShowsOnlyWhileLiveLevelsMoveIt() {
        val levels = kotlinx.coroutines.flow.MutableStateFlow<labs.rtensor.dsp.AudioLevelFrame?>(null)
        val state = mutableStateOf(player(AudioProcessingState(available = true, report = DownmixReport(48000, surround))).copy(reducedMotion = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicApp(state.value, initialPage = "now", audioLevels = levels) }
        fun frames() { repeat(5) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle() }
        frames()
        compose.onNodeWithTag("level-meter", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(developerOptions = true) }
        frames()
        compose.onNodeWithTag("level-meter", useUnmergedTree = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(playing = false) }
        frames()
        compose.onNodeWithTag("level-meter", useUnmergedTree = true).assertDoesNotExist()
    }
}
