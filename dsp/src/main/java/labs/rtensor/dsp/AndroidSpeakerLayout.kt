package labs.rtensor.dsp

/** Android output-position mask order is ascending bit order. Unknown positions
 * are rejected; six channels alone cannot distinguish back from side surrounds. */
object AndroidSpeakerLayout {
    private val positions = linkedMapOf(
        4 to SpeakerPosition.FL, 8 to SpeakerPosition.FR,
        16 to SpeakerPosition.FC, 32 to SpeakerPosition.LFE,
        64 to SpeakerPosition.BL, 128 to SpeakerPosition.BR,
        2048 to SpeakerPosition.SL, 4096 to SpeakerPosition.SR,
        16384 to SpeakerPosition.TFL, 65536 to SpeakerPosition.TFR,
        131072 to SpeakerPosition.TBL, 524288 to SpeakerPosition.TBR,
        1048576 to SpeakerPosition.TSL, 2097152 to SpeakerPosition.TSR,
    )
    private val knownMask = positions.keys.fold(0) { mask, bit -> mask or bit }
    fun fromMask(mask: Int, channelCount: Int): List<SpeakerPosition> {
        require(mask != 0 && mask and knownMask.inv() == 0) { "The decoder reported an unknown speaker mask: $mask" }
        require(Integer.bitCount(mask) == channelCount) { "Decoder channel count and speaker mask disagree" }
        return positions.filterKeys { mask and it != 0 }.values.toList()
    }
}
