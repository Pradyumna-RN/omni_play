package labs.rtensor.music

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.ProviderStream
import labs.rtensor.music.sources.SourcePlaybackInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class AudioSourceLabelTest {
    private val apple = ProviderStream("song", MusicProvider.APPLE_MUSIC, "https://cdn.example/audio", "Song", "Artist",
        180000, "audio/alac", 192000, 24, 6)
    private fun info(mime: String, channels: Int, rate: Int = 48000) = SourcePlaybackInfo(apple, "YouTube",
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(rate).build())

    @Test fun aacIdentifiesAppleAudioWithoutBorrowingLosslessMetadata() {
        val label = info("audio/mp4a-latm", 2).label(DownmixReport(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR), processingEnabled = false))
        assertTrue(label.startsWith("Apple Music · AAC compressed 48.0 kHz"))
        assertTrue(label.contains("stereo · unchanged"))
        assertFalse(label.contains("lossless")); assertFalse(label.contains("192")); assertFalse(label.contains("DMR"))
    }
    @Test fun pendingAndFallbackNeverInheritAnAppleQualityClaim() {
        assertEquals("Apple Music · checking audio…", SourcePlaybackInfo(apple, "YouTube").label(null))
        assertEquals("YouTube · compressed stream", SourcePlaybackInfo(null, "YouTube · compressed stream").label(null))
        assertEquals("Apple Music · ALAC lossless 96.0 kHz · stereo source · checking output", info("audio/alac", 2, 96000).label(null))
    }
    @Test fun missingSpeakerPositionsStillReportsTheRealMultichannelSource() {
        val report = DownmixReport(48000, emptyList(), processingEnabled = false, comparisonAvailable = false,
            unavailableReason = "Unknown speaker layout", sourceChannels = 6)
        val label = info("audio/eac3", 6).label(report)
        assertTrue(label.contains("6 ch · original audio · processing unavailable"))
        assertFalse(label.contains("stereo")); assertFalse(label.contains("front L/R"))
    }
    @Test fun surroundBedAndLosslessAreReportedAsDistinctFormats() {
        val bed = DownmixReport(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC,
            SpeakerPosition.LFE, SpeakerPosition.SL, SpeakerPosition.SR))
        val label = info("audio/eac3-joc", 6).label(bed)
        assertTrue(label.startsWith("Apple Music · E-AC-3 JOC")); assertTrue(label.contains("6 ch → DMR stereo"))
        assertFalse(label.contains("lossless")); assertFalse(label.contains("9.1.6"))
        assertTrue(info("audio/alac", 6).label(bed).contains("ALAC lossless"))
    }
}
