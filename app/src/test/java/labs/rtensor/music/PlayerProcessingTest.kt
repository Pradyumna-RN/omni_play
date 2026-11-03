package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.data.LibraryStore
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerProcessingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:stereo", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val stereo = listOf(SpeakerPosition.FL, SpeakerPosition.FR)
    private fun initial(report: DownmixReport) = MusicState(current = track, queue = listOf(track), queueIndex = 0,
        playing = true, positionMs = 72000, durationMs = 224000, reducedMotion = true,
        audioProcessing = AudioProcessingState(available = true, report = report))

    @Test fun originalProcessedSwitchWorksWithoutSeekingOrStoppingTheSong() {
        val state = mutableStateOf(initial(DownmixReport(48000, stereo, processing = "Stereo enhancement")))
        val changes = mutableListOf<Boolean>(); var seeks = 0; var pauses = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++ }, onToggle = { pauses++ }, onProcessing = { processed ->
            changes += processed
            state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = processed,
                report = state.value.audioProcessing.report!!.copy(processingEnabled = processed)))
        }) }
        compose.onNodeWithContentDescription("Original audio").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Stereo · original audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Processed audio").performClick().assertIsSelected()
        compose.onNodeWithText("Stereo enhancement · generated spatial audio").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(false, true), changes); assertEquals(0, seeks); assertEquals(0, pauses)
            assertEquals(72000L, state.value.positionMs); assertTrue(state.value.playing)
        }
    }

    @Test fun unsupportedStereoRateCannotPretendProcessingIsActive() {
        val report = DownmixReport(88200, stereo, processingEnabled = false, comparisonAvailable = false)
        compose.setContent { MusicApp(initial(report), initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithContentDescription("Original audio").assertIsSelected()
        compose.onNodeWithText("88.2 kHz · processing unavailable").assertIsDisplayed()
    }

    @Test fun multichannelOriginalExplainsThatTheDesktopComparisonKeepsOnlyTheFrontPair() {
        val positions = stereo + listOf(SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
        val state = initial(DownmixReport(48000, positions, processingEnabled = false)).let {
            it.copy(audioProcessing = it.audioProcessing.copy(selectedProcessed = false))
        }
        compose.setContent { MusicApp(state, initialPage = "now") }
        compose.onNodeWithContentDescription("Original audio").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Front L/R only · 6-channel source").assertIsDisplayed()
    }

    @Test fun comparisonPreferenceSurvivesReopeningTheStore() {
        val first = LibraryStore(compose.activity)
        first.processedAudio = false
        assertFalse(LibraryStore(compose.activity).processedAudio)
        first.processedAudio = true
        assertTrue(LibraryStore(compose.activity).processedAudio)
    }
}
