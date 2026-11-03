package labs.rtensor.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeBindings {
    val available: Boolean = runCatching {
        System.loadLibrary("rtensor_downmix")
        check(abiVersion() == 1) { "Unsupported private DSP ABI" }
    }.isSuccess
    external fun abiVersion(): Int
    external fun create(rate: Int, positions: IntArray, layout: Int, maxFrames: Int, preampDb: Float): Long
    external fun process(handle: Long, input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, frames: Int): Int
    external fun reset(handle: Long): Int
    external fun latency(handle: Long): Int
    external fun destroy(handle: Long)
}

/** One owner per stream. Lifecycle calls cannot race JNI processing or destruction. */
class NativeDownmixEngine(override val format: DownmixFormat) : DownmixEngine {
    private var handle: Long
    override val latencyFrames: Int
    override val drainFrames: Int get() = latencyFrames + format.sampleRate / 20
    init {
        check(isAvailable) { "The private Android DSP library is not included in this build" }
        handle = NativeBindings.create(format.sampleRate, format.positions.map { it.id }.toIntArray(), format.layout.id, format.maxBlockFrames, format.preampDb)
        check(handle != 0L) { "The private DSP could not configure this format" }
        latencyFrames = NativeBindings.latency(handle)
    }

    @Synchronized override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int) {
        check(handle != 0L) { "DSP stream is closed" }
        require(frames in 0..format.maxBlockFrames)
        require(input.isDirect && output.isDirect && !output.isReadOnly) { "DSP requires writable direct output and direct input" }
        require(input.order() == ByteOrder.nativeOrder() && output.order() == ByteOrder.nativeOrder())
        require(input.position() % 4 == 0 && output.position() % 4 == 0)
        val inputBytes = Math.multiplyExact(frames, format.inputChannels * 4)
        val outputBytes = Math.multiplyExact(frames, 8)
        require(input.remaining() >= inputBytes && output.remaining() >= outputBytes) { "DSP buffer is too small" }
        val status = NativeBindings.process(handle, input, input.position(), output, output.position(), frames)
        check(status == 0) { "Private DSP rejected the audio block (status $status)" }
        input.position(input.position() + inputBytes)
        output.position(output.position() + outputBytes)
    }

    @Synchronized override fun reset() {
        check(handle != 0L) { "DSP stream is closed" }
        check(NativeBindings.reset(handle) == 0)
    }

    @Synchronized override fun close() {
        if (handle != 0L) {
            val previous = handle
            handle = 0
            NativeBindings.destroy(previous)
        }
    }

    companion object {
        val isAvailable: Boolean get() = NativeBindings.available
        val wasPackaged: Boolean get() = BuildConfig.PRIVATE_DSP_INCLUDED
    }
}
