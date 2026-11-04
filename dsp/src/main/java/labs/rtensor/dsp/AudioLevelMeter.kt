package labs.rtensor.dsp

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Full-scale PCM measurements, before Android/device volume. No signal is modified. */
data class ChannelLevel(val position: SpeakerPosition?, val rmsDb: Float, val peakDb: Float)
data class AudioLevelFrame(
    val input: List<ChannelLevel>, val output: List<ChannelLevel>,
    val sourceRate: Int, val outputRate: Int, val measuredAtNs: Long,
)

/** One audio-thread owner. Disabled meters do no PCM scanning or publication.
 * Windows are fixed at 50 ms; amplitudes never normalize to the loudest channel. */
class AudioLevelMeter(
    private val enabled: () -> Boolean,
    private val publish: (AudioLevelFrame?) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private var sourceRate = 0
    private var outputRate = 0
    private var sourcePositions = emptyList<SpeakerPosition?>()
    private var outputPositions = emptyList<SpeakerPosition?>()
    private var source = Accumulator(0)
    private var output = Accumulator(0)
    private var active = false

    fun configure(rate: Int, positions: List<SpeakerPosition?>, renderRate: Int, rendered: List<SpeakerPosition?>) {
        sourceRate = rate; outputRate = renderRate
        sourcePositions = positions.toList(); outputPositions = rendered.toList()
        source = Accumulator(positions.size); output = Accumulator(rendered.size)
        reset()
    }
    fun reset() { source.clear(); output.clear(); active = false; publish(null) }
    private fun observing(): Boolean {
        if (!enabled()) {
            if (active) reset()
            return false
        }
        active = true
        return sourceRate > 0 && outputRate > 0
    }
    fun input(buffer: ByteBuffer, start: Int, end: Int, encoding: Int) {
        if (observing()) source.add(buffer, start, end, encoding)
    }
    fun output(buffer: ByteBuffer, start: Int, end: Int, encoding: Int) {
        if (!observing()) return
        output.add(buffer, start, end, encoding)
        emitIfReady()
    }
    /** Bypass bytes are sampled once, after the delegate consumes them. */
    fun bypass(buffer: ByteBuffer, start: Int, end: Int, encoding: Int) {
        if (!observing()) return
        source.add(buffer, start, end, encoding)
        // The sink might apply a channel map: only reuse measurements for identical layouts.
        if (sourcePositions == outputPositions) output.copyFrom(source)
        else return
        emitIfReady()
    }
    private fun emitIfReady() {
        if (output.frames < (outputRate / 20).coerceAtLeast(1) || source.frames == 0L) return
        publish(AudioLevelFrame(source.levels(sourcePositions), output.levels(outputPositions), sourceRate, outputRate, clock()))
        source.clear(); output.clear()
    }
    private class Accumulator(private val channels: Int) {
        val squares = DoubleArray(channels)
        val peaks = DoubleArray(channels)
        var frames = 0L
        fun clear() { squares.fill(0.0); peaks.fill(0.0); frames = 0 }
        fun copyFrom(other: Accumulator) { other.squares.copyInto(squares); other.peaks.copyInto(peaks); frames = other.frames }
        fun add(buffer: ByteBuffer, start: Int, end: Int, encoding: Int) {
            if (channels == 0 || start >= end) return
            val bytes = when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            if (start < 0 || end > buffer.limit() || (end - start) % (bytes * channels) != 0) return
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            var at = start
            repeat((end - start) / (bytes * channels)) {
                for (ch in 0 until channels) {
                    val x = when (encoding) {
                        C.ENCODING_PCM_8BIT -> ((view.get(at).toInt() and 255) - 128) / 128.0
                        C.ENCODING_PCM_16BIT -> view.getShort(at) / 32768.0
                        C.ENCODING_PCM_24BIT -> ((view.get(at).toInt() and 255) or
                            ((view.get(at + 1).toInt() and 255) shl 8) or (view.get(at + 2).toInt() shl 16)) / 8388608.0
                        C.ENCODING_PCM_32BIT -> view.getInt(at) / 2147483648.0
                        else -> view.getFloat(at).toDouble()
                    }.takeIf { it.isFinite() } ?: 0.0
                    squares[ch] += x * x; peaks[ch] = maxOf(peaks[ch], abs(x)); at += bytes
                }
                frames++
            }
        }
        fun levels(positions: List<SpeakerPosition?>): List<ChannelLevel> = positions.mapIndexed { i, p ->
            ChannelLevel(p, db(sqrt(squares[i] / frames.coerceAtLeast(1))), db(peaks[i]))
        }
        private fun db(amplitude: Double): Float = if (amplitude <= 0.000001) -120f else (20 * log10(amplitude)).toFloat()
    }
}
