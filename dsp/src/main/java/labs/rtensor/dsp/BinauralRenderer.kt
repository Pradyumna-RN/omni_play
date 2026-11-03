package labs.rtensor.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Headphone render of a speaker layout: every speaker channel is encoded at its
 * nominal direction into third-order ambisonics (ACN/SN3D), and the 16 ambisonic
 * signals are convolved with SADIE-derived spherical-harmonic HRIRs (Omnitone, Apache-2.0).
 * Left/right symmetry gives both ears from one HRIR set: L = S + A, R = S - A, where
 * S holds the left/right-symmetric harmonics (m >= 0) and A the antisymmetric ones (m < 0).
 *
 * This is generic head-related filtering of the speaker channels it receives. It does
 * not decode Atmos objects; channels the source does not carry are not invented here.
 * The LFE channel is not spatialised; it is low-passed and sent to both ears.
 *
 * Zero added latency: each call convolves its whole block (overlap-add, carrying the
 * HRIR tail), so blocks of any size up to [maxFrames] can be rendered.
 */
internal class BinauralRenderer(
    val sampleRate: Int,
    positions: List<SpeakerPosition>,
    val maxFrames: Int,
    /** Linear gain applied to every input channel before rendering. */
    inputGain: Float,
) {
    private val channels = positions.size
    private val lfeIndex = positions.indexOf(SpeakerPosition.LFE)
    /** encode[ch * 16 + acn]: SN3D gain of channel ch into harmonic acn, input gain and makeup included. */
    private val encode = FloatArray(channels * ORDER_CHANNELS)
    private val hrir: Array<FloatArray> = Hrir.forRate(sampleRate)
    private val taps = hrir[0].size
    private val spectra = HashMap<Int, Array<FloatArray>>()
    private val ffts = HashMap<Int, Fft>()
    private val maxSize = Fft.sizeFor(maxFrames + taps - 1)
    private val work = Array(ORDER_CHANNELS) { FloatArray(maxFrames) }
    private val lfe = FloatArray(maxFrames)
    private val zr = FloatArray(maxSize); private val zi = FloatArray(maxSize)
    private val sr = FloatArray(maxSize); private val si = FloatArray(maxSize)
    private val ar = FloatArray(maxSize); private val ai = FloatArray(maxSize)
    private val tailS = FloatArray(taps - 1); private val tailA = FloatArray(taps - 1)
    private val lfeFilter = Biquad.lowPass(sampleRate, 120.0)
    private val lfeGain: Float
    private val release = exp(-1.0 / (0.08 * sampleRate)).toFloat()
    private var limiterGain = 1f

    init {
        require(channels in 1..16 && positions.distinct().size == channels)
        require(maxFrames in 1..8192)
        require(inputGain.isFinite() && inputGain > 0f)
        val makeup = inputGain * MAKEUP
        positions.forEachIndexed { ch, position ->
            if (position == SpeakerPosition.LFE) return@forEachIndexed
            val (azimuth, elevation) = direction(position)
            val y = harmonics(azimuth, elevation)
            for (acn in 0 until ORDER_CHANNELS) encode[ch * ORDER_CHANNELS + acn] = y[acn] * makeup
        }
        lfeGain = inputGain * MAKEUP * LFE_EAR_GAIN
        spectraFor(maxSize)
    }

    /** Interleaved float32 [channels] in, interleaved stereo float32 out. Positions advance. */
    fun render(input: ByteBuffer, output: ByteBuffer, frames: Int) {
        require(frames in 0..maxFrames)
        require(input.order() == ByteOrder.nativeOrder() && output.order() == ByteOrder.nativeOrder())
        require(input.remaining() >= frames * channels * 4 && output.remaining() >= frames * 8) { "Binaural buffer is too small" }
        if (frames == 0) return
        val base = input.position()
        for (acn in 0 until ORDER_CHANNELS) work[acn].fill(0f, 0, frames)
        lfe.fill(0f, 0, frames)
        for (t in 0 until frames) {
            val frame = base + t * channels * 4
            for (ch in 0 until channels) {
                // A non-finite sample would poison the carried convolution tail.
                val x = input.getFloat(frame + ch * 4).let { if (it.isFinite()) it else 0f }
                if (ch == lfeIndex) { lfe[t] = x; continue }
                if (x == 0f) continue
                val row = ch * ORDER_CHANNELS
                for (acn in 0 until ORDER_CHANNELS) work[acn][t] += x * encode[row + acn]
            }
        }
        input.position(base + frames * channels * 4)

        val n = Fft.sizeFor(frames + taps - 1)
        val fft = ffts.getOrPut(n) { Fft(n) }
        val h = spectraFor(n)
        sr.fill(0f, 0, n); si.fill(0f, 0, n); ar.fill(0f, 0, n); ai.fill(0f, 0, n)
        // Two real harmonics per complex FFT, then split by Hermitian symmetry.
        for (pair in 0 until ORDER_CHANNELS / 2) {
            val a = 2 * pair; val b = a + 1
            zr.fill(0f, 0, n); zi.fill(0f, 0, n)
            System.arraycopy(work[a], 0, zr, 0, frames)
            System.arraycopy(work[b], 0, zi, 0, frames)
            fft.forward(zr, zi)
            val ha = h[a]; val hb = h[b]
            for (k in 0 until n) {
                val nk = (n - k) and (n - 1)
                // X_a = (Z[k] + conj Z[n-k]) / 2 ; X_b = (Z[k] - conj Z[n-k]) / 2j
                val xar = .5f * (zr[k] + zr[nk]); val xai = .5f * (zi[k] - zi[nk])
                val xbr = .5f * (zi[k] + zi[nk]); val xbi = -.5f * (zr[k] - zr[nk])
                accumulate(a, k, xar, xai, ha[2 * k], ha[2 * k + 1])
                accumulate(b, k, xbr, xbi, hb[2 * k], hb[2 * k + 1])
            }
        }
        // s and a are real, so one inverse transform of S + jA returns s in re, a in im.
        for (k in 0 until n) { val r = sr[k] - ai[k]; val i = si[k] + ar[k]; zr[k] = r; zi[k] = i }
        fft.inverse(zr, zi)
        val tail = taps - 1
        for (t in 0 until frames + tail) {
            if (t < tail) { zr[t] += tailS[t]; zi[t] += tailA[t] }
        }
        for (k in 0 until tail) { tailS[k] = zr[frames + k]; tailA[k] = zi[frames + k] }

        val out = output.position()
        for (t in 0 until frames) {
            val low = lfeFilter.next(lfe[t]) * lfeGain
            var left = zr[t] + zi[t] + low
            var right = zr[t] - zi[t] + low
            if (!left.isFinite() || !right.isFinite()) { left = 0f; right = 0f }
            // Release first, then clamp: the gain applied to this sample never lets it exceed the ceiling.
            limiterGain = 1f - (1f - limiterGain) * release
            val peak = max(abs(left), abs(right)) * limiterGain
            if (peak > CEILING) limiterGain *= CEILING / peak
            output.putFloat(out + t * 8, left * limiterGain)
            output.putFloat(out + t * 8 + 4, right * limiterGain)
        }
        output.position(out + frames * 8)
    }

    private fun accumulate(acn: Int, k: Int, xr: Float, xi: Float, hr: Float, hi: Float) {
        val pr = xr * hr - xi * hi; val pi = xr * hi + xi * hr
        if (ANTISYMMETRIC[acn]) { ar[k] += pr; ai[k] += pi } else { sr[k] += pr; si[k] += pi }
    }

    private fun spectraFor(n: Int): Array<FloatArray> = spectra.getOrPut(n) {
        val fft = ffts.getOrPut(n) { Fft(n) }
        Array(ORDER_CHANNELS) { acn ->
            val re = FloatArray(n); val im = FloatArray(n)
            System.arraycopy(hrir[acn], 0, re, 0, taps)
            fft.forward(re, im)
            FloatArray(2 * n) { if (it % 2 == 0) re[it / 2] else im[it / 2] }
        }
    }

    fun reset() {
        tailS.fill(0f); tailA.fill(0f); lfeFilter.reset(); limiterGain = 1f
    }

    companion object {
        const val ORDER_CHANNELS = 16
        /** Harmonics with m < 0 (ACN 1, 4, 5, 9, 10, 11) flip sign between the ears. */
        private val ANTISYMMETRIC = BooleanArray(ORDER_CHANNELS) { it in setOf(1, 4, 5, 9, 10, 11) }
        /** These filters average about -3 dB per ear over all directions; restore unity. */
        private val MAKEUP = 10f.pow(3f / 20f)
        /** LFE carries +10 dB in-band; a front speaker reaches each ear at about -4 dB. */
        private val LFE_EAR_GAIN = 10f.pow((10f - 4f) / 20f)
        private const val CEILING = .98f

        /** Nominal speaker directions in degrees, azimuth positive to the left. */
        fun direction(position: SpeakerPosition): Pair<Double, Double> = when (position) {
            SpeakerPosition.FL -> 30.0 to 0.0; SpeakerPosition.FR -> -30.0 to 0.0
            SpeakerPosition.FC, SpeakerPosition.LFE -> 0.0 to 0.0
            SpeakerPosition.SL -> 90.0 to 0.0; SpeakerPosition.SR -> -90.0 to 0.0
            SpeakerPosition.BL -> 135.0 to 0.0; SpeakerPosition.BR -> -135.0 to 0.0
            SpeakerPosition.BLC -> 165.0 to 0.0; SpeakerPosition.BRC -> -165.0 to 0.0
            SpeakerPosition.TFL -> 45.0 to 45.0; SpeakerPosition.TFR -> -45.0 to 45.0
            SpeakerPosition.TSL -> 90.0 to 45.0; SpeakerPosition.TSR -> -90.0 to 45.0
            SpeakerPosition.TBL -> 135.0 to 45.0; SpeakerPosition.TBR -> -135.0 to 45.0
        }

        /** Real third-order spherical harmonics, ACN order, SN3D, no Condon-Shortley phase. */
        fun harmonics(azimuthDegrees: Double, elevationDegrees: Double): FloatArray {
            val az = azimuthDegrees * PI / 180; val el = elevationDegrees * PI / 180
            val x = cos(az) * cos(el); val y = sin(az) * cos(el); val z = sin(el)
            val s3 = sqrt(3.0); val s15 = sqrt(15.0); val s58 = sqrt(5.0 / 8); val s38 = sqrt(3.0 / 8)
            return doubleArrayOf(
                1.0,
                y, z, x,
                s3 * x * y, s3 * y * z, .5 * (3 * z * z - 1), s3 * x * z, s3 / 2 * (x * x - y * y),
                s58 * y * (3 * x * x - y * y), s15 * x * y * z, s38 * y * (5 * z * z - 1), .5 * z * (5 * z * z - 3),
                s38 * x * (5 * z * z - 1), s15 / 2 * z * (x * x - y * y), s58 * x * (x * x - 3 * y * y),
            ).let { values -> FloatArray(ORDER_CHANNELS) { values[it].toFloat() } }
        }
    }
}

/** The bundled 48 kHz SH-HRIRs, band-limited-interpolated for 96 and 192 kHz playback. */
internal object Hrir {
    private const val TAPS = 256
    private val base: Array<FloatArray> by lazy {
        val stream = checkNotNull(Hrir::class.java.getResourceAsStream("toa_hrir_48k.f32")) { "The HRIR resource is missing" }
        val bytes = stream.use { it.readBytes() }
        check(bytes.size == BinauralRenderer.ORDER_CHANNELS * TAPS * 4) { "The HRIR resource is damaged" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        Array(BinauralRenderer.ORDER_CHANNELS) { FloatArray(TAPS) { buffer.float } }
    }
    private val cache = HashMap<Int, Array<FloatArray>>()

    @Synchronized fun forRate(rate: Int): Array<FloatArray> = cache.getOrPut(rate) {
        require(rate % 48000 == 0 && Integer.bitCount(rate / 48000) == 1) { "Binaural rendering supports 48, 96 and 192 kHz" }
        val factor = rate / 48000
        if (factor == 1) base else Array(base.size) { upsample(base[it], factor) }
    }

    /** Zero-padding the spectrum interpolates without adding content above 24 kHz; the
     * 1/factor scale that the longer inverse transform applies keeps the frequency response. */
    private fun upsample(h: FloatArray, factor: Int): FloatArray {
        val n = h.size; val m = n * factor
        val re = FloatArray(n) { h[it] }; val im = FloatArray(n)
        Fft(n).forward(re, im)
        val pr = FloatArray(m); val pi = FloatArray(m)
        for (k in 0 until n / 2) { pr[k] = re[k]; pi[k] = im[k] }
        for (k in 1 until n / 2) { pr[m - k] = re[n - k]; pi[m - k] = im[n - k] }
        pr[n / 2] = re[n / 2] / 2; pr[m - n / 2] = re[n / 2] / 2
        Fft(m).inverse(pr, pi)
        return pr
    }
}

/** In-place iterative radix-2 complex FFT. [inverse] includes the 1/n scale. */
internal class Fft(val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val reversed = IntArray(n)
    init {
        require(n >= 2 && Integer.bitCount(n) == 1)
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, -1f)
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, 1f)
        val scale = 1f / n
        for (i in 0 until n) { re[i] *= scale; im[i] *= scale }
    }
    private fun transform(re: FloatArray, im: FloatArray, sign: Float) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2; val stride = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cosTable[k * stride]; val wi = sign * sinTable[k * stride]
                    val a = start + k; val b = a + half
                    val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
    companion object {
        fun sizeFor(length: Int): Int = Integer.highestOneBit(max(2, length - 1)) shl 1
    }
}

/** Second-order Butterworth low-pass (bilinear transform). */
internal class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float,
) {
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun next(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = if (abs(y) < 1e-25f) 0f else y
        return y
    }
    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    companion object {
        fun lowPass(rate: Int, cutoff: Double): Biquad {
            val k = tan(PI * cutoff / rate); val q = 1 / sqrt(2.0)
            val norm = 1 / (1 + k / q + k * k)
            val b0 = k * k * norm
            return Biquad(b0.toFloat(), (2 * b0).toFloat(), b0.toFloat(), (2 * (k * k - 1) * norm).toFloat(), ((1 - k / q + k * k) * norm).toFloat())
        }
    }
}
