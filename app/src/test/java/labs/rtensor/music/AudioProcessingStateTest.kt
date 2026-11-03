package labs.rtensor.music

import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.playback.AudioProcessingState
import org.junit.Assert.*
import org.junit.Test

class AudioProcessingStateTest {
    private val report = DownmixReport(48000,
        listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC), spatialAvailable = true)

    @Test fun spatialSelectionWaitsForTheRenderAcknowledgementEvenWhenBothModesAreProcessed() {
        val requested = AudioProcessingState(selectedSpatial = true, available = true, report = report)
        assertTrue(requested.comparisonPending)
        assertFalse(requested.copy(report = report.copy(listeningMode = ListeningMode.SPATIAL)).comparisonPending)
        val toSpeaker = requested.copy(selectedSpatial = false, report = report.copy(listeningMode = ListeningMode.SPATIAL))
        assertTrue(toSpeaker.comparisonPending)
        assertFalse(toSpeaker.copy(report = report).comparisonPending)
    }

    @Test fun unsupportedSpatialFallsBackToProcessedWithoutAnEndlessPendingIndicator() {
        val state = AudioProcessingState(selectedSpatial = true, available = true,
            report = report.copy(spatialAvailable = false))
        assertFalse(state.comparisonPending)
        assertFalse(state.copy(report = report.copy(comparisonAvailable = false, processingEnabled = false,
            listeningMode = ListeningMode.ORIGINAL)).comparisonPending)
    }

    @Test fun originalSelectionOverridesTheRememberedSpatialPreference() {
        val state = AudioProcessingState(selectedProcessed = false, selectedSpatial = true, report = report)
        assertEquals(ListeningMode.ORIGINAL, state.selectedMode)
        assertTrue(state.comparisonPending)
        assertFalse(state.copy(report = report.copy(processingEnabled = false,
            listeningMode = ListeningMode.ORIGINAL)).comparisonPending)
    }
}
