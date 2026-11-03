package labs.rtensor.music.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import labs.rtensor.dsp.DownmixRenderersFactory
import labs.rtensor.dsp.NativeDownmixEngine

/**
 * The optional private Android core processes explicit multichannel PCM before
 * output. Public-source builds contain the adapter only; ordinary stereo bypasses
 * DSP. This does not capture or decode a provider-owned protected player.
 */
@UnstableApi
object AudioPipeline {
    val hasPrivateRenderer: Boolean get() = NativeDownmixEngine.isAvailable
    fun privateRenderers(context: Context): DefaultRenderersFactory? =
        if (!hasPrivateRenderer && !NativeDownmixEngine.wasPackaged) null else DownmixRenderersFactory(context) { report ->
            if (report != null) Log.i("AudioPipeline", "DSP ${report.inputPositions} -> ${report.outputChannels} channels at ${report.sampleRate} Hz; Atmos verification=${report.atmosVerified}")
        }

    /** Retained for ordinary builds; float/multichannel DSP uses the sink wrapper. */
    fun processors(): Array<AudioProcessor> = emptyArray()
}
