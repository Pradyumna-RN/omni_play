package labs.rtensor.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeBindings {
    /** ABI 3 adds the speaker field and the 9.1.6 upmix switch. */
    var loadedAbi = 0 // declared before loadError so its initialiser runs first
        private set
    /** Why the private library could not be used, kept for diagnostics rather than
     * collapsed into a boolean. Null when it loaded with the expected ABI. */
    val loadError: Throwable? = runCatching {
        System.loadLibrary("rtensor_downmix")
        val abi = abiVersion()
        check(abi in MIN_ABI..MAX_ABI) { "Unsupported private DSP ABI $abi (expected $MIN_ABI to $MAX_ABI)" }
        loadedAbi = abi
    }.exceptionOrNull()
    val available: Boolean get() = loadError == null
    const val MIN_ABI = 2
    const val MAX_ABI = 3
    val hasField: Boolean get() = available && loadedAbi >= 3
    external fun abiVersion(): Int
    external fun create(rate: Int, positions: IntArray, layout: Int, maxFrames: Int, preampDb: Float): Long
    external fun process(handle: Long, input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, frames: Int): Int
    external fun reset(handle: Long): Int
    external fun setCompareMode(handle: Long, original: Boolean): Int
    external fun latency(handle: Long): Int
    external fun destroy(handle: Long)
    external fun processField(handle: Long, input: ByteBuffer, inputOffset: Int, field: ByteBuffer, fieldOffset: Int, frames: Int): Int
    external fun setUpmix916(handle: Long, enabled: Boolean): Int
}

/** One owner per stream. Lifecycle calls cannot race JNI processing or destruction. */
class NativeDownmixEngine(override val format: DownmixFormat) : DownmixEngine {
    private var handle: Long
    override val latencyFrames: Int
    override val upmix916: Boolean
    override val drainFrames: Int get() = latencyFrames + format.sampleRate / 20
    init {
        NativeBindings.loadError?.let { throw DownmixException(DownmixStage.DSP_LIBRARY, unavailableReason ?: "The private DSP is unavailable", it) }
        handle = NativeBindings.create(format.sampleRate, format.positions.map { it.id }.toIntArray(), format.layout.id, format.maxBlockFrames, format.preampDb)
        if (handle == 0L) throw DownmixException(DownmixStage.FORMAT,
            "The private DSP could not configure ${format.inputChannels} channels at ${format.sampleRate} Hz (${format.positions.joinToString(" ")}, ${format.layout})")
        // Multichannel renders through the 9.1.6 monitor so the speakers the source
        // lacks are generated, as the desktop renderer's "9.1.6 monitor" option does.
        // Explicit height inputs are already a spatial speaker scene. Generating
        // heights again would overwrite/add ambience to the decoded scene.
        upmix916 = NativeBindings.hasField && format.inputChannels > 2 && format.positions.none { it.id >= SpeakerPosition.TFL.id } &&
            NativeBindings.setUpmix916(handle, true) == 0
        latencyFrames = NativeBindings.latency(handle)
    }
    override val providesField: Boolean get() = NativeBindings.hasField

    @Synchronized override fun processField(input: ByteBuffer, field: ByteBuffer, frames: Int) {
        check(handle != 0L) { "DSP stream is closed" }
        check(providesField) { "This private DSP build does not expose its speaker field" }
        require(frames in 0..format.maxBlockFrames)
        require(input.isDirect && field.isDirect && !field.isReadOnly) { "DSP requires writable direct output and direct input" }
        require(input.order() == ByteOrder.nativeOrder() && field.order() == ByteOrder.nativeOrder())
        require(input.position() % 4 == 0 && field.position() % 4 == 0)
        val inputBytes = Math.multiplyExact(frames, format.inputChannels * 4)
        val fieldBytes = Math.multiplyExact(frames, FIELD_CHANNELS * 4)
        require(input.remaining() >= inputBytes && field.remaining() >= fieldBytes) { "DSP buffer is too small" }
        val status = NativeBindings.processField(handle, input, input.position(), field, field.position(), frames)
        if (status != 0) throw DownmixException(DownmixStage.DSP, "Private DSP rejected the audio block (status $status)")
        input.position(input.position() + inputBytes)
        field.position(field.position() + fieldBytes)
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
        const val FIELD_CHANNELS = 16
        val isAvailable: Boolean get() = NativeBindings.available
        /** Null when available; otherwise whether it was left out of the build or failed to load. */
        val unavailableReason: String? get() = NativeBindings.loadError?.let { error ->
            if (!wasPackaged) "The private Android DSP library is not included in this build"
            else "The private Android DSP library failed to load: ${error.javaClass.simpleName}: ${error.message}"
        }
        val wasPackaged: Boolean get() = BuildConfig.PRIVATE_DSP_INCLUDED
    }
}
