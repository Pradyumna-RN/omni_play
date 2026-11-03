package labs.rtensor.dsp

import java.nio.ByteBuffer
import java.util.Collections

/** Stable speaker IDs are positions, not channel ordinals. */
enum class SpeakerPosition(val id: Int) {
    FL(0), FR(1), FC(2), LFE(3), BL(4), BR(5), SL(6), SR(7),
    BLC(8), BRC(9), TFL(10), TFR(11), TSL(12), TSR(13), TBL(14), TBR(15),
}

enum class RenderLayout(val id: Int) { WINDOWS_71(0), SHARUR_916(1) }

class DownmixFormat(
    val sampleRate: Int,
    positions: List<SpeakerPosition>,
    layout: RenderLayout? = null,
    val maxBlockFrames: Int = 512,
    val preampDb: Float = if (positions.size == 2) 0f else -14f,
) {
    val positions: List<SpeakerPosition> = Collections.unmodifiableList(ArrayList(positions))
    val layout: RenderLayout = layout ?: if (this.positions.any { it.id >= 8 }) RenderLayout.SHARUR_916 else RenderLayout.WINDOWS_71
    init {
        require(sampleRate in SUPPORTED_SAMPLE_RATES) { "The renderer supports 48000, 96000 and 192000 Hz; received $sampleRate Hz" }
        require(this.positions.size in 2..16 && this.positions.distinct().size == this.positions.size) { "Explicit, unique speaker positions are required" }
        require(this.positions.size != 2 || this.positions.toSet() == setOf(SpeakerPosition.FL, SpeakerPosition.FR)) { "Stereo processing requires the front left/right pair" }
        require(this.positions.size != 2 || this.layout == RenderLayout.WINDOWS_71) { "Stereo is a front-pair input, not an existing 9.1.6 field" }
        require(this.layout != RenderLayout.WINDOWS_71 || this.positions.all { it.id < 8 }) { "This speaker layout requires the 9.1.6 renderer" }
        require(maxBlockFrames in 1..8192)
        require(preampDb.isFinite() && preampDb in -60f..12f)
    }
    val inputChannels: Int get() = positions.size
    companion object { val SUPPORTED_SAMPLE_RATES = setOf(48000, 96000, 192000) }
}

interface DownmixEngine : AutoCloseable {
    val format: DownmixFormat
    val latencyFrames: Int
    /** Bounded end-of-stream padding for delayed/filter state. Not source audio. */
    val drainFrames: Int get() = latencyFrames
    /** Direct interleaved float32 buffers. Successful processing advances both positions. */
    fun process(input: ByteBuffer, output: ByteBuffer, frames: Int)
    /** Live desktop comparison: original keeps FL/FR, with the same delay/gain. */
    fun setCompareMode(original: Boolean)
    fun reset()
}

data class DownmixReport(
    val sampleRate: Int,
    val inputPositions: List<SpeakerPosition>,
    val outputChannels: Int = 2,
    val processing: String = "Downmix DSP",
    val processingEnabled: Boolean = true,
    val comparisonAvailable: Boolean = true,
    val unavailableReason: String? = null,
    val renderSampleRate: Int = sampleRate,
    val bitPerfect: Boolean = false,
    // PCM channel count cannot establish whether Atmos object metadata was rendered.
    val atmosVerified: Boolean = false,
)
