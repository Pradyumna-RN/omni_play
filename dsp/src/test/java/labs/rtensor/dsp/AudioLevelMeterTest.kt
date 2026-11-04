package labs.rtensor.dsp

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioLevelMeterTest {
    private val stereo = listOf(SpeakerPosition.FL, SpeakerPosition.FR)
    private val surround = stereo + listOf(SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun pcm(values: List<Float>, frames: Int = 2400, encoding: Int = C.ENCODING_PCM_FLOAT): ByteBuffer {
        val size = if (encoding == C.ENCODING_PCM_8BIT) 1 else PcmFloatConverter.bytesPerSample(encoding)
        return ByteBuffer.allocateDirect(frames * values.size * size).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { values.forEach { x -> when (encoding) {
                C.ENCODING_PCM_FLOAT -> putFloat(x)
                C.ENCODING_PCM_8BIT -> put((x * 128 + 128).toInt().toByte())
                C.ENCODING_PCM_16BIT -> putShort((x * 32768).toInt().toShort())
                C.ENCODING_PCM_32BIT -> putInt((x * 2147483648.0).toInt())
                else -> { val v = (x * 8388608).toInt(); put(v.toByte()); put((v shr 8).toByte()); put((v shr 16).toByte()) }
            } } }; flip()
        }
    }
    @Test fun eachActualChannelUsesAbsoluteDbfsAndSilenceNeverLightsUp() {
        val frames = mutableListOf<AudioLevelFrame?>()
        val meter = AudioLevelMeter({ true }, frames::add, { 123L })
        meter.configure(48000, surround, 48000, stereo)
        val input = pcm(listOf(.5f, .25f, 0f, .125f, .0625f, .03125f))
        val out = pcm(listOf(.25f, -.125f))
        input.mark(); meter.input(input, 0, input.limit(), C.ENCODING_PCM_FLOAT)
        meter.output(out, 0, out.limit(), C.ENCODING_PCM_FLOAT)
        val f = frames.filterNotNull().single()
        assertEquals(surround, f.input.map { it.position }); assertEquals(6, f.input.size)
        assertEquals(-6.0206f, f.input[0].rmsDb, .0001f)
        assertEquals(-12.0412f, f.input[1].peakDb, .0001f)
        assertEquals(-120f, f.input[2].peakDb, 0f)
        assertEquals(-18.0618f, f.output[1].rmsDb, .0001f)
        assertEquals(123L, f.measuredAtNs)
        assertEquals(0, input.position()); input.reset(); assertEquals(0, out.position())
    }
    @Test fun bypassMetersEveryPcmDepthWithoutChangingAnySampleOrBufferState() {
        for (encoding in listOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val frames = mutableListOf<AudioLevelFrame?>();val meter = AudioLevelMeter({ true }, frames::add)
            meter.configure(48000, stereo, 48000, stereo)
            val input = pcm(listOf(.5f, -.25f), encoding = encoding)
            val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            meter.bypass(input.asReadOnlyBuffer(), 0, input.limit(), encoding)
            val f = frames.filterNotNull().single()
            assertEquals(-6.0206f, f.input[0].rmsDb, .0001f)
            assertEquals(-12.0412f, f.input[1].rmsDb, .0001f)
            assertEquals(f.input, f.output)
            assertEquals(0, input.position())
            assertArrayEquals(original, ByteArray(input.remaining()).also { input.duplicate().get(it) })
        }
    }
    @Test fun disabledAndResetMetersCannotPublishOldActivityOrOldChannelLayouts() {
        var enabled = false;val frames = mutableListOf<AudioLevelFrame?>()
        val meter = AudioLevelMeter({ enabled }, frames::add)
        meter.configure(48000, surround, 48000, surround)
        val input = pcm(List(6) { .5f })
        meter.bypass(input, 0, input.limit(), C.ENCODING_PCM_FLOAT)
        assertTrue(frames.filterNotNull().isEmpty())
        enabled = true; meter.bypass(input, 0, input.limit(), C.ENCODING_PCM_FLOAT)
        assertEquals(6, frames.last()!!.input.size)
        enabled = false; meter.bypass(input, 0, input.limit(), C.ENCODING_PCM_FLOAT)
        assertNull(frames.last())
        meter.configure(48000, stereo, 48000, stereo); enabled = true
        val silence = pcm(listOf(0f, 0f));meter.bypass(silence, 0, silence.limit(), C.ENCODING_PCM_FLOAT)
        assertEquals(2, frames.last()!!.input.size);assertTrue(frames.last()!!.input.all { it.peakDb == -120f })
    }
    @Test fun unknownSpeakersStayUnknownAndNonfiniteMeterValuesNeverEscape() {
        val frames = mutableListOf<AudioLevelFrame?>();val meter = AudioLevelMeter({ true }, frames::add)
        meter.configure(48000, listOf(null, null), 48000, listOf(null, null))
        val input = pcm(listOf(Float.NaN, Float.POSITIVE_INFINITY))
        meter.bypass(input, 0, input.limit(), C.ENCODING_PCM_FLOAT)
        assertTrue(frames.last()!!.input.all { it.position == null && it.rmsDb == -120f })
        assertTrue(input.getFloat(0).isNaN()) // observer does not sanitize the audio itself
    }
    @Test fun partialWindowsAndRetriesAccumulateEachConsumedSampleExactlyOnce() {
        val frames = mutableListOf<AudioLevelFrame?>();val meter = AudioLevelMeter({ true }, frames::add)
        meter.configure(48000, stereo, 48000, stereo)
        val input = pcm(listOf(.5f, .25f))
        meter.bypass(input, 0, 8, C.ENCODING_PCM_FLOAT)
        assertTrue(frames.filterNotNull().isEmpty())
        meter.bypass(input, 8, input.limit(), C.ENCODING_PCM_FLOAT)
        assertEquals(1, frames.filterNotNull().size)
        assertEquals(-6.0206f, frames.last()!!.input[0].rmsDb, .0001f)
        assertEquals(-12.0412f, frames.last()!!.output[1].peakDb, .0001f)
    }
}
