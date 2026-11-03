package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class StereoRateAdapterTest {
    private fun buffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    private fun convert(data: FloatArray, blocks: IntArray = intArrayOf(470)): FloatArray {
        val adapter = StereoRateAdapter(); val result = ArrayList<Float>(); var offset = 0; var block = 0
        while (offset < data.size / 2) {
            val frames = minOf(blocks[block++ % blocks.size], data.size / 2 - offset)
            val source = buffer(frames * 8).apply { repeat(frames * 2) { putFloat(data[offset * 2 + it]) }; flip() }
            val output = buffer(((frames * 160 + 146) / 147) * 8)
            val count = adapter.process(source, output, frames)
            assertEquals(count * 8, output.position()); assertFalse(source.hasRemaining())
            output.flip(); while (output.hasRemaining()) result += output.float
            offset += frames
        }
        return result.toFloatArray()
    }
    @Test fun stereoGainAndChannelIdentityArePreservedAtTheCorrectDuration() {
        val frames = 44100
        val actual = convert(FloatArray(frames * 2) { if (it % 2 == 0) .25f else -.125f })
        assertEquals(48000 * 2, actual.size)
        for (i in 300 until actual.size step 2) {
            assertEquals(.25f, actual[i], .000001f); assertEquals(-.125f, actual[i + 1], .000001f)
        }
    }
    @Test fun irregularDecoderBlocksProduceExactlyTheSameSamples() {
        val input = FloatArray(4096 * 2) { (sin(it * .13) * .2).toFloat() }
        assertArrayEquals(convert(input), convert(input, intArrayOf(1, 7, 83, 470, 3)), 0f)
    }
    @Test fun audioBandTonesMatchAnIndependentAnalyticalReference() {
        for (frequency in listOf(1000.0, 10000.0, 19000.0, 20000.0)) {
            val source = FloatArray(4410 * 2) { i -> (sin(2 * PI * frequency * (i / 2) / 44100) * .2).toFloat() }
            val actual = convert(source)
            var squaredError = 0.0; var samples = 0
            for (i in 300 until actual.size / 2) {
                val sourceTime = i * 147.0 / 160.0 - 64.0
                val expected = sin(2 * PI * frequency * sourceTime / 44100) * .2
                squaredError += (actual[i * 2] - expected) * (actual[i * 2] - expected); samples++
            }
            assertTrue("$frequency Hz RMS error ${sqrt(squaredError / samples)}", sqrt(squaredError / samples) < .0003)
        }
    }
    @Test fun resetClearsOldAudioAndMalformedInputDoesNotPoisonTheHistory() {
        val adapter = StereoRateAdapter(); val input = buffer(3760).apply { repeat(940) { putFloat(.2f) }; flip() }
        val first = buffer(4096); adapter.process(input, first, 470)
        adapter.reset(); input.rewind(); val repeated = buffer(4096); adapter.process(input, repeated, 470)
        first.flip(); repeated.flip(); assertEquals(first, repeated)
        adapter.reset(); input.rewind(); input.putFloat(12, Float.NaN)
        assertThrows(IllegalArgumentException::class.java) { adapter.process(input, buffer(4096), 470) }
        input.putFloat(12, .2f); val valid = buffer(4096); adapter.process(input, valid, 470); valid.flip()
        assertEquals(first, valid)
    }
}
