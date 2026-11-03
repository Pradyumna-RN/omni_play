package labs.rtensor.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeBindings {
    /** Why the private library could not be used, kept for diagnostics rather than
     * collapsed into a boolean. Null when it loaded with the expected ABI. */
    val loadError: Throwable? = runCatching {
        System.loadLibrary("rtensor_downmix")
        val abi = abiVersion()
        check(abi == ABI) { "Unsupported private DSP ABI $abi (expected $ABI)" }
    }.exceptionOrNull()
    val available: Boolean get() = loadError == null
    const val ABI = 2
    external fun abiVersion(): Int
    external fun create(rate: Int, positions: IntArray, layout: Int, maxFrames: Int, preampDb: Float): Long
    external fun process(handle: Long, input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, frames: Int): Int
    external fun reset(handle: Long): Int
    external fun setCompareMode(handle: Long, original: Boolean): Int
    external fun latency(handle: Long): Int
    external fun destroy(handle: Long)
}

/** One owner per stream. Lifecycle calls cannot race JNI processing or destruction. */
class NativeDownmixEngine(override val format: DownmixFormat) : DownmixEngine {
    private var handle: Long
    override val latencyFrames: Int
    override val drainFrames: Int get() = latencyFrames + format.sampleRate / 20
    init {
        NativeBindings.loadError?.let { throw DownmixException(DownmixStage.DSP_LIBRARY, unavailableReason ?: "The private DSP is unavailable", it) }
        handle = NativeBindings.create(format.sampleRate, format.positions.map { it.id }.toIntArray(), format.layout.id, format.maxBlockFrames, format.preampDb)
        if (handle == 0L) throw DownmixException(DownmixStage.FORMAT,
            "The private DSP could not configure ${format.inputChannels} channels at ${format.sampleRate} Hz (${format.positions.joinToString(" ")}, ${format.layout})")
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
        if (status != 0) throw DownmixException(DownmixStage.DSP, "Private DSP rejected the audio block (status $status)")
        input.position(input.position() + inputBytes)
        output.position(output.position() + outputBytes)
    }

    @Synchronized override fun reset() {
        check(handle != 0L) { "DSP stream is closed" }
        check(NativeBindings.reset(handle) == 0)
    }

    @Synchronized override fun setCompareMode(original: Boolean) {
        check(handle != 0L) { "DSP stream is closed" }
        check(NativeBindings.setCompareMode(handle, original) == 0) { "The DSP comparison mode could not change" }
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
        /** Null when available; otherwise whether it was left out of the build or failed to load. */
        val unavailableReason: String? get() = NativeBindings.loadError?.let { error ->
            if (!wasPackaged) "The private Android DSP library is not included in this build"
            else "The private Android DSP library failed to load: ${error.javaClass.simpleName}: ${error.message}"
        }
        val wasPackaged: Boolean get() = BuildConfig.PRIVATE_DSP_INCLUDED
    }
}
