package labs.rtensor.dsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Eac3DecoderTest {
    private fun native() {
        if (System.getProperty("rtensor.ffmpeg.expectNative") == "true") assertTrue("FFmpeg JNI must load", FfmpegLibrary.isAvailable())
        else assumeTrue(FfmpegLibrary.isAvailable())
    }
    private fun format(channels: Int = 6, mime: String = MimeTypes.AUDIO_E_AC3) = Format.Builder()
        .setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48000).build()
    private fun frames(resource: String): List<ByteArray> {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/audio/$resource")).readBytes()
        val result = mutableListOf<ByteArray>(); var offset = 0
        while (offset < bytes.size) {
            assertEquals(0x0b, bytes[offset].toInt() and 255); assertEquals(0x77, bytes[offset + 1].toInt() and 255)
            val size = 2 * ((((bytes[offset + 2].toInt() and 7) shl 8) or (bytes[offset + 3].toInt() and 255)) + 1)
            assertTrue(offset + size <= bytes.size)
            result += bytes.copyOfRange(offset, offset + size); offset += size
        }
        return result
    }
    private fun decode(decoder: FfmpegAudioDecoder, frame: ByteArray, pts: Long): ByteArray {
        val until = System.nanoTime() + 3_000_000_000
        var input = decoder.dequeueInputBuffer()
        while (input == null && System.nanoTime() < until) { Thread.sleep(1); input = decoder.dequeueInputBuffer() }
        val owned = checkNotNull(input)
        owned.ensureSpaceForWrite(frame.size); checkNotNull(owned.data).put(frame); owned.timeUs = pts; owned.flip()
        decoder.queueInputBuffer(owned)
        var output = decoder.dequeueOutputBuffer()
        while (output == null && System.nanoTime() < until) { Thread.sleep(1); output = decoder.dequeueOutputBuffer() }
        val decoded = checkNotNull(output) { "No decoded audio before deadline" }
        try {
            val pcm = checkNotNull(decoded.data)
            return ByteArray(pcm.remaining()).also(pcm::get)
        } finally { decoded.release() }
    }

    /** MP4 E-AC-3 samples can hold several syncframes. Each one must land after the
     * previous frame's PCM, including when the output buffer has to grow. */
    @Test fun severalSyncframesInOnePacketDecodeContiguouslyAndMatchSeparateDecoding() {
        native()
        val packets = frames("owned-51.eac3").take(12)
        val separate = FfmpegAudioDecoder(format(), 4, 4, 8192, true)
        val expected = try { ByteArrayOutputStream().also { out -> packets.forEachIndexed { i, f -> out.write(decode(separate, f, i * 32000L)) } }.toByteArray() }
            finally { separate.release() }
        assertEquals(12 * 1536 * 6 * 4, expected.size)
        for (group in listOf(2, 3, 6)) {
            val joined = FfmpegAudioDecoder(format(), 4, 4, 8192, true)
            try {
                val actual = ByteArrayOutputStream()
                packets.chunked(group).forEachIndexed { i, chunk ->
                    val packet = ByteArrayOutputStream().also { out -> chunk.forEach(out::write) }.toByteArray()
                    val pcm = decode(joined, packet, i * 32000L * group)
                    assertEquals("A $group-frame packet returns every frame", chunk.size * 1536 * 6 * 4, pcm.size)
                    actual.write(pcm)
                }
                assertArrayEquals("$group syncframes per packet", expected, actual.toByteArray())
                assertEquals(6, joined.channelCount); assertEquals(0x60fL, joined.channelLayoutMask)
            } finally { joined.release() }
        }
    }

    @Test fun aMidStreamLayoutChangeIsAnExplicitDecoderError() {
        native()
        val decoder = FfmpegAudioDecoder(format(), 4, 4, 8192, true)
        try {
            assertEquals(1536 * 6 * 4, decode(decoder, frames("owned-51.eac3")[0], 0).size)
            val error = assertThrows(FfmpegDecoderException::class.java) { decode(decoder, frames("owned-stereo.eac3")[0], 32000) }
            assertTrue(error.message, "6 ch" in error.message!! && "2 ch" in error.message!! && "0x60f" in error.message!!)
            assertEquals("The configured layout is kept", 0x60fL, decoder.channelLayoutMask)
            assertEquals(DownmixStage.DECODER, PlaybackDiagnostics.describe(error)!!.stage)
        } finally { decoder.release() }
    }

    @Test fun actualEac3DecodesSixDistinctChannelsWithoutStereoFoldingAndResetsAfterSeeking() {
        native()
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3_JOC))
        assertFalse(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AAC))
        val decoder = FfmpegAudioDecoder(format(), 4, 4, 8192, true)
        try {
            val packets = frames("owned-51.eac3"); val audio = ByteArrayOutputStream()
            packets.forEachIndexed { index, frame -> audio.write(decode(decoder, frame, index * 32000L)) }
            assertEquals(6, decoder.channelCount); assertEquals(48000, decoder.sampleRate)
            assertEquals(0x60fL, decoder.channelLayoutMask) // E-AC-3 declares 5.1(side), independently checked with ffprobe.
            val pcm = ByteBuffer.wrap(audio.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frequencies = listOf(311, 521, 733, 77, 947, 1163)
            for (channel in 0..5) {
                fun power(frequency: Int): Double {
                    var re = 0.0; var im = 0.0
                    for (frame in 4800 until 24000) {
                        val value = pcm[frame * 6 + channel].toDouble(); assertTrue(value.isFinite())
                        val angle = 2 * PI * frequency * frame / 48000
                        re += value * cos(angle); im += value * sin(angle)
                    }
                    return re * re + im * im
                }
                val wanted = power(frequencies[channel])
                assertTrue("Channel $channel must carry its own tone", wanted > 1000)
                frequencies.filterIndexed { index, _ -> index != channel }.forEach {
                    assertTrue("Channel $channel must not be swapped or downmixed", wanted > 20 * power(it))
                }
            }
            decoder.flush()
            assertTrue(decode(decoder, packets[8], 2_000_000).isNotEmpty())
            assertEquals(6, decoder.channelCount); assertEquals(0x60fL, decoder.channelLayoutMask)
        } finally { decoder.release() }
    }

    @Test fun actualDecoderPcmRunsThroughOriginalDmrWhileStereoIsByteIdentical() {
        native()
        if (System.getProperty("rtensor.dsp.expectNative") == "true") assertTrue(NativeDownmixEngine.isAvailable)
        else assumeTrue(NativeDownmixEngine.isAvailable)
        for (channels in listOf(6, 2)) {
            val decoder = FfmpegAudioDecoder(format(channels), 4, 4, 8192, true)
            val output = ByteArrayOutputStream(); val reports = mutableListOf<DownmixReport?>()
            val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
                when (method.name) {
                    "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    "handleBuffer" -> { val b = args!![0] as ByteBuffer; output.write(ByteArray(b.remaining()).also(b::get)); true }
                    else -> when (method.returnType) { Boolean::class.javaPrimitiveType -> true; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
                }
            } as AudioSink
            var engines = 0
            val sink = DownmixAudioSink(delegate, { FfmpegSpeakerLayout.positions(decoder.channelLayoutMask, it) },
                engineFactory = { engines++; NativeDownmixEngine(it) }, onReport = reports::add)
            try {
                val pcm = decode(decoder, frames(if (channels == 6) "owned-51.eac3" else "owned-stereo.eac3")[0], 0)
                sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(decoder.channelCount)
                    .setSampleRate(decoder.sampleRate).setPcmEncoding(decoder.encoding).build(), 0, null)
                assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(pcm.size).order(ByteOrder.nativeOrder()).apply { put(pcm); flip() }, 0, 1))
                assertFalse(reports.last()!!.atmosVerified)
                if (channels == 2) { assertEquals(0, engines); assertArrayEquals(pcm, output.toByteArray()); assertFalse(reports.last()!!.comparisonAvailable) }
                else {
                    assertEquals(1, engines); assertEquals(pcm.size / 3, output.size()); assertEquals(6, reports.last()!!.inputPositions.size)
                    val rendered = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    var audible = false
                    while (rendered.hasRemaining()) { val sample = rendered.get(); assertTrue(sample.isFinite()); audible = audible || abs(sample) > 1e-8 }
                    assertTrue("Original DMR must produce audio from the decoded bed", audible)
                }
            } finally { sink.release(); decoder.release() }
        }
    }

    @Test fun factorySelectsSoftwareOnlyForClearAc3AndEac3AndMapsMasksConservatively() {
        native()
        val context: Context = RuntimeEnvironment.getApplication()
        val factory = object : DownmixRenderersFactory(context) {
            fun audio(): List<Renderer> {
                val out = ArrayList<Renderer>(); val handler = Handler(Looper.getMainLooper())
                buildAudioRenderers(context, 0, MediaCodecSelector.DEFAULT, true, buildAudioSink(context, true, false), handler, object : AudioRendererEventListener {}, out)
                return out
            }
        }
        val software = factory.audio().first()
        assertEquals("MusicFfmpegBedAudioRenderer", software.name)
        assertEquals(C.FORMAT_HANDLED, software.capabilities.supportsFormat(format()) and 7)
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, software.capabilities.supportsFormat(format(2, MimeTypes.AUDIO_OPUS)) and 7)
        assertEquals(C.FORMAT_UNSUPPORTED_DRM, software.capabilities.supportsFormat(format().buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build()) and 7)
        val protected = format().buildUpon().setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, "audio/mp4", byteArrayOf(1)))).build()
        assertEquals(C.FORMAT_UNSUPPORTED_DRM, software.capabilities.supportsFormat(protected) and 7)
        assertEquals(listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.SL, SpeakerPosition.SR), FfmpegSpeakerLayout.positions(0x60f, 6))
        assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0, 6) }
        assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0x13f, 7) } // back-centre is not a supported DMR position
        assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0x3f, 8) }
    }
}
