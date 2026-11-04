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
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import labs.rtensor.decoder.openjoc.OpenJocAudioDecoder
import labs.rtensor.decoder.openjoc.OpenJocAudioRenderer
import labs.rtensor.decoder.openjoc.OpenJocLibrary

/** Uses the software E-AC-3 bed decoder when packaged, otherwise the device decoder,
 * then the app-owned PCM render. Multichannel PCM whose speaker positions are
 * unknown plays unprocessed through Android rather than failing. No Apple Music SDK
 * capture, Atmos object decoder or system loopback is implemented. Stereo
 * bypasses DMR without upmixing or rate conversion. */
@UnstableApi
open class DownmixRenderersFactory(
    context: Context,
    private val processingEnabled: () -> Boolean = { true },
    /** Before [onReport], so the trailing report lambda keeps its meaning. */
    private val listeningMode: () -> ListeningMode = { if (processingEnabled()) ListeningMode.PROCESSED else ListeningMode.ORIGINAL },
    private val objectDecodingEnabled: () -> Boolean = { false },
    private val onReport: (DownmixReport?) -> Unit = {},
) : DefaultRenderersFactory(context) {
    private var positions: List<SpeakerPosition>? = null
    private var layoutError: Throwable? = null
    private var objectDecoded = false
    init { setEnableAudioFloatOutput(true) }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return DownmixAudioSink(delegate, decodedPositions = { channels ->
            val decoded = positions ?: throw (layoutError as? DownmixException ?: DownmixException(DownmixStage.LAYOUT,
                "The decoder did not expose a supported speaker layout for $channels channels; refusing an unverified downmix", layoutError))
            if (decoded.size != channels) throw DownmixException(DownmixStage.LAYOUT,
                "Decoder speaker layout changed from ${decoded.size} to $channels channels without a format notification")
            decoded
        }, onReport = { report -> onReport(report?.copy(objectDecoded = objectDecoded)) }, processingEnabled = processingEnabled, listeningMode = listeningMode)
    }

    override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
        if (OpenJocLibrary.isAvailable()) {
            out.add(object : OpenJocAudioRenderer(eventHandler, eventListener, audioSink, java.util.function.BooleanSupplier { objectDecodingEnabled() }) {
                override fun getOutputFormat(decoder: OpenJocAudioDecoder): Format {
                    val layout = runCatching { FfmpegSpeakerLayout.positions(decoder.channelLayoutMask, decoder.channelCount) }
                    positions = layout.getOrNull(); layoutError = layout.exceptionOrNull(); objectDecoded = layout.isSuccess
                    return super.getOutputFormat(decoder)
                }
            })
        }
        if (FfmpegLibrary.isAvailable()) {
            var multichannelRequired = false
            val guarded = object : ForwardingAudioSink(audioSink) {
                override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                    if (multichannelRequired && format.channelCount <= 2) throw AudioSink.ConfigurationException(
                        IllegalArgumentException("The software decoder returned stereo for a multichannel source"), format)
                    super.configure(format, specifiedBufferSize, outputChannels)
                }
            }
            out.add(object : FfmpegAudioRenderer(eventHandler, eventListener, guarded) {
                override fun getName() = "MusicFfmpegBedAudioRenderer"
                override fun shouldOutputFloat(format: Format): Boolean =
                    format.sampleMimeType == MimeTypes.AUDIO_ALAC || super.shouldOutputFloat(format)
                override fun supportsFormatInternal(format: Format): Int = when {
                    format.sampleMimeType !in setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_ALAC) -> androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE
                    format.drmInitData != null || format.cryptoType != androidx.media3.common.C.CRYPTO_TYPE_NONE -> androidx.media3.common.C.FORMAT_UNSUPPORTED_DRM
                    else -> super.supportsFormatInternal(format)
                }
                override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegAudioDecoder {
                    multichannelRequired = format.channelCount > 2 || format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC
                    return super.createDecoder(format, cryptoConfig)
                }
                override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format {
                    objectDecoded = false
                    val layout = runCatching { FfmpegSpeakerLayout.positions(decoder.channelLayoutMask, decoder.channelCount) }
                    positions = layout.getOrNull()
                    layoutError = layout.exceptionOrNull()
                    return super.getOutputFormat(decoder)
                }
            })
        }
        out.add(object : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, audioSink) {
            override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
                objectDecoded = false
                try {
                    val count = if (mediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_COUNT) == true)
                        mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else format.channelCount
                    val multichannelRequired = format.channelCount > 2 || format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC
                    require(!multichannelRequired || count > 2) { "The decoder folded the multichannel recording to stereo before DSP" }
                    val mask = if (mediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_MASK) == true)
                        mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_MASK).takeIf { it != 0 && it != 1 } else null
                    // A missing or unmappable layout is not fatal: the sink then plays the
                    // original through Android and reports why the DSP stayed out.
                    val layout = runCatching {
                        if (count <= 2) null else if (mask != null)
                            AndroidSpeakerLayout.fromMask(mask, count)
                        else if (format.sampleMimeType == MimeTypes.AUDIO_FLAC && count == format.channelCount)
                            flacPositions(count)
                        else null
                    }
                    positions = layout.getOrNull()
                    layoutError = layout.exceptionOrNull()?.let { DownmixException(DownmixStage.LAYOUT,
                        "${it.message} (Android channel mask 0x${Integer.toHexString(mask ?: 0)}, $count channels)", it) }
                        ?: if (count > 2 && positions == null) DownmixException(DownmixStage.LAYOUT,
                            "The ${format.sampleMimeType ?: "audio"} decoder did not report which speaker each of its $count channels is; playing the original") else null
                    super.onOutputFormatChanged(format, mediaFormat)
                } catch (error: IllegalArgumentException) {
                    throw createRendererException(error, format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                }
            }
        })
    }

    companion object {
        val objectDecoderAvailable: Boolean get() = OpenJocLibrary.isAvailable()
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
