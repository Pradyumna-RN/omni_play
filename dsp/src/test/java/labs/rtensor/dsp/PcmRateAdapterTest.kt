package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PcmRateAdapterTest {
    private fun buffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    private fun convert(data: FloatArray, blocks: IntArray = intArrayOf(470)): FloatArray {
        val adapter = PcmRateAdapter(); val result = ArrayList<Float>(); var offset = 0; var block = 0
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
        val adapter = PcmRateAdapter(); val input = buffer(3760).apply { repeat(940) { putFloat(.2f) }; flip() }
        val first = buffer(4096); adapter.process(input, first, 470)
        adapter.reset(); input.rewind(); val repeated = buffer(4096); adapter.process(input, repeated, 470)
        first.flip(); repeated.flip(); assertEquals(first, repeated)
        adapter.reset(); input.rewind(); input.putFloat(12, Float.NaN)
        assertThrows(IllegalArgumentException::class.java) { adapter.process(input, buffer(4096), 470) }
        input.putFloat(12, .2f); val valid = buffer(4096); adapter.process(input, valid, 470); valid.flip()
        assertEquals(first, valid)
    }
    private fun convertChannels(data: FloatArray, channels: Int, adapter: PcmRateAdapter, block: Int = 470): FloatArray {
        val result = ArrayList<Float>(); var offset = 0; val total = data.size / channels
        while (offset < total) {
            val frames = minOf(block, total - offset)
            val source = buffer(frames * channels * 4).apply { repeat(frames * channels) { putFloat(data[offset * channels + it]) }; flip() }
            val output = buffer(adapter.maxOutputFrames(frames) * channels * 4)
            val count = adapter.process(source, output, frames)
            assertEquals(count * channels * 4, output.position()); assertFalse(source.hasRemaining())
            output.flip(); while (output.hasRemaining()) result += output.float
            offset += frames
        }
        return result.toFloatArray()
    }
    @Test fun eachOfSixChannelsMatchesTheSameChannelConvertedAlone() {
        val channels = 6; val frames = 4410
        val tones = doubleArrayOf(311.0, 521.0, 733.0, 77.0, 947.0, 1163.0)
        val data = FloatArray(frames * channels) { i -> (sin(2 * PI * tones[i % channels] * (i / channels) / 44100) * .2).toFloat() }
        val together = convertChannels(data, channels, PcmRateAdapter(channels))
        assertEquals(4800 * channels, together.size)
        for (c in 0 until channels) {
            val alone = convertChannels(FloatArray(frames) { data[it * channels + c] }, 1, PcmRateAdapter(1))
            assertEquals(4800, alone.size)
            for (i in alone.indices) assertEquals("channel $c frame $i", alone[i], together[i * channels + c], 0f)
        }
    }
    @Test fun highResolutionFamilyIsConvertedToTheMatchingRenderRate() {
        assertEquals(48000, PcmRateAdapter.CONVERTIBLE_RATES[44100]); assertEquals(96000, PcmRateAdapter.CONVERTIBLE_RATES[88200])
        assertEquals(192000, PcmRateAdapter.CONVERTIBLE_RATES[176400]); assertNull(PcmRateAdapter.CONVERTIBLE_RATES[32000])
        val out = convertChannels(FloatArray(88200 * 8) { if (it % 8 == 3) .1f else .3f }, 8, PcmRateAdapter(8))
        assertEquals(96000 * 8, out.size)
        for (i in 400 * 8 until out.size) assertEquals(if (i % 8 == 3) .1f else .3f, out[i], .000001f)
    }
}
