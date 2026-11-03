package labs.rtensor.dsp

/** AV_CH_LAYOUT_NATIVE bit order, not Android's channel-mask bit numbers. */
object FfmpegSpeakerLayout {
    private val bits = linkedMapOf(
        0 to SpeakerPosition.FL, 1 to SpeakerPosition.FR, 2 to SpeakerPosition.FC,
        3 to SpeakerPosition.LFE, 4 to SpeakerPosition.BL, 5 to SpeakerPosition.BR,
        9 to SpeakerPosition.SL, 10 to SpeakerPosition.SR,
    )
    fun positions(mask: Long, channels: Int): List<SpeakerPosition> {
        val known = bits.keys.fold(0L) { m, bit -> m or (1L shl bit) }
        require(mask > 0 && mask and known.inv() == 0L && java.lang.Long.bitCount(mask) == channels) {
            "FFmpeg did not expose a supported speaker layout"
        }
        return bits.filterKeys { mask and (1L shl it) != 0L }.values.toList()
    }
}
