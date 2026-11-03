package labs.rtensor.music.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import labs.rtensor.dsp.DownmixRenderersFactory
import labs.rtensor.dsp.NativeDownmixEngine
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.music.data.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AudioProcessingState(
    val selectedProcessed: Boolean = true,
    val available: Boolean = false,
    val report: DownmixReport? = null,
)

/**
 * The optional private Android core processes explicit multichannel PCM before
 * output. Supported stereo uses the original core's optional monitor upmix.
 * This does not capture or decode a provider-owned protected player.
 */
@UnstableApi
object AudioPipeline {
    val hasPrivateRenderer: Boolean get() = NativeDownmixEngine.isAvailable
    private val mutable = MutableStateFlow(AudioProcessingState(available = hasPrivateRenderer))
    val state = mutable.asStateFlow()

    fun selectProcessing(processed: Boolean) { mutable.update { it.copy(selectedProcessed = processed) } }

    fun privateRenderers(context: Context): DefaultRenderersFactory? {
        selectProcessing(LibraryStore(context).processedAudio)
        return if (!hasPrivateRenderer && !NativeDownmixEngine.wasPackaged) null else DownmixRenderersFactory(context,
            processingEnabled = { mutable.value.selectedProcessed }) { report ->
            mutable.update { it.copy(report = report) }
            if (report != null) Log.i("AudioPipeline", "${if (report.processingEnabled) report.processing else "Original"} ${report.inputPositions} -> ${report.outputChannels} channels; source=${report.sampleRate} Hz, render=${report.renderSampleRate} Hz; Atmos verification=${report.atmosVerified}")
        }
    }

    /** Retained for ordinary builds; float/multichannel DSP uses the sink wrapper. */
    fun processors(): Array<AudioProcessor> = emptyArray()
}
