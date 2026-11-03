package labs.rtensor.dsp

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Uses the device decoder, then the app-owned PCM render. No Apple Music SDK
 * capture, Atmos object decoder or system loopback is implemented. Stereo
 * processing uses the original renderer's optional stereo upmix. */
@UnstableApi
class DownmixRenderersFactory(
    context: Context,
    private val processingEnabled: () -> Boolean = { true },
    private val onReport: (DownmixReport?) -> Unit = {},
) : DefaultRenderersFactory(context) {
    private var positions: List<SpeakerPosition>? = null
    init { setEnableAudioFloatOutput(true) }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return DownmixAudioSink(delegate, decodedPositions = { channels ->
            val decoded = positions ?: error("The decoder did not expose a supported speaker layout; refusing an unverified downmix")
            require(decoded.size == channels) { "Decoder speaker layout changed without a format notification" }
            decoded
        }, onReport = onReport, processingEnabled = processingEnabled)
    }

    override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
        out.add(object : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, audioSink) {
            override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
                try {
                    val count = if (mediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_COUNT) == true)
                        mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else format.channelCount
                    val multichannelRequired = format.channelCount > 2 || format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC
                    require(!multichannelRequired || count > 2) { "The decoder folded the multichannel recording to stereo before DSP" }
                    val mask = if (mediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_MASK) == true)
                        mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_MASK).takeIf { it != 0 && it != 1 } else null
                    positions = if (count <= 2) null else if (mask != null)
                        AndroidSpeakerLayout.fromMask(mask, count)
                    else if (format.sampleMimeType == MimeTypes.AUDIO_FLAC && count == format.channelCount)
                        flacPositions(count)
                    else null
                    super.onOutputFormatChanged(format, mediaFormat)
                } catch (error: IllegalArgumentException) {
                    throw createRendererException(error, format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                }
            }
        })
    }

    companion object {
        /** FLAC's specified interleaved channel assignment, when no decoder mask
         * is supplied. Seven channels include BC, which this renderer cannot map.
         * Other codecs must supply positions rather than inheriting this rule. */
        fun flacPositions(channels: Int): List<SpeakerPosition>? = when (channels) {
            3 -> listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC)
            4 -> listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.BL, SpeakerPosition.BR)
            5 -> listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.BL, SpeakerPosition.BR)
            6 -> listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
            8 -> listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR, SpeakerPosition.SL, SpeakerPosition.SR)
            else -> null
        }
    }
}
