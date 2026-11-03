package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class BinauralRendererTest {
    private val bed = listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE,
        SpeakerPosition.BL, SpeakerPosition.BR, SpeakerPosition.SL, SpeakerPosition.SR)
    private fun buffer(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    /** Renders [signal] on one speaker, in blocks of the given sizes, returning (left, right). */
    private fun render(renderer: BinauralRenderer, channels: Int, channel: Int, signal: FloatArray, blocks: List<Int>): Pair<FloatArray, FloatArray> {
        val left = FloatArray(signal.size); val right = FloatArray(signal.size)
        var at = 0
        for (size in blocks) {
            val frames = minOf(size, signal.size - at)
            if (frames <= 0) break
            val input = buffer(frames * channels)
            repeat(frames) { t -> repeat(channels) { ch -> input.putFloat(if (ch == channel) signal[at + t] else 0f) } }
            input.flip()
            val output = buffer(frames * 2)
            renderer.render(input, output, frames)
            assertFalse(input.hasRemaining()); assertFalse(output.hasRemaining())
            output.flip()
            repeat(frames) { t -> left[at + t] = output.float; right[at + t] = output.float }
            at += frames
        }
        return left to right
    }
    private fun impulse(length: Int) = FloatArray(length).also { it[0] = .1f }
    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size) = (from until to).sumOf { (x[it] * x[it]).toDouble() }
    private fun peakIndex(x: FloatArray) = x.indices.maxBy { abs(x[it]) }

    @Test fun harmonicsFollowAmbixSn3dWithLeftPositiveAzimuth() {
        val left = BinauralRenderer.harmonics(90.0, 0.0)
        assertEquals(1f, left[0], 1e-6f); assertEquals(1f, left[1], 1e-6f) // W, Y
        assertEquals(0f, left[2], 1e-6f); assertEquals(0f, left[3], 1e-6f) // Z, X
        val up = BinauralRenderer.harmonics(0.0, 90.0)
        assertEquals(1f, up[2], 1e-6f); assertEquals(1f, up[6], 1e-6f); assertEquals(1f, up[12], 1e-6f)
        // SN3D keeps every order's peak at most 1.
        for (az in -180..180 step 15) for (el in -90..90 step 15)
            BinauralRenderer.harmonics(az.toDouble(), el.toDouble()).forEach { assertTrue(abs(it) <= 1.0001f) }
    }

    @Test fun theFftPathEqualsDirectConvolutionOfTheHarmonicHrirs() {
        val renderer = BinauralRenderer(48000, bed, 512, 1f)
        val signal = FloatArray(1500) { ((it * 7919) % 200 - 100) / 2000f }
        val (left, right) = render(renderer, bed.size, 0, signal, List(4) { 512 })
        val hrir = Hrir.forRate(48000)
        val y = BinauralRenderer.harmonics(30.0, 0.0)
        val makeup = Math.pow(10.0, 3.0 / 20)
        val antisymmetric = setOf(1, 4, 5, 9, 10, 11)
        for (t in signal.indices) {
            var s = 0.0; var a = 0.0
            for (acn in 0 until 16) for (k in 0..minOf(t, hrir[acn].size - 1)) {
                val v = signal[t - k] * y[acn] * hrir[acn][k] * makeup
                if (acn in antisymmetric) a += v else s += v
            }
            assertEquals("left $t", s + a, left[t].toDouble(), 2e-5)
            assertEquals("right $t", s - a, right[t].toDouble(), 2e-5)
        }
    }

    @Test fun blockSizeDoesNotChangeTheRender() {
        val signal = FloatArray(4000) { ((it * 104729) % 300 - 150) / 3000f }
        val whole = render(BinauralRenderer(48000, bed, 512, 1f), bed.size, 6, signal, List(8) { 512 })
        val ragged = render(BinauralRenderer(48000, bed, 512, 1f), bed.size, 6, signal, generateSequence(1) { it % 467 + 37 }.take(200).toList())
        for (t in signal.indices) {
            assertEquals(whole.first[t], ragged.first[t], 1e-5f)
            assertEquals(whole.second[t], ragged.second[t], 1e-5f)
        }
    }

    @Test fun aLeftSpeakerIsLouderAndEarlierInTheLeftEarAndMirrorsOnTheRight() {
        val renderer = BinauralRenderer(48000, bed, 512, 1f)
        val (sl, sr) = render(renderer, bed.size, bed.indexOf(SpeakerPosition.SL), impulse(512), listOf(512))
        assertTrue(10 * log10(energy(sl) / energy(sr)) > 5)
        assertTrue(peakIndex(sl) < peakIndex(sr))
        val mirror = BinauralRenderer(48000, bed, 512, 1f)
        val (rl, rr) = render(mirror, bed.size, bed.indexOf(SpeakerPosition.SR), impulse(512), listOf(512))
        for (t in 0 until 512) { assertEquals(sl[t], rr[t], 1e-6f); assertEquals(sr[t], rl[t], 1e-6f) }
        val centre = BinauralRenderer(48000, bed, 512, 1f)
        val (cl, cr) = render(centre, bed.size, bed.indexOf(SpeakerPosition.FC), impulse(512), listOf(512))
        for (t in 0 until 512) assertEquals(cl[t], cr[t], 1e-6f)
    }

    @Test fun lfeIsNotSpatialisedAndKeepsOnlyLowFrequencies() {
        val renderer = BinauralRenderer(48000, bed, 512, 1f)
        val lfe = bed.indexOf(SpeakerPosition.LFE)
        val low = FloatArray(4800) { (.05 * kotlin.math.sin(2 * Math.PI * 50 * it / 48000)).toFloat() }
        val (ll, lr) = render(renderer, bed.size, lfe, low, List(10) { 512 })
        for (t in low.indices) assertEquals(ll[t], lr[t], 0f)
        val high = FloatArray(4800) { (.05 * kotlin.math.sin(2 * Math.PI * 2000 * it / 48000)).toFloat() }
        val (hl, _) = render(BinauralRenderer(48000, bed, 512, 1f), bed.size, lfe, high, List(10) { 512 })
        assertTrue(energy(hl, 2400) < energy(ll, 2400) / 1000)
    }

    @Test fun higherRatesKeepTheSameDirectionalResponse() {
        val base = Hrir.forRate(48000)
        for (rate in listOf(96000, 192000)) {
            val factor = rate / 48000
            val upsampled = Hrir.forRate(rate)
            assertEquals(256 * factor, upsampled[0].size)
            for (acn in 0 until 16) {
                // DC gain is preserved and the original samples are interpolated (scaled by 1/factor).
                assertEquals(base[acn].sum(), upsampled[acn].sum(), 1e-4f)
                for (k in 8 until 200) assertEquals(base[acn][k] / factor, upsampled[acn][k * factor], 2e-3f)
            }
            val renderer = BinauralRenderer(rate, bed, 512, 1f)
            val (l, r) = render(renderer, bed.size, bed.indexOf(SpeakerPosition.SL), impulse(2048), List(4) { 512 })
            assertTrue(10 * log10(energy(l) / energy(r)) > 5)
        }
    }

    @Test fun theLimiterHoldsTheCeilingAndOutputStaysFinite() {
        val renderer = BinauralRenderer(48000, bed, 512, 1f)
        val loud = buffer(512 * bed.size).apply { repeat(512 * bed.size) { putFloat(if (it % 3 == 0) 4f else -4f) }; flip() }
        val out = buffer(1024); renderer.render(loud, out, 512); out.flip()
        repeat(1024) { val v = out.float; assertTrue(v.isFinite() && abs(v) <= .9801f) }
        val bad = buffer(512 * bed.size).apply { repeat(512 * bed.size) { putFloat(if (it == 5) Float.NaN else .01f) }; flip() }
        val out2 = buffer(1024); renderer.render(bad, out2, 512); out2.flip()
        repeat(1024) { assertTrue(out2.float.isFinite()) }
    }

    @Test fun diffuseLevelIsCloseToUnityPerEar() {
        // Uncorrelated noise on all seven full-range speakers, at the same total power as one
        // speaker, should reach each ear within a few dB of that power.
        val channels = bed.size
        val frames = 48000
        val random = java.util.Random(7)
        val renderer = BinauralRenderer(48000, bed, 512, 1f)
        var inputPower = 0.0; var leftPower = 0.0
        val scale = .05f / sqrt(7f)
        var at = 0
        while (at < frames) {
            val input = buffer(512 * channels)
            repeat(512) { repeat(channels) { ch ->
                val v = if (bed[ch] == SpeakerPosition.LFE) 0f else (random.nextGaussian() * scale).toFloat()
                inputPower += v * v; input.putFloat(v)
            } }
            input.flip()
            val out = buffer(1024); renderer.render(input, out, 512); out.flip()
            repeat(512) { val l = out.float; out.float; if (at > 2048) leftPower += l * l }
            at += 512
        }
        val db = 10 * log10(leftPower / (inputPower * (frames - 2560) / frames / 1))
        assertTrue("ear level $db dB", db in -4.0..4.0)
    }
}
