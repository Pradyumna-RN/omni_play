package labs.rtensor.dsp

import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import labs.rtensor.dsp.SpeakerPosition.*
import org.junit.Assert.*
import org.junit.Test

class FfmpegSpeakerLayoutTest {
    @Test fun ffmpegNativeOrderIsAscendingBitOrder() {
        assertEquals(listOf(FL, FR), FfmpegSpeakerLayout.positions(0x3, 2))
        assertEquals(listOf(FL, FR, FC, LFE, SL, SR), FfmpegSpeakerLayout.positions(0x60f, 6)) // 5.1(side), E-AC-3
        assertEquals(listOf(FL, FR, FC, LFE, BL, BR), FfmpegSpeakerLayout.positions(0x3f, 6))   // 5.1(back), AC-3
        assertEquals(listOf(FL, FR, FC, LFE, BL, BR, SL, SR), FfmpegSpeakerLayout.positions(0x63f, 8))
    }

    @Test fun heightPositionsMapToTheirOwnSpeakers() {
        val mask = 0x63fL or (1L shl 12) or (1L shl 14) or (1L shl 15) or (1L shl 17) // 7.1.4
        assertEquals(listOf(FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR), FfmpegSpeakerLayout.positions(mask, 12))
        val topSides = 0x60fL or (1L shl 36) or (1L shl 37) // 5.1.2 with top-side pair
        assertEquals(listOf(FL, FR, FC, LFE, SL, SR, TSL, TSR), FfmpegSpeakerLayout.positions(topSides, 8))
    }

    @Test fun unrepresentablePositionsAreRejectedWithTheMask() {
        val backCentre = assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0x13f, 7) }
        assertEquals(DownmixStage.LAYOUT, backCentre.stage)
        assertTrue(backCentre.message, "0x13f" in backCentre.message!! && "0x100" in backCentre.message!!)
        assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(1L shl 13, 1) } // top centre
        assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0x3fL or (1L shl 35), 7) } // LFE2
    }

    @Test fun aMissingOrMismatchedLayoutIsNeverGuessed() {
        assertTrue("0x" !in assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0, 6) }.message!!.substringBefore("channels"))
        val mismatch = assertThrows(DownmixException::class.java) { FfmpegSpeakerLayout.positions(0x3f, 8) }
        assertTrue(mismatch.message, "0x3f" in mismatch.message!! && "8 channels" in mismatch.message!!)
    }

    @Test fun midStreamDecoderChangesAreDescribed() {
        assertNull(FfmpegAudioDecoder.describeOutputChange(6, 48000, 0x60f, 6, 48000, 0x60f))
        val toStereo = FfmpegAudioDecoder.describeOutputChange(6, 48000, 0x60f, 2, 48000, 0x3)!!
        assertTrue(toStereo, "6 ch" in toStereo && "2 ch" in toStereo && "0x60f" in toStereo && "0x3" in toStereo)
        assertNotNull(FfmpegAudioDecoder.describeOutputChange(6, 48000, 0x60f, 6, 44100, 0x60f))
        assertNotNull(FfmpegAudioDecoder.describeOutputChange(6, 48000, 0x60f, 6, 48000, 0x3f)) // same count, side → back
    }
}
