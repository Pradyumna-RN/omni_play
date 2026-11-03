package labs.rtensor.dsp

/** AV_CH_LAYOUT_NATIVE bit order, not Android's channel-mask bit numbers.
 * FFmpeg interleaves native-order PCM in ascending bit order, so the returned
 * list is the decoded channel order. Positions the renderer cannot represent
 * (wide, centre-back, front-of-centre, LFE2, top centre...) are rejected rather
 * than guessed. Side (bits 9/10) and back (4/5) surrounds stay distinct. */
object FfmpegSpeakerLayout {
    private val bits = linkedMapOf(
        0 to SpeakerPosition.FL, 1 to SpeakerPosition.FR, 2 to SpeakerPosition.FC,
        3 to SpeakerPosition.LFE, 4 to SpeakerPosition.BL, 5 to SpeakerPosition.BR,
        9 to SpeakerPosition.SL, 10 to SpeakerPosition.SR,
        12 to SpeakerPosition.TFL, 14 to SpeakerPosition.TFR,
        15 to SpeakerPosition.TBL, 17 to SpeakerPosition.TBR,
        36 to SpeakerPosition.TSL, 37 to SpeakerPosition.TSR,
    )
    private val known = bits.keys.fold(0L) { m, bit -> m or (1L shl bit) }

    fun positions(mask: Long, channels: Int): List<SpeakerPosition> {
        if (mask == 0L) throw DownmixException(DownmixStage.LAYOUT,
            "The decoder did not report a speaker layout for $channels channels; refusing to guess from the count")
        val unknown = mask and known.inv()
        if (unknown != 0L) throw DownmixException(DownmixStage.LAYOUT,
            "Unsupported speaker positions in decoded layout ${describe(mask)} (unmapped ${describe(unknown)})")
        if (java.lang.Long.bitCount(mask) != channels) throw DownmixException(DownmixStage.LAYOUT,
            "Decoded layout ${describe(mask)} has ${java.lang.Long.bitCount(mask)} positions but $channels channels")
        return bits.filterKeys { mask and (1L shl it) != 0L }.values.toList()
    }

    fun describe(mask: Long): String = "0x" + java.lang.Long.toHexString(mask)
}
