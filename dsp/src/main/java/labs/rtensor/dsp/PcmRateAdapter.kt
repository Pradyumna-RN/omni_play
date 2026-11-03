package labs.rtensor.dsp

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Streaming 160/147 conversion before the fixed-rate private core: 44.1 -> 48,
 * 88.2 -> 96 and 176.4 -> 192 kHz, for interleaved float PCM of any channel count.
 * A normalized 128-tap Hann-windowed sinc preserves gain and bandlimits
 * interpolation images. Each channel is filtered independently with the same
 * phase, so channel identity and inter-channel timing are unchanged.
 * Phase/history survive decoder block boundaries. */
internal class PcmRateAdapter(val channels: Int = 2) {
    init { require(channels in 1..16) { "Unsupported channel count $channels" } }
    private val coefficients = Array(UP) { phase ->
        val fraction = phase.toDouble() / UP
        FloatArray(TAPS) { tap ->
            val distance = tap - HALF + 1 - fraction
            val sinc = if (distance == 0.0) 1.0 else sin(PI * distance) / (PI * distance)
            val window = if (kotlin.math.abs(distance) >= HALF) 0.0 else .5 * (1.0 + cos(PI * distance / HALF))
            (sinc * window).toFloat()
        }.also { weights ->
            val sum = weights.sum()
            for (i in weights.indices) weights[i] /= sum
        }
    }
    // Every frame is stored twice, RING frames apart, so any 128-tap window is one
    // contiguous run. The ring starts zeroed, which is the silence before the stream.
    private val history = FloatArray(RING * 2 * channels)
    private val sums = DoubleArray(channels)
    private var inputIndex = -1L
    private var nextNumerator = -HALF.toLong() * UP
    val latencyOutputFrames: Int = (HALF * UP + DOWN - 1) / DOWN
    fun inputFramesPerBlock(maxOutputFrames: Int): Int = maxOutputFrames * DOWN / UP
    fun maxOutputFrames(inputFrames: Int): Int = (inputFrames * UP + DOWN - 1) / DOWN

    fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): Int {
        val frameBytes = channels * 4
        require(frames >= 0 && input.remaining() >= frames * frameBytes)
        require(output.remaining() >= maxOutputFrames(frames) * frameBytes)
        // Reject malformed PCM before it can enter the interpolation history.
        for (i in 0 until frames * channels) require(input.getFloat(input.position() + i * 4).isFinite())
        var written = 0
        repeat(frames) {
            inputIndex++
            val slot = (inputIndex.toInt() and (RING - 1)) * channels
            for (c in 0 until channels) {
                val sample = input.float
                history[slot + c] = sample; history[slot + RING * channels + c] = sample
            }
            while (Math.floorDiv(nextNumerator, UP.toLong()) + HALF <= inputIndex) {
                val base = Math.floorDiv(nextNumerator, UP.toLong())
                val weights = coefficients[Math.floorMod(nextNumerator, UP.toLong()).toInt()]
                sums.fill(0.0)
                var offset = ((base - HALF + 1).toInt() and (RING - 1)) * channels
                for (tap in 0 until TAPS) {
                    val weight = weights[tap]
                    for (c in 0 until channels) sums[c] += history[offset + c] * weight
                    offset += channels
                }
                for (c in 0 until channels) output.putFloat(sums[c].toFloat())
                nextNumerator += DOWN; written++
            }
        }
        return written
    }

    fun reset() { history.fill(0f); inputIndex = -1L; nextNumerator = -HALF.toLong() * UP }
    companion object {
        private const val UP = 160
        private const val DOWN = 147
        private const val HALF = 64
        private const val TAPS = HALF * 2
        private const val RING = 256
        /** Source rates this adapter lifts onto a rate the private core accepts. */
        val CONVERTIBLE_RATES: Map<Int, Int> = mapOf(44100 to 48000, 88200 to 96000, 176400 to 192000)
    }
}
