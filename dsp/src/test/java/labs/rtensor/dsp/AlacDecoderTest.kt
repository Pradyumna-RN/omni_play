package labs.rtensor.dsp

import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.*
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.decoder.ffmpeg.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlacDecoderTest {
    private fun verify(rate: Int, channels: Int) {
        if (System.getProperty("rtensor.ffmpeg.expectNative") == "true") assertTrue(FfmpegLibrary.isAvailable())
        else assumeTrue(FfmpegLibrary.isAvailable())
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC))
        val bytes = checkNotNull(javaClass.getResourceAsStream("/audio/owned-24bit-$rate-${channels}ch.m4a")).readBytes()
        val data = ByteArrayDataSource(bytes); data.open(DataSpec(Uri.parse("memory://owned-alac.mp4")))
        val input = DefaultExtractorInput(data, 0, bytes.size.toLong())
        val packets = mutableListOf<ByteArray>(); var format: Format? = null
        val pending = ByteArrayOutputStream()
        val track = object : TrackOutput {
            override fun format(value: Format) { format = value }
            override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int {
                val buffer = ByteArray(length); val n = input.read(buffer, 0, length)
                if (n > 0) pending.write(buffer, 0, n)
                return n
            }
            override fun sampleData(input: ParsableByteArray, length: Int, part: Int) { val b = ByteArray(length); input.readBytes(b, 0, length); pending.write(b) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                assertNull(crypto); assertEquals(0, offset); assertEquals(size, pending.size()); packets += pending.toByteArray(); pending.reset()
            }
        }
        val extractor = FragmentedMp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput { assertEquals(C.TRACK_TYPE_AUDIO, type); return track }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try { val holder = PositionHolder(); var count = 0; while (extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) check(++count < 1000) }
        finally { data.close(); extractor.release() }
        val encoded = checkNotNull(format)
        assertEquals(MimeTypes.AUDIO_ALAC, encoded.sampleMimeType); assertEquals(channels, encoded.channelCount); assertEquals(rate, encoded.sampleRate)
        val decoder = FfmpegAudioDecoder(encoded, 4, 4, 131072, true)
        fun decode(packet: ByteArray): ByteArray {
            val end = System.nanoTime() + 3_000_000_000L
            var source = decoder.dequeueInputBuffer()
            while (source == null && System.nanoTime() < end) { Thread.sleep(1); source = decoder.dequeueInputBuffer() }
            checkNotNull(source).apply { ensureSpaceForWrite(packet.size); checkNotNull(this.data).put(packet); flip(); decoder.queueInputBuffer(this) }
            var output = decoder.dequeueOutputBuffer()
            while (output == null && System.nanoTime() < end) { Thread.sleep(1); output = decoder.dequeueOutputBuffer() }
            val result = checkNotNull(output)
            try { val pcm = checkNotNull(result.data); return ByteArray(pcm.remaining()).also(pcm::get) }
            finally { result.release() }
        }
        try {
            val all = ByteArrayOutputStream(); packets.forEach { all.write(decode(it)) }
            assertEquals(8192 * channels * 4, all.size()); assertEquals(rate, decoder.sampleRate); assertEquals(channels, decoder.channelCount)
            assertEquals(if (channels == 2) 3L else 0x3fL, decoder.channelLayoutMask)
            val pcm = ByteBuffer.wrap(all.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var lowBits = false
            for (i in 0 until 8192) for (c in 0 until channels) {
                val value = Math.rint(1_000_000 * sin(2 * PI * (311 + 211 * c) * i / rate)).toInt() + ((i * 7 + c * 11) % 127)
                val expected = value / 8388608f
                assertEquals("24-bit sample $i channel $c", expected, pcm[i * channels + c], 0f)
                if (value and 255 != 0) lowBits = true
            }
            assertTrue("Fixture exercises detail below 16-bit", lowBits)
            decoder.flush(); assertArrayEquals(all.toByteArray().copyOfRange(0, 4096 * channels * 4), decode(packets.first()))
        } finally { decoder.release() }
    }
    @Test fun stereo192kPreservesEvery24BitSampleAndSeeksWithoutLoss() = verify(192000, 2)
    @Test fun multichannel96kPreservesSixDiscrete24BitChannelsAndTheirLayout() = verify(96000, 6)
}
