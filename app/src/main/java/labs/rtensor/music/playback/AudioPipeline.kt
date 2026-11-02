package labs.rtensor.music.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * The native DSP will attach here, after decode and before the Android audio sink.
 * Currently the signal is unprocessed. No private DSP code is shipped or emulated.
 * A future processor must negotiate format, handle flush/seek and avoid blocking
 * the audio thread. The supplied Windows DLL cannot run on Android.
 */
@UnstableApi
object AudioPipeline {
    fun processors(): Array<AudioProcessor> = emptyArray()
}
