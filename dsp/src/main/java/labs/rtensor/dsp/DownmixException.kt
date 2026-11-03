package labs.rtensor.dsp

/** Where the decoder → DMR → output chain stopped. Kept as data so the app can
 * show a precise reason instead of a generic playback error. */
enum class DownmixStage(val label: String) {
    DECODER_LIBRARY("Decoder library"),
    DECODER("Decoder"),
    LAYOUT("Speaker layout"),
    DSP_LIBRARY("DSP library"),
    FORMAT("Processing format"),
    DSP("DSP processing"),
    OUTPUT("Audio output"),
}

/** An IllegalStateException so existing callers that treat DSP rejection as a
 * state failure keep working. */
class DownmixException(val stage: DownmixStage, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
