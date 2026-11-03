package labs.rtensor.dsp

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Streaming 44.1 -> 48 kHz conversion before the fixed-rate private core.
 * A normalized 128-tap Hann-windowed sinc preserves stereo gain and bandlimits
 * interpolation images. Phase/history survive decoder block boundaries. */
internal class StereoRateAdapter {
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
    private val history = FloatArray(RING * 2)
    private var inputIndex = -1L
    private var nextNumerator = -HALF.toLong() * UP
    val latencyOutputFrames: Int = (HALF * UP + DOWN - 1) / DOWN
    fun inputFramesPerBlock(maxOutputFrames: Int): Int = maxOutputFrames * DOWN / UP

    fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): Int {
        require(frames >= 0 && input.remaining() >= frames * 8)
        require(output.remaining() >= ((frames * UP + DOWN - 1) / DOWN) * 8)
        // Reject malformed PCM before it can enter the interpolation history.
        for (i in 0 until frames * 2) require(input.getFloat(input.position() + i * 4).isFinite())
        var written = 0
        repeat(frames) {
            inputIndex++
            val slot = (inputIndex.toInt() and (RING - 1)) * 2
            history[slot] = input.float; history[slot + 1] = input.float
            while (Math.floorDiv(nextNumerator, UP.toLong()) + HALF <= inputIndex) {
                val base = Math.floorDiv(nextNumerator, UP.toLong())
                val weights = coefficients[Math.floorMod(nextNumerator, UP.toLong()).toInt()]
                var left = 0.0; var right = 0.0
                for (tap in 0 until TAPS) {
                    val index = base + tap - HALF + 1
                    if (index >= 0) {
                        val offset = (index.toInt() and (RING - 1)) * 2
                        left += history[offset] * weights[tap]
                        right += history[offset + 1] * weights[tap]
                    }
                }
                output.putFloat(left.toFloat()); output.putFloat(right.toFloat())
                nextNumerator += DOWN; written++
            }
        }
        return written
    }

    fun reset() { history.fill(0f); inputIndex = -1L; nextNumerator = -HALF.toLong() * UP }
    private companion object {
        const val UP = 160
        const val DOWN = 147
        const val HALF = 64
        const val TAPS = HALF * 2
        const val RING = 256
    }
}
