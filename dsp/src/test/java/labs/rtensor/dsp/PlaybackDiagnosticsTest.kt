package labs.rtensor.dsp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackDiagnosticsTest {
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(6).setSampleRate(48000).build()

    private fun renderer(cause: Exception) = ExoPlaybackException.createForRenderer(cause, "MusicFfmpegBedAudioRenderer", 1, pcm,
        androidx.media3.common.C.FORMAT_HANDLED, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)

    @Test fun aLayoutRejectionInsideTheSinkKeepsItsStageAndReason() {
        val layout = DownmixException(DownmixStage.LAYOUT, "Unsupported speaker positions in decoded layout 0x70f (unmapped 0x100)")
        val failure = PlaybackDiagnostics.describe(renderer(AudioSink.ConfigurationException(layout, pcm)))!!
        assertEquals(DownmixStage.LAYOUT, failure.stage)
        assertTrue(failure.message, "0x70f" in failure.message && failure.message.startsWith("Speaker layout"))
        assertTrue(failure.detail, "ConfigurationException" in failure.detail && "6 ch 48000 Hz" in failure.detail)
    }

    @Test fun eachPipelineStageIsClassifiedByType() {
        assertEquals(DownmixStage.DECODER, PlaybackDiagnostics.describe(renderer(FfmpegDecoderException("Error decoding (see logcat).")))!!.stage)
        assertEquals(DownmixStage.DSP_LIBRARY, PlaybackDiagnostics.describe(renderer(AudioSink.ConfigurationException(
            DownmixException(DownmixStage.DSP_LIBRARY, "not included", UnsatisfiedLinkError("no rtensor_downmix")), pcm)))!!.stage)
        assertEquals(DownmixStage.FORMAT, PlaybackDiagnostics.describe(renderer(AudioSink.ConfigurationException(
            IllegalArgumentException("The software decoder returned stereo for a multichannel source"), pcm)))!!.stage)
        assertEquals(DownmixStage.OUTPUT, PlaybackDiagnostics.describe(renderer(AudioSink.InitializationException(-12, 48000, 12, 4096, pcm, false, null)))!!.stage)
        assertEquals(DownmixStage.OUTPUT, PlaybackDiagnostics.describe(renderer(AudioSink.WriteException(-6, pcm, false)))!!.stage)
        assertEquals(DownmixStage.DECODER_LIBRARY, PlaybackDiagnostics.describe(UnsatisfiedLinkError("libffmpegJNI.so"))!!.stage)
    }

    @Test fun theDeepestMessageIsShownWhenNoStageMessageExists() {
        val failure = PlaybackDiagnostics.describe(renderer(AudioSink.ConfigurationException(IllegalArgumentException("The output cannot accept float PCM"), pcm)))!!
        assertTrue(failure.message, failure.message.endsWith("The output cannot accept float PCM"))
    }

    @Test fun networkAndNullErrorsAreNotAudioFailures() {
        assertNull(PlaybackDiagnostics.describe(null))
        assertNull(PlaybackDiagnostics.describe(ExoPlaybackException.createForSource(IOException("timeout"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
    }

    @Test fun cyclicAndDeepChainsAreBounded() {
        val a = IllegalStateException("a"); val b = DownmixException(DownmixStage.DSP, "b", a); a.initCause(b)
        val failure = PlaybackDiagnostics.describe(a)!!
        assertEquals(DownmixStage.DSP, failure.stage)
        var deep: Throwable = DownmixException(DownmixStage.DSP, "deep")
        repeat(50) { deep = RuntimeException("level $it", deep) }
        assertNotNull(PlaybackDiagnostics.describe(deep)?.detail) // depth-limited, does not throw
    }

    @Test fun theEnvironmentLineNamesAbisDecoderAndDspState() {
        val line = PlaybackDiagnostics.environment(arrayOf("arm64-v8a"))
        assertTrue(line, line.startsWith("ABIs=arm64-v8a;") && "FFmpeg" in line && "DSP" in line)
        if (!NativeDownmixEngine.isAvailable) assertNotNull(NativeDownmixEngine.unavailableReason)
    }
}
