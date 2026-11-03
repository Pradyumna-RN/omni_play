package labs.rtensor.dsp

import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Intercepts decoded PCM before Android output. Unlike setAudioProcessors alone,
 * this wrapper also sees the float PCM path. Stereo bypass stays with the delegate.
 * A provider-owned/protected player cannot be processed through this interface.
 */
@UnstableApi
class DownmixAudioSink(
    private val delegate: AudioSink,
    private val decodedPositions: (Int) -> List<SpeakerPosition>,
    private val engineFactory: (DownmixFormat) -> DownmixEngine = { NativeDownmixEngine(it) },
    private val onReport: (DownmixReport?) -> Unit = {},
) : ForwardingAudioSink(delegate) {
    private var engine: DownmixEngine? = null
    private var inputFormat: Format? = null
    private var inputChannels = 0
    private var frameBytes = 0
    private var floatInput: ByteBuffer = EMPTY
    private var floatOutput: ByteBuffer = EMPTY
    private var pending: ByteBuffer? = null
    private var pendingPts = 0L
    private var inputBuffer: ByteBuffer? = null
    private var inputFrames = 0L
    private var nextPts = 0L
    private var firstPts: Long? = null
    private var tailRemaining = 0
    private var tailStarted = false
    private var wroteAudio = false
    private var requestedAttributes = AudioAttributes.DEFAULT
    private var inputEndPts = 0L

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int {
        // Decode compressed input rather than bypassing the custom PCM stage.
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.channelCount <= 2) return delegate.getFormatSupport(format)
        val encodingSupported = runCatching { PcmFloatConverter.bytesPerSample(format.pcmEncoding) }.isSuccess
        return if (format.channelCount in 3..16 && format.sampleRate in DownmixFormat.SUPPORTED_SAMPLE_RATES && encodingSupported)
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    }
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        check(pending == null) { "The previous decoded audio buffer has not drained" }
        engine?.close(); engine = null
        clearStream()
        inputFormat = format
        if (format.channelCount <= 2) {
            delegate.setAudioAttributes(requestedAttributes)
            delegate.configure(format, specifiedBufferSize, outputChannels)
            onReport(null)
            return
        }
        var created: DownmixEngine? = null
        try {
            require(format.sampleMimeType == MimeTypes.AUDIO_RAW) { "Downmix requires decoded PCM" }
            require(outputChannels == null) { "A decoder channel-remapping array needs explicit layout reconciliation" }
            val positions = decodedPositions(format.channelCount)
            require(positions.size == format.channelCount)
            val specification = DownmixFormat(format.sampleRate, positions)
            frameBytes = PcmFloatConverter.bytesPerSample(format.pcmEncoding) * format.channelCount
            inputChannels = format.channelCount
            created = engineFactory(specification)
            floatInput = ByteBuffer.allocateDirect(specification.maxBlockFrames * inputChannels * 4).order(ByteOrder.nativeOrder())
            floatOutput = ByteBuffer.allocateDirect(specification.maxBlockFrames * 8).order(ByteOrder.nativeOrder())
            val stereo = format.buildUpon().setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
            require(delegate.getFormatSupport(stereo) != AudioSink.SINK_FORMAT_UNSUPPORTED) { "The output cannot accept float PCM" }
            // A buffer size calculated for multichannel bytes cannot be reused for stereo.
            delegate.setAudioAttributes(renderedAttributes())
            delegate.configure(stereo, 0, null)
            engine = created
            onReport(DownmixReport(format.sampleRate, positions))
        } catch (error: Exception) {
            created?.close()
            engine = null
            onReport(null)
            throw AudioSink.ConfigurationException(error, format)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val active = engine ?: return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val format = checkNotNull(inputFormat)
        try {
            check(!tailStarted) { "Cannot append PCM after end of stream without flushing" }
            if (!drainPending()) return false
            if (inputBuffer !== buffer) { inputBuffer = buffer; inputFrames = 0 }
            require(buffer.remaining() % frameBytes == 0) { "The decoder returned a partial PCM frame" }
            require(buffer.order() == ByteOrder.nativeOrder())
            if (firstPts == null) firstPts = presentationTimeUs
            while (buffer.hasRemaining()) {
                val frames = minOf(buffer.remaining() / frameBytes, active.format.maxBlockFrames)
                floatInput.clear()
                PcmFloatConverter.convert(buffer, floatInput, frames * inputChannels, format.pcmEncoding)
                floatInput.flip(); floatOutput.clear()
                active.process(floatInput, floatOutput, frames)
                floatOutput.flip()
                pending = floatOutput
                pendingPts = presentationTimeUs + inputFrames * 1_000_000L / format.sampleRate
                inputFrames += frames
                nextPts = presentationTimeUs + inputFrames * 1_000_000L / format.sampleRate
                inputEndPts = nextPts
                wroteAudio = true
                if (!drainPending()) return false
            }
            inputBuffer = null
            return true
        } catch (error: Exception) {
            if (error is AudioSink.WriteException) throw error
            throw AudioSink.WriteException(-2, format, false).apply { initCause(error) }
        }
    }

    private fun drainPending(): Boolean {
        val buffer = pending ?: return true
        if (!delegate.handleBuffer(buffer, pendingPts, 1)) return false
        check(!buffer.hasRemaining()) { "Output accepted a buffer without consuming it" }
        pending = null
        return true
    }

    override fun playToEndOfStream() {
        val active = engine ?: return delegate.playToEndOfStream()
        if (!tailStarted) { tailStarted = true; tailRemaining = if (wroteAudio) active.drainFrames else 0 }
        if (!drainPending()) return
        while (tailRemaining > 0) {
            val frames = minOf(tailRemaining, active.format.maxBlockFrames)
            floatInput.clear()
            repeat(frames * inputChannels) { floatInput.putFloat(0f) }
            floatInput.flip(); floatOutput.clear()
            active.process(floatInput, floatOutput, frames); floatOutput.flip()
            pending = floatOutput; pendingPts = nextPts
            nextPts += frames * 1_000_000L / active.format.sampleRate
            tailRemaining -= frames
            if (!drainPending()) return
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean = if (engine == null) delegate.isEnded
        else tailStarted && tailRemaining == 0 && pending == null && delegate.isEnded
    override fun hasPendingData(): Boolean = pending != null || tailRemaining > 0 || delegate.hasPendingData()
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = delegate.getCurrentPositionUs(sourceEnded)
        val active = engine ?: return position
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        val adjusted = (position - active.latencyFrames * 1_000_000L / active.format.sampleRate).coerceAtLeast(firstPts ?: position)
        return if (tailStarted && wroteAudio) adjusted.coerceAtMost(inputEndPts) else adjusted
    }
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        requestedAttributes = audioAttributes
        delegate.setAudioAttributes(if (engine != null) renderedAttributes() else audioAttributes)
    }
    private fun renderedAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(requestedAttributes.usage).setContentType(requestedAttributes.contentType)
        .setFlags(requestedAttributes.flags).setAllowedCapturePolicy(requestedAttributes.allowedCapturePolicy)
        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER).build()
    override fun handleDiscontinuity() {
        clearStream(); engine?.reset(); delegate.handleDiscontinuity()
    }
    override fun flush() {
        clearStream(); engine?.reset(); delegate.flush()
    }
    override fun reset() {
        clearStream(); engine?.close(); engine = null; inputFormat = null
        floatInput = EMPTY; floatOutput = EMPTY
        delegate.reset(); onReport(null)
    }
    override fun release() {
        clearStream(); engine?.close(); engine = null
        delegate.release(); onReport(null)
    }
    private fun clearStream() {
        pending = null; inputBuffer = null; inputFrames = 0; nextPts = 0
        firstPts = null; tailRemaining = 0; tailStarted = false; wroteAudio = false
        inputEndPts = 0
    }
    private companion object { val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
}
