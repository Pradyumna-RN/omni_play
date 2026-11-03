package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
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
    private val surround = stereo + listOf(SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun initial(report: DownmixReport) = MusicState(current = track, queue = listOf(track), queueIndex = 0,
        playing = true, positionMs = 72000, durationMs = 224000, reducedMotion = true,
        audioProcessing = AudioProcessingState(available = true, report = report))

    @Test fun originalProcessedSwitchWorksWithoutSeekingOrStoppingTheSong() {
        val state = mutableStateOf(initial(DownmixReport(48000, surround, processing = "Multichannel render")))
        val changes = mutableListOf<Boolean>(); var seeks = 0; var pauses = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++ }, onToggle = { pauses++ }, onProcessing = { processed ->
            changes += processed
            state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = processed))
        }) }
        compose.onNodeWithContentDescription("Original audio").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Switching to original…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(processingEnabled = false, listeningMode = ListeningMode.ORIGINAL))) }
        compose.onNodeWithText("Front L/R only · 6-channel source").assertIsDisplayed()
        compose.onNodeWithContentDescription("Processed audio").performClick().assertIsSelected()
        compose.onNodeWithText("Switching to processed…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(processingEnabled = true, listeningMode = ListeningMode.PROCESSED))) }
        compose.onNodeWithText("6 channels → stereo").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(false, true), changes); assertEquals(0, seeks); assertEquals(0, pauses)
            assertEquals(72000L, state.value.positionMs); assertTrue(state.value.playing)
        }
    }

    @Test fun spatialAndProcessedWaitForTheirDistinctRendererAcknowledgements() {
        val state = mutableStateOf(initial(DownmixReport(48000, surround, spatialAvailable = true, upmix916 = true)))
        var seeks = 0; var pauses = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++ }, onToggle = { pauses++ },
            onSpatial = { selected -> state.value = state.value.copy(audioProcessing =
                state.value.audioProcessing.copy(selectedSpatial = selected)) },
            onProcessing = { selected -> state.value = state.value.copy(audioProcessing =
                state.value.audioProcessing.copy(selectedProcessed = selected)) }) }
        compose.onNodeWithContentDescription("Spatial headphone audio").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Switching to spatial…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(listeningMode = ListeningMode.SPATIAL))) }
        compose.onNodeWithText("6 channels + 9.1.6 upmix → headphones (binaural)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Processed audio").performClick().assertIsSelected()
        compose.onNodeWithText("Switching to processed…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(listeningMode = ListeningMode.PROCESSED))) }
        compose.onNodeWithText("6 channels + 9.1.6 upmix → stereo").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, seeks); assertEquals(0, pauses); assertEquals(72000L, state.value.positionMs) }
    }

    @Test fun unsupportedStereoRateCannotPretendProcessingIsActive() {
        val report = DownmixReport(88200, stereo, processingEnabled = false, comparisonAvailable = false)
        compose.setContent { MusicApp(initial(report), initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").assertDoesNotExist()
        compose.onNodeWithContentDescription("Original audio").assertDoesNotExist()
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
        first.spatialAudio = true
        assertTrue(LibraryStore(compose.activity).spatialAudio)
        first.spatialAudio = false
        assertFalse(LibraryStore(compose.activity).spatialAudio)
    }

    @Test fun pausedSelectionWaitsForAudioInsteadOfShowingTheOppositeMode() {
        val state = mutableStateOf(initial(DownmixReport(48000, surround, processingEnabled = false)).let {
            it.copy(playing = false, positionMs = 0, audioProcessing = it.audioProcessing.copy(selectedProcessed = false))
        })
        var seeks = 0; var pauses = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++ }, onToggle = { pauses++ }, onProcessing = { processed ->
            // The renderer receives no PCM while paused. Only the requested mode changes.
            state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = processed))
        }) }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Processed · ready when you play").assertIsDisplayed()
        compose.onNodeWithText("Front L/R only · 6-channel source").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(playing = true) }
        compose.onNodeWithText("Switching to processed…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(
            report = state.value.audioProcessing.report!!.copy(processingEnabled = true, listeningMode = ListeningMode.PROCESSED))) }
        compose.onNodeWithText("6 channels → stereo").assertIsDisplayed()
        compose.onNodeWithText("Switching to processed…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, seeks); assertEquals(0, pauses); assertEquals(0L, state.value.positionMs) }
    }

    @Test fun reversingAPausedRequestCancelsThePendingMode() {
        val state = mutableStateOf(initial(DownmixReport(48000, surround, processingEnabled = false)).let {
            it.copy(playing = false, audioProcessing = it.audioProcessing.copy(selectedProcessed = false))
        })
        compose.setContent { MusicApp(state.value, initialPage = "now", onProcessing = { processed ->
            state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = processed))
        }) }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().performClick()
        compose.onNodeWithText("Processed · ready when you play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Original audio").performClick().assertIsSelected()
        compose.onNodeWithText("Processed · ready when you play").assertDoesNotExist()
        compose.onNodeWithText("Front L/R only · 6-channel source").assertIsDisplayed()
    }

    @Test fun loadingKeepsTheRequestedSelectionWithoutClaimingTheRendererAppliedIt() {
        val state = initial(DownmixReport(48000, surround)).let { it.copy(audioProcessing = it.audioProcessing.copy(report = null)) }
        compose.setContent { MusicApp(state, initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").assertDoesNotExist()
        compose.onNodeWithText("6 channels → stereo").assertDoesNotExist()
    }

    @Test fun unknownLayoutShowsDisabledComparisonAndItsReason() {
        val reason = "The decoder did not report speaker positions"
        val report = DownmixReport(48000, emptyList(), outputChannels = 6, processingEnabled = false,
            comparisonAvailable = false, unavailableReason = reason, sourceChannels = 6)
        compose.setContent { MusicApp(initial(report), initialPage = "now") }
        compose.onNodeWithContentDescription("Original audio").performScrollTo().assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("Processed audio").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithText("6 channels · original audio · $reason").assertIsDisplayed()
        compose.onNodeWithText("6 channels → stereo").assertDoesNotExist()
    }

    @Test fun missingDspShowsAnExplanationInsteadOfHidingTheControl() {
        val state = initial(DownmixReport(48000, surround)).copy(audioProcessing =
            AudioProcessingState(available = false, unavailableReason = "The DSP library is not included"))
        compose.setContent { MusicApp(state, initialPage = "now") }
        compose.onNodeWithContentDescription("Original audio").performScrollTo().assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("Processed audio").assertIsNotEnabled()
        compose.onNodeWithText("Processing unavailable · The DSP library is not included").assertIsDisplayed()
    }
}
