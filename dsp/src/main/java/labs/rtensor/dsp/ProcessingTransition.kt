package labs.rtensor.dsp

import java.nio.ByteBuffer

/** A short fade through silence changes the existing native mix without clicks,
 * a second decoder, a source reload or resetting the filter/delay history. */
internal class ProcessingTransition(sampleRate: Int, initialProcessed: Boolean) {
    var processed = initialProcessed
        private set
    private var gain = 1f
    private var targetGain = 1f
    private val step = 1f / (sampleRate / 100).coerceAtLeast(1)

    fun prepare(requested: Boolean): Boolean {
        var changed = false
        if (requested != processed && gain == 0f) {
            processed = requested
            changed = true
        }
        targetGain = if (requested == processed) 1f else 0f
        return changed
    }

    fun apply(buffer: ByteBuffer, frames: Int) {
        if (gain == 1f && targetGain == 1f) return
        val start = buffer.position()
        repeat(frames) { frame ->
            gain = if (gain < targetGain) (gain + step).coerceAtMost(targetGain)
                else (gain - step).coerceAtLeast(targetGain)
            val offset = start + frame * 8
            buffer.putFloat(offset, buffer.getFloat(offset) * gain)
            buffer.putFloat(offset + 4, buffer.getFloat(offset + 4) * gain)
        }
    }

    fun reset() { gain = 1f; targetGain = 1f }
}
