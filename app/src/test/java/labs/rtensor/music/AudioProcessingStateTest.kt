package labs.rtensor.music

import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.playback.AudioProcessingState
import org.junit.Assert.*
import org.junit.Test

class AudioProcessingStateTest {
    private val report = DownmixReport(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC))
    @Test fun originalSelectionWaitsForTheRendererWithoutChangingTheSourceLayout() {
        val state = AudioProcessingState(selectedProcessed = false, report = report)
        assertEquals(ListeningMode.ORIGINAL, state.selectedMode); assertTrue(state.comparisonPending)
        assertFalse(state.copy(report = report.copy(processingEnabled = false, listeningMode = ListeningMode.ORIGINAL)).comparisonPending)
    }
    @Test fun processedSelectionWaitsForAcknowledgement() {
        val state = AudioProcessingState(report = report.copy(processingEnabled = false, listeningMode = ListeningMode.ORIGINAL))
        assertTrue(state.comparisonPending); assertFalse(state.copy(report = report).comparisonPending)
    }
    @Test fun bypassDoesNotShowAnEndlessPendingComparison() {
        assertFalse(AudioProcessingState(report = report.copy(comparisonAvailable = false)).comparisonPending)
        assertEquals(listOf(ListeningMode.ORIGINAL, ListeningMode.PROCESSED), ListeningMode.entries)
    }
}
