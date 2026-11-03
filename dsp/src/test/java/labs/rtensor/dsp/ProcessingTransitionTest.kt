package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProcessingTransitionTest {
    private fun block(value: Float, frames: Int = 512) = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder()).apply {
        repeat(frames * 2) { putFloat(value) }; flip()
    }
    @Test fun aLiveSwitchChangesModeOnlyAtSilenceAndReturnsToFullGain() {
        val transition = ProcessingTransition(48000, true)
        assertFalse(transition.prepare(false))
        val outgoing = block(.4f); transition.apply(outgoing, 512)
        assertEquals(0f, outgoing.getFloat(511 * 8), 0f)
        assertTrue(transition.prepare(false)); assertFalse(transition.processed)
        val incoming = block(-.2f); transition.apply(incoming, 512)
        assertTrue(kotlin.math.abs(incoming.getFloat(0)) < .001f)
        assertEquals(-.2f, incoming.getFloat(511 * 8), .00001f)
        assertFalse(transition.prepare(false))
    }
    @Test fun rapidReversalBeforeSilenceKeepsTheCurrentMixAndRecovers() {
        val transition = ProcessingTransition(48000, true)
        assertFalse(transition.prepare(false)); transition.apply(block(.2f, 64), 64)
        assertFalse(transition.prepare(true)); assertTrue(transition.processed)
        val recovered = block(.2f); transition.apply(recovered, 512)
        assertEquals(.2f, recovered.getFloat(511 * 8), 0f)
    }
    @Test fun stablePlaybackPreservesEverySampleAndTheBufferPosition() {
        val transition = ProcessingTransition(48000, true); val data = block(.1234567f)
        assertFalse(transition.prepare(true)); transition.apply(data, 512)
        assertEquals(0, data.position())
        repeat(1024) { assertEquals(.1234567f, data.float, 0f) }
    }
    @Test fun seekResetRestoresGainWithoutChangingTheNativeMode() {
        val transition = ProcessingTransition(48000, false)
        transition.prepare(true); transition.apply(block(.5f, 64), 64)
        transition.reset(); assertFalse(transition.processed)
        assertFalse(transition.prepare(false))
        val after = block(.5f); transition.apply(after, 512)
        assertEquals(.5f, after.float, 0f)
    }
}
