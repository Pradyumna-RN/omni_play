package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class NativeDownmixEngineTest {
    private val roles = listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun buffer(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    @Before fun privateLibraryIsPresent() {
        if (System.getProperty("rtensor.dsp.expectNative") == "true") assertTrue("The requested private JNI library failed to load", NativeDownmixEngine.isAvailable)
        else assumeTrue("Optional private host DSP is not supplied", NativeDownmixEngine.isAvailable)
    }
    private fun input(frames: Int) = buffer(frames * 24).apply { repeat(frames) { putFloat(.1f); putFloat(-.2f); repeat(4) { putFloat(0f) } }; flip() }
    @Test fun realJniConsumesMultichannelFloatAndProducesFiniteStereo() {
        NativeDownmixEngine(DownmixFormat(48000, roles)).use { engine ->
            assertTrue(engine.latencyFrames > 0)
            assertTrue(engine.drainFrames >= engine.latencyFrames)
            val input = input(512); val output = buffer(4096)
            engine.process(input, output, 512)
            assertEquals(input.limit(), input.position()); assertEquals(4096, output.position())
            output.flip(); val samples = FloatArray(1024) { output.float }
            assertTrue(samples.all { it.isFinite() }); assertTrue(samples.any { it != 0f })
        }
    }
    @Test fun resetReproducesAFreshEngineWithoutSharingHistory() {
        NativeDownmixEngine(DownmixFormat(48000, roles)).use { a ->
            NativeDownmixEngine(DownmixFormat(48000, roles)).use { b ->
                val first = buffer(4096); a.process(input(512), first, 512)
                a.reset(); val repeated = buffer(4096); a.process(input(512), repeated, 512)
                val independent = buffer(4096); b.process(input(512), independent, 512)
                first.flip(); repeated.flip(); independent.flip()
                assertEquals(first, repeated); assertEquals(first, independent)
            }
        }
    }
    @Test fun directBufferOffsetsAndCanariesAreRespected() {
        NativeDownmixEngine(DownmixFormat(48000, roles)).use { e ->
            val input = buffer(4 + 128 * 24).putInt(0x12345678)
            input.put(input(128)); input.flip(); input.position(4)
            val output = buffer(4 + 128 * 8 + 4).putInt(0x23456789)
            output.putInt(output.capacity() - 4, 0x3456789a)
            output.limit(output.capacity() - 4)
            e.process(input, output, 128)
            assertEquals(0x12345678, input.getInt(0)); assertEquals(0x23456789, output.getInt(0))
            output.limit(output.capacity()); assertEquals(0x3456789a, output.getInt(output.capacity() - 4))
        }
    }
    @Test fun insufficientHeapReadonlyAndOverlappingBuffersAreRejected() {
        NativeDownmixEngine(DownmixFormat(48000, roles)).use { e ->
            val input = input(128)
            assertThrows(IllegalArgumentException::class.java) { e.process(input, buffer(4), 128) }
            assertThrows(IllegalArgumentException::class.java) { e.process(ByteBuffer.allocate(3072), buffer(1024), 128) }
            assertThrows(IllegalArgumentException::class.java) { e.process(input, buffer(1024).asReadOnlyBuffer(), 128) }
            assertThrows(IllegalStateException::class.java) { e.process(input, input, 128) }
            assertEquals(0, input.position())
        }
    }
    @Test fun nonfiniteAudioIsRejectedBeforePositionsAdvance() {
        NativeDownmixEngine(DownmixFormat(48000, roles)).use { e ->
            val input = input(128).putFloat(0, Float.NaN); val output = buffer(1024)
            assertThrows(IllegalStateException::class.java) { e.process(input, output, 128) }
            assertEquals(0, input.position()); assertEquals(0, output.position())
        }
    }
    @Test fun closeIsIdempotentAndPreventsFurtherNativeCalls() {
        val e = NativeDownmixEngine(DownmixFormat(48000, roles)); e.close(); e.close()
        assertThrows(IllegalStateException::class.java) { e.reset() }
        assertThrows(IllegalStateException::class.java) { e.setCompareMode(true) }
        assertThrows(IllegalStateException::class.java) { e.process(input(128), buffer(1024), 128) }
    }
    @Test fun realJniProcessesStereoAndOriginalComparisonIsARealDifferentSignal() {
        val frames = 8192
        val format = DownmixFormat(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR), maxBlockFrames = frames)
        fun source() = buffer(frames * 8).apply {
            repeat(frames) { i -> putFloat((kotlin.math.sin(i * .057) * .1).toFloat()); putFloat((kotlin.math.sin(i * .091) * .1).toFloat()) }; flip()
        }
        NativeDownmixEngine(format).use { wet -> NativeDownmixEngine(format).use { dry ->
            dry.setCompareMode(true)
            val a = buffer(frames * 8); val b = buffer(frames * 8)
            wet.process(source(), a, frames); dry.process(source(), b, frames)
            a.flip(); b.flip(); var difference = 0f
            repeat(frames * 2) { difference = maxOf(difference, kotlin.math.abs(a.float - b.float)) }
            assertTrue("Stereo processing must change the signal, not just its label", difference > .0001f)
            wet.setCompareMode(true); wet.reset()
            val original = buffer(frames * 8); wet.process(source(), original, frames); original.flip(); b.rewind()
            assertEquals(b, original)
        } }
    }
    @Test fun processingAndCloseCannotRaceIntoFreedMemory() {
        val engine = NativeDownmixEngine(DownmixFormat(48000, roles))
        val start = CountDownLatch(1); val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = List(3) { thread {
            start.await()
            repeat(50) {
                try { engine.process(input(64), buffer(512), 64) }
                catch (closed: IllegalStateException) { if (closed.message != "DSP stream is closed") failures.add(closed) }
                catch (other: Throwable) { failures.add(other) }
            }
        } }
        start.countDown(); engine.close(); workers.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
