package labs.rtensor.music.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import labs.rtensor.dsp.DownmixRenderersFactory
import labs.rtensor.dsp.NativeDownmixEngine
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.AudioFailure
import labs.rtensor.dsp.PlaybackDiagnostics
import labs.rtensor.music.data.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AudioProcessingState(
    val selectedProcessed: Boolean = true,
    val available: Boolean = false,
    val report: DownmixReport? = null,
    /** Why the last audio pipeline stopped, classified where the cause chain is intact. */
    val failure: AudioFailure? = null,
) {
    /** A selection is a request; only the render callback acknowledges it. */
    val comparisonPending: Boolean get() = report?.let { it.comparisonAvailable && it.processingEnabled != selectedProcessed } == true
}

/**
 * The optional private Android core processes explicit multichannel PCM before
 * output. Stereo bypasses the core without conversion or upmixing.
 * This does not capture or decode a provider-owned protected player.
 */
@UnstableApi
object AudioPipeline {
    val hasPrivateRenderer: Boolean get() = NativeDownmixEngine.isAvailable
    private val mutable = MutableStateFlow(AudioProcessingState(available = hasPrivateRenderer))
    val state = mutable.asStateFlow()

    fun selectProcessing(processed: Boolean) { mutable.update { it.copy(selectedProcessed = processed) } }
    fun clearReport() { mutable.update { it.copy(report = null, failure = null) } }

    /** Records an audio-stage failure; returns null for errors outside the audio chain. */
    fun reportFailure(error: Throwable): AudioFailure? {
        val failure = PlaybackDiagnostics.describe(error)
        mutable.update { it.copy(failure = failure) }
        if (failure != null) Log.w(TAG, "${failure.message} | ${failure.detail}")
        return failure
    }

    private var environmentLogged = false
    private fun logEnvironment() {
        if (environmentLogged) return
        environmentLogged = true
        Log.i(TAG, PlaybackDiagnostics.environment(android.os.Build.SUPPORTED_ABIS))
    }

    fun privateRenderers(context: Context): DefaultRenderersFactory? {
        selectProcessing(LibraryStore(context).processedAudio)
        logEnvironment()
        return if (!hasPrivateRenderer && !NativeDownmixEngine.wasPackaged) null else DownmixRenderersFactory(context,
            processingEnabled = { mutable.value.selectedProcessed }) { report ->
            mutable.update { it.copy(report = report, failure = if (report != null) null else it.failure) }
            if (report != null) Log.i(TAG, "${if (report.processingEnabled) report.processing else "Original"} ${report.inputPositions} -> ${report.outputChannels} channels; source=${report.sampleRate} Hz, render=${report.renderSampleRate} Hz; Atmos verification=${report.atmosVerified}")
        }
    }

    private const val TAG = "AudioPipeline"

    /** Retained for ordinary builds; float/multichannel DSP uses the sink wrapper. */
    fun processors(): Array<AudioProcessor> = emptyArray()
}
