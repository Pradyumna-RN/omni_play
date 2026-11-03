package labs.rtensor.dsp

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DownmixContractTest {
    @Test fun stereoRequiresARealFrontPairAndItsOwnInputLayout() {
        val stereo = listOf(SpeakerPosition.FL, SpeakerPosition.FR)
        assertEquals(2, DownmixFormat(48000, stereo).inputChannels)
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FC)) }
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(48000, stereo, layout = RenderLayout.SHARUR_916) }
    }
    @Test fun decoderMaskDistinguishesBackAndSideSurrounds() {
        val back = AndroidSpeakerLayout.fromMask(4 or 8 or 16 or 32 or 64 or 128, 6)
        val side = AndroidSpeakerLayout.fromMask(4 or 8 or 16 or 32 or 2048 or 4096, 6)
        assertEquals(listOf(SpeakerPosition.BL, SpeakerPosition.BR), back.takeLast(2))
        assertEquals(listOf(SpeakerPosition.SL, SpeakerPosition.SR), side.takeLast(2))
    }
    @Test fun unknownAndInconsistentMasksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AndroidSpeakerLayout.fromMask(0, 6) }
        assertThrows(IllegalArgumentException::class.java) { AndroidSpeakerLayout.fromMask(4 or 8 or 1024, 3) }
        assertThrows(IllegalArgumentException::class.java) { AndroidSpeakerLayout.fromMask(4 or 8 or 16, 6) }
    }
    @Test fun layoutIsDefensivelyCopiedAndCannotChangeItsChannelContract() {
        val input = mutableListOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC)
        val format = DownmixFormat(48000, input)
        input.clear()
        assertEquals(3, format.inputChannels)
        assertThrows(UnsupportedOperationException::class.java) { (format.positions as MutableList).clear() }
    }
    @Test fun unsupportedRatesAndDuplicatePositionsAreRejected() {
        val positions = listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC)
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(44100, positions) }
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FR)) }
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(48000, positions, maxBlockFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { DownmixFormat(48000, positions, preampDb = Float.NaN) }
    }
    @Test fun heightsSelectTheFullLayoutAndReportsDoNotInventAtmosOrBitPerfect() {
        val format = DownmixFormat(96000, listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.TFL))
        assertEquals(RenderLayout.SHARUR_916, format.layout)
        val report = DownmixReport(format.sampleRate, format.positions)
        assertFalse(report.atmosVerified); assertFalse(report.bitPerfect)
        assertEquals(2, report.outputChannels)
    }
    private fun buffer(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    @Test fun pcm24RetainsTheLowBitsThatPcm16WouldDiscard() {
        val input = buffer(9).apply { put(byteArrayOf(1, 0, 0, -1, -1, 127, 0, 0, -128)); flip() }
        val output = buffer(12)
        PcmFloatConverter.convert(input, output, 3, C.ENCODING_PCM_24BIT)
        output.flip()
        assertEquals(1f / 8388608f, output.float, 0f)
        assertEquals(8388607f / 8388608f, output.float, 0f)
        assertEquals(-1f, output.float, 0f)
        assertEquals(9, input.position())
    }
    @Test fun signedPcm16And32AreNormalizedWithoutClippingTheNegativeEndpoint() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT)) {
            val input = buffer(12)
            if (encoding == C.ENCODING_PCM_16BIT) { input.putShort(Short.MIN_VALUE); input.putShort(16384) }
            else { input.putInt(Int.MIN_VALUE); input.putInt(1073741824) }
            input.flip(); val output = buffer(8)
            PcmFloatConverter.convert(input, output, 2, encoding); output.flip()
            assertEquals(-1f, output.float, 0f); assertEquals(.5f, output.float, 0f)
        }
    }
    @Test fun floatConversionPreservesSamplesAndRejectsSmallBuffersBeforeReading() {
        val input = buffer(8).apply { putFloat(.1234567f); putFloat(-.0000001f); flip() }
        val tooSmall = buffer(4)
        assertThrows(IllegalArgumentException::class.java) { PcmFloatConverter.convert(input, tooSmall, 2, C.ENCODING_PCM_FLOAT) }
        assertEquals(0, input.position())
        val out = buffer(8); PcmFloatConverter.convert(input, out, 2, C.ENCODING_PCM_FLOAT); out.flip()
        assertEquals(.1234567f, out.float, 0f); assertEquals(-.0000001f, out.float, 0f)
    }
}
