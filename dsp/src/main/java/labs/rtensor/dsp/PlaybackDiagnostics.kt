package labs.rtensor.dsp

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer

/** A user-facing explanation of where audio playback stopped. */
data class AudioFailure(val stage: DownmixStage, val message: String, val detail: String)

/** Classifies an audio failure from its cause chain. The chain is walked here, in
 * the process that owns the player: a PlaybackException crossing to a controller
 * keeps only its top-level cause, which loses the reason. Uses type checks rather
 * than class names so minified builds classify the same way. */
@UnstableApi
object PlaybackDiagnostics {
    private const val MAX_DEPTH = 8

    fun describe(error: Throwable?): AudioFailure? {
        val chain = chain(error)
        if (chain.isEmpty()) return null
        val stage = chain.firstNotNullOfOrNull { (it as? DownmixException)?.stage }
            ?: chain.firstNotNullOfOrNull(::classify) ?: return null
        val reason = (chain.firstOrNull { it is DownmixException } ?: chain.lastOrNull { !it.message.isNullOrBlank() } ?: chain.last())
        val message = "${stage.label}: ${reason.message?.takeIf { it.isNotBlank() } ?: reason.javaClass.simpleName}"
        val format = chain.firstNotNullOfOrNull { (it as? AudioSink.ConfigurationException)?.format ?: (it as? AudioSink.InitializationException)?.format }
        val detail = buildString {
            chain.forEachIndexed { index, item ->
                if (index > 0) append(" ← ")
                append(item.javaClass.simpleName.ifEmpty { item.javaClass.name })
                item.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(240)) }
            }
            if (format != null) append(" | format ${format.sampleMimeType} ${format.channelCount} ch ${format.sampleRate} Hz")
        }
        return AudioFailure(stage, message, detail)
    }

    /** One line describing what this build can actually run, for logs and bug reports. */
    fun environment(abis: Array<String>): String {
        val ffmpeg = runCatching { if (FfmpegLibrary.isAvailable()) "FFmpeg ${FfmpegLibrary.getVersion()}" else "FFmpeg decoder unavailable" }
            .getOrElse { "FFmpeg check failed: ${it.javaClass.simpleName}" }
        val dsp = NativeDownmixEngine.unavailableReason ?: "DSP ABI ${NativeBindings.ABI} loaded"
        return "ABIs=${abis.joinToString(",")}; $ffmpeg; $dsp; DSP packaged=${NativeDownmixEngine.wasPackaged}"
    }

    private fun classify(error: Throwable): DownmixStage? = when (error) {
        is UnsatisfiedLinkError -> DownmixStage.DECODER_LIBRARY
        is FfmpegDecoderException, is MediaCodecDecoderException, is MediaCodecRenderer.DecoderInitializationException, is DecoderException -> DownmixStage.DECODER
        is AudioSink.InitializationException, is AudioSink.WriteException, is AudioSink.UnexpectedDiscontinuityException -> DownmixStage.OUTPUT
        is AudioSink.ConfigurationException -> DownmixStage.FORMAT
        else -> null
    }

    private fun chain(error: Throwable?): List<Throwable> {
        val out = ArrayList<Throwable>()
        var current = error
        while (current != null && out.size < MAX_DEPTH && out.none { it === current }) { out.add(current); current = current.cause }
        return out
    }
}
