package labs.rtensor.dsp

import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DownmixAudioSinkTest {
    private val roles = listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun format(channels: Int = 6, encoding: Int = C.ENCODING_PCM_FLOAT, rate: Int = 48000) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(channels).setSampleRate(rate).setPcmEncoding(encoding).build()
    private fun buffer(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    private fun input(frames: Int, channels: Int = 6) = buffer(frames * channels * 4).apply {
        repeat(frames) { index -> putFloat(index / 10000f); putFloat(-index / 10000f); repeat(channels - 2) { putFloat(.01f) } }; flip()
    }
    private class RecordingOutput {
        val bytes = ByteArrayOutputStream()
        var configured: Format? = null
        var configuredBufferSize = -1
        var configurations = 0
        var blockNext = false
        var ended = false
        var floatSupported = true
        var position = AudioSink.CURRENT_POSITION_NOT_SET
        var attributes: AudioAttributes? = null
        val timestamps = mutableListOf<Long>()
        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "getFormatSupport" -> if (!floatSupported && (args!![0] as Format).pcmEncoding == C.ENCODING_PCM_FLOAT) AudioSink.SINK_FORMAT_UNSUPPORTED else AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                "configure" -> { configured = args!![0] as Format; configuredBufferSize = args[1] as Int; configurations++; ended = false; null }
                "handleBuffer" -> {
                    val b = args!![0] as ByteBuffer; timestamps.add(args[1] as Long)
                    val blocked = blockNext; blockNext = false
                    val count = if (blocked) minOf(8, b.remaining()) else b.remaining()
                    repeat(count) { bytes.write(b.get().toInt()) }
                    !blocked
                }
                "playToEndOfStream" -> { ended = true; null }
                "isEnded" -> ended
                "hasPendingData" -> false
                "getCurrentPositionUs" -> position
                "setAudioAttributes" -> { attributes = args!![0] as AudioAttributes; null }
                "getAudioAttributes" -> attributes ?: AudioAttributes.DEFAULT
                "flush", "reset" -> { bytes.reset(); ended = false; null }
                "toString" -> "RecordingOutput"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as AudioSink
    }
    private class CountingEngine(override val format: DownmixFormat, override val latencyFrames: Int = 4) : DownmixEngine {
        var processed = 0; var resets = 0; var closes = 0
        val comparisons = mutableListOf<Boolean>()
        override fun setCompareMode(original: Boolean) { comparisons += original }
        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int) {
            processed++
            repeat(frames) {
                output.putFloat(input.float); output.putFloat(input.float)
                repeat(format.inputChannels - 2) { input.float }
            }
        }
        override fun reset() { resets++ }
        override fun close() { closes++ }
    }
    private class Harness(val output: RecordingOutput = RecordingOutput()) {
        val engines = mutableListOf<CountingEngine>()
        val reports = mutableListOf<DownmixReport?>()
    }
    private fun wrapper(h: Harness, layout: (Int) -> List<SpeakerPosition> = { roles }) = DownmixAudioSink(h.output.sink, layout,
        engineFactory = { CountingEngine(it).also(h.engines::add) }, onReport = h.reports::add, processStereo = true)

    @Test fun unsupportedStereoRateBypassesWithoutChangingFormatOrBytes() {
        val h = Harness(); val sink = wrapper(h); val f = format(2, C.ENCODING_PCM_24BIT, 32000)
        sink.configure(f, 1234, null)
        val samples = ByteArray(120) { it.toByte() }; val b = buffer(120).apply { put(samples); flip() }
        assertTrue(sink.handleBuffer(b, 0, 1)); assertArrayEquals(samples, h.output.bytes.toByteArray())
        assertSame(f, h.output.configured); assertEquals(1234, h.output.configuredBufferSize)
        assertTrue(h.engines.isEmpty()); assertFalse(h.reports.single()!!.comparisonAvailable)
    }
    @Test fun defaultStereoBypassesDmrAtEverySupportedDepthAndRate() {
        for (rate in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
                val h = Harness()
                val sink = DownmixAudioSink(h.output.sink, { error("Stereo must not need a DMR layout") },
                    engineFactory = { error("Stereo must not construct DMR") }, onReport = h.reports::add)
                val f = format(2, encoding, rate)
                sink.configure(f, 321, null)
                val samples = ByteArray(120) { (it * 13).toByte() }
                assertTrue(sink.handleBuffer(buffer(120).apply { put(samples); flip() }, 1234, 1))
                assertSame(f, h.output.configured)
                assertEquals(321, h.output.configuredBufferSize)
                assertArrayEquals(samples, h.output.bytes.toByteArray())
                assertFalse(h.reports.single()!!.comparisonAvailable)
                assertEquals("Stereo bypass", h.reports.single()!!.unavailableReason)
            }
        }
    }
    @Test fun floatMultichannelIsProcessedAndDeliveredAsFloatStereo() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 64000, null)
        assertTrue(sink.handleBuffer(input(1000), 0, 1))
        assertEquals(2, h.engines.single().processed)
        assertEquals(8000, h.output.bytes.size()); assertEquals(2, h.output.configured!!.channelCount)
        assertEquals(C.ENCODING_PCM_FLOAT, h.output.configured!!.pcmEncoding)
        assertEquals(0, h.output.configuredBufferSize)
        val report = h.reports.single()!!
        assertEquals(roles, report.inputPositions); assertFalse(report.bitPerfect); assertFalse(report.atmosVerified)
    }
    @Test fun optionalLibraryStereoEnhancementRequiresExplicitOptIn() {
        val h = Harness(); val sink = wrapper(h)
        sink.configure(format(2), 0, null)
        assertTrue(sink.handleBuffer(input(1024, 2), 0, 1))
        assertEquals(2, h.engines.single().format.inputChannels)
        assertEquals(listOf(SpeakerPosition.FL, SpeakerPosition.FR), h.engines.single().format.positions)
        assertEquals(0f, h.engines.single().format.preampDb, 0f)
        assertEquals(1024 * 8, h.output.bytes.size())
        assertEquals("Stereo enhancement", h.reports.single()!!.processing)
    }
    @Test fun liveComparisonDoesNotReloadReconfigureOrReprocessBlockedAudio() {
        val h = Harness(); var processed = true
        val sink = DownmixAudioSink(h.output.sink, { roles },
            engineFactory = { CountingEngine(it).also(h.engines::add) },
            onReport = h.reports::add, processingEnabled = { processed })
        sink.configure(format(), 0, null)
        val first = input(256); h.output.blockNext = true
        assertFalse(sink.handleBuffer(first, 0, 1))
        processed = false
        assertTrue(sink.handleBuffer(first, 0, 1))
        assertEquals(1, h.engines.single().processed)
        assertTrue(sink.handleBuffer(input(512), 5333, 1))
        assertTrue(sink.handleBuffer(input(512), 16000, 1))
        val e = h.engines.single()
        assertEquals(listOf(false, true), e.comparisons)
        assertEquals(3, e.processed); assertEquals(0, e.resets); assertEquals(0, e.closes)
        assertEquals((256 + 1024) * 8, h.output.bytes.size())
        assertFalse(h.reports.last()!!.processingEnabled)
    }
    @Test fun actualJniStereoFlowsThroughTheSinkWithLiveComparisonAndSeeking() {
        if (System.getProperty("rtensor.dsp.expectNative") == "true") assertTrue(NativeDownmixEngine.isAvailable)
        else org.junit.Assume.assumeTrue(NativeDownmixEngine.isAvailable)
        for (rate in listOf(44100, 48000)) {
            val h = Harness(); var processed = true
            val sink = DownmixAudioSink(h.output.sink, { roles }, onReport = h.reports::add, processingEnabled = { processed }, processStereo = true)
            sink.configure(format(2, rate = rate), 0, null)
            assertTrue(sink.handleBuffer(input(1024, 2), 0, 1))
            processed = false
            assertTrue(sink.handleBuffer(input(1024, 2), 1024L * 1_000_000 / rate, 1))
            assertFalse(h.reports.last()!!.processingEnabled)
            sink.flush()
            assertTrue(sink.handleBuffer(input(1024, 2), 1_000_000, 1))
            sink.playToEndOfStream(); assertTrue(sink.isEnded)
            val output = ByteBuffer.wrap(h.output.bytes.toByteArray()).order(ByteOrder.nativeOrder())
            var heard = false
            while (output.hasRemaining()) { val sample = output.float; assertTrue(sample.isFinite()); heard = heard || sample != 0f }
            assertTrue(heard); sink.release()
        }
    }
    @Test fun aPausedSelectionIsAcknowledgedOnlyAfterNewAudioIsRendered() {
        val h = Harness(); var processed = false
        val sink = DownmixAudioSink(h.output.sink, { roles },
            engineFactory = { CountingEngine(it).also(h.engines::add) },
            onReport = h.reports::add, processingEnabled = { processed })
        sink.configure(format(), 0, null)
        sink.pause(); processed = true
        assertFalse(h.reports.last()!!.processingEnabled)
        assertEquals(listOf(true), h.engines.single().comparisons)
        sink.play()
        assertTrue(sink.handleBuffer(input(1024), 0, 1))
        assertTrue(h.reports.last()!!.processingEnabled)
        assertEquals(listOf(true, false), h.engines.single().comparisons)
        assertEquals(1, h.output.configurations); assertEquals(0, h.engines.single().resets)
    }
    @Test fun youtubeAacRateIsConvertedBeforeTheFixedRateCoreWithoutChangingPlaybackDuration() {
        val h = Harness(); val sink = wrapper(h)
        sink.configure(format(2, rate = 44100), 0, null)
        assertTrue(sink.handleBuffer(input(44100, 2), 0, 1))
        assertEquals(48000, h.engines.single().format.sampleRate)
        assertEquals(48000, h.output.configured!!.sampleRate)
        assertEquals(48000 * 8, h.output.bytes.size())
        assertEquals(44100, h.reports.single()!!.sampleRate)
        assertEquals(48000, h.reports.single()!!.renderSampleRate)
        h.output.position = 501541
        assertEquals(500000, sink.getCurrentPositionUs(false))
        sink.playToEndOfStream(); assertTrue(sink.isEnded)
        h.output.position = 2_000_000
        assertEquals(1_000_000, sink.getCurrentPositionUs(true))
        sink.flush(); assertTrue(sink.handleBuffer(input(44100, 2), 2_000_000, 1))
        assertEquals(48000 * 8, h.output.bytes.size())
    }
    @Test fun rateConversionDoesNotRunAgainWhenTheOutputOnlyAcceptedPartOfABuffer() {
        val h = Harness(); val sink = wrapper(h)
        sink.configure(format(2, rate = 44100), 0, null)
        val samples = input(4410, 2); h.output.blockNext = true
        assertFalse(sink.handleBuffer(samples, 0, 1))
        assertEquals(470 * 8, samples.position())
        assertTrue(sink.handleBuffer(samples, 0, 1))
        assertEquals(4800 * 8, h.output.bytes.size())
        assertEquals(0L, h.output.timestamps[0]); assertEquals(0L, h.output.timestamps[1])
    }
    @Test fun pcm24LowBitsReachTheFloatProcessor() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(6, C.ENCODING_PCM_24BIT), 0, null)
        val b = buffer(18).apply { put(byteArrayOf(1, 0, 0)); repeat(15) { put(0) }; flip() }
        assertTrue(sink.handleBuffer(b, 0, 1))
        val actual = ByteBuffer.wrap(h.output.bytes.toByteArray()).order(ByteOrder.nativeOrder())
        assertEquals(1f / 8388608f, actual.float, 0f)
    }
    @Test fun partialWritesDoNotRepeatProcessingOrSamples() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        h.output.blockNext = true; val samples = input(1024)
        assertFalse(sink.handleBuffer(samples, 1000, 1)); assertEquals(512 * 24, samples.position())
        assertEquals(1, h.engines.single().processed)
        assertTrue(sink.handleBuffer(samples, 1000, 1)); assertEquals(2, h.engines.single().processed)
        assertEquals(1024 * 8, h.output.bytes.size())
        val result = ByteBuffer.wrap(h.output.bytes.toByteArray()).order(ByteOrder.nativeOrder())
        repeat(1024) { index -> assertEquals(index / 10000f, result.float, 0f); assertEquals(-index / 10000f, result.float, 0f) }
        assertEquals(listOf(1000L, 1000L, 11666L), h.output.timestamps)
    }
    @Test fun seekDropsPendingOutputAndResetsHistory() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        h.output.blockNext = true; assertFalse(sink.handleBuffer(input(256), 0, 1))
        sink.flush(); assertEquals(1, h.engines.single().resets)
        assertTrue(sink.handleBuffer(input(128), 2_000_000, 1)); assertEquals(128 * 8, h.output.bytes.size())
    }
    @Test fun endOfStreamDrainsDeclaredLatencyExactlyOnce() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        sink.handleBuffer(input(16), 0, 1); sink.playToEndOfStream()
        assertTrue(sink.isEnded); assertEquals(20 * 8, h.output.bytes.size())
        sink.playToEndOfStream(); assertEquals(20 * 8, h.output.bytes.size())
        assertEquals(2, h.engines.single().processed)
    }
    @Test fun endOfStreamHonoursOutputBackpressure() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        sink.handleBuffer(input(16), 0, 1); h.output.blockNext = true; sink.playToEndOfStream()
        assertFalse(sink.isEnded); assertTrue(sink.hasPendingData())
        sink.playToEndOfStream(); assertTrue(sink.isEnded); assertEquals(20 * 8, h.output.bytes.size())
    }
    @Test fun rateAndLayoutChangesCloseThePreviousStream() {
        val h = Harness(); val all = roles + listOf(SpeakerPosition.SL, SpeakerPosition.SR)
        val sink = wrapper(h) { if (it == 6) roles else all }
        sink.configure(format(), 0, null); sink.configure(format(8, rate = 96000), 0, null)
        assertEquals(1, h.engines.first().closes); assertEquals(96000, h.engines.last().format.sampleRate)
        assertEquals(8, h.reports.last()!!.inputPositions.size)
    }
    @Test fun unknownPositionsAndDecoderRemappingAreExplicitErrors() {
        val h = Harness(); val unknown = wrapper(h) { error("Unknown decoded positions") }
        assertThrows(AudioSink.ConfigurationException::class.java) { unknown.configure(format(), 0, null) }
        assertTrue(h.engines.isEmpty())
        assertThrows(AudioSink.ConfigurationException::class.java) { wrapper(h).configure(format(), 0, intArrayOf(0, 1)) }
    }
    @Test fun aLayoutFailureReachesPlaybackDiagnosticsWithItsMask() {
        val h = Harness()
        val sink = wrapper(h) { FfmpegSpeakerLayout.positions(0x13f, it) }
        // An unknown speaker layout must not stop playback: the original plays and the reason is shown.
        val f = format(7); sink.configure(f, 777, null)
        assertTrue(h.engines.isEmpty()); assertSame(f, h.output.configured); assertEquals(777, h.output.configuredBufferSize)
        val report = h.reports.last()!!
        assertFalse(report.comparisonAvailable); assertFalse(report.processingEnabled); assertEquals(7, report.sourceChannels)
        assertEquals(7, report.outputChannels) // PCM passed to Android, not a claimed DMR stereo render
        assertTrue(report.unavailableReason, "0x13f" in report.unavailableReason!!)
        val samples = ByteArray(7 * 4 * 3) { it.toByte() }
        assertTrue(sink.handleBuffer(buffer(samples.size).apply { put(samples); flip() }, 0, 1))
        assertArrayEquals(samples, h.output.bytes.toByteArray())
    }
    @Test fun unsupportedRateAndFloatOutputDoNotSilentlyBecomePcm16() {
        val h = Harness(); h.output.floatSupported = false
        val sink = wrapper(h)
        assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format(), 0, null) }
        assertEquals(1, h.engines.single().closes); assertEquals(0, h.output.configurations)
        assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format(rate = 44100), 0, null) }
        assertEquals(2, h.engines.size); assertEquals(1, h.engines.last().closes); assertEquals(0, h.output.configurations)
    }
    @Test fun compressedInputCannotBypassTheDecoderAndDspStage() {
        val sink = wrapper(Harness())
        val compressed = format().buildUpon().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).build()
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(compressed))
        assertSame(AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(compressed))
    }
    @Test fun aPartialPcmFrameIsRejectedBeforeDspProcessing() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        val b = buffer(4).apply { putFloat(.5f); flip() }
        assertThrows(AudioSink.WriteException::class.java) { sink.handleBuffer(b, 0, 1) }
        assertEquals(0, b.position()); assertEquals(0, h.engines.single().processed)
    }
    @Test fun flacFallbackUsesSpecifiedChannelPositionsAndRejectsBackCenter() {
        assertEquals(roles, DownmixRenderersFactory.flacPositions(6))
        assertNull(DownmixRenderersFactory.flacPositions(7))
        assertEquals(SpeakerPosition.SR, DownmixRenderersFactory.flacPositions(8)!!.last())
    }
    @Test fun mediaClockAccountsForTheRendererDelay() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        sink.handleBuffer(input(16), 1000, 1)
        h.output.position = 1000; assertEquals(1000, sink.getCurrentPositionUs(false))
        h.output.position = 1100; assertEquals(1017, sink.getCurrentPositionUs(false))
    }
    @Test fun repeatedResetAndReleaseCloseTheEngineOnce() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(), 0, null)
        sink.reset(); sink.reset(); sink.release(); assertEquals(1, h.engines.single().closes)
    }
    @Test fun formatQueriesRejectUnsupportedPcmInsteadOfAdvertisingProcessing() {
        val sink = wrapper(Harness())
        assertTrue(sink.supportsFormat(format(8, C.ENCODING_PCM_24BIT, 192000)))
        assertTrue(sink.supportsFormat(format(rate = 44100)))
        assertTrue(sink.supportsFormat(format(8, rate = 88200)))
        // PCM the core cannot take is answered by the output, and configure plays it unprocessed.
        val h = Harness(); val bypass = wrapper(h); val pcm8 = format(8, C.ENCODING_PCM_8BIT)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, bypass.getFormatSupport(pcm8))
        bypass.configure(pcm8, 0, null)
        assertTrue(h.engines.isEmpty()); assertSame(pcm8, h.output.configured)
        assertTrue(h.reports.last()!!.unavailableReason!!, "8-bit" in h.reports.last()!!.unavailableReason!!)
    }
    @Test fun renderedStereoOptsOutOfASecondSpatializationPass() {
        val h = Harness(); val sink = wrapper(h)
        val original = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        sink.setAudioAttributes(original); sink.configure(format(), 0, null)
        assertEquals(C.SPATIALIZATION_BEHAVIOR_NEVER, h.output.attributes!!.spatializationBehavior)
        sink.configure(format(2, rate = 32000), 0, null)
        assertSame(original, h.output.attributes)
    }
    @Test fun cdRateMultichannelIsConvertedAndProcessedAt48k() {
        val h = Harness(); val sink = wrapper(h); sink.configure(format(rate = 44100), 0, null)
        assertTrue(sink.handleBuffer(input(44100), 0, 1))
        assertEquals(48000, h.engines.single().format.sampleRate); assertEquals(6, h.engines.single().format.inputChannels)
        assertEquals(48000, h.output.configured!!.sampleRate); assertEquals(2, h.output.configured!!.channelCount)
        assertEquals(48000 * 8, h.output.bytes.size())
        val report = h.reports.single()!!
        assertEquals(44100, report.sampleRate); assertEquals(48000, report.renderSampleRate)
        assertTrue(report.comparisonAvailable); assertEquals(6, report.sourceChannels)
    }
    @Test fun anUnconvertibleMultichannelRatePlaysTheOriginalWithAReason() {
        val h = Harness(); val sink = wrapper(h); val f = format(rate = 32000)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(f))
        sink.configure(f, 0, null)
        assertTrue(h.engines.isEmpty()); assertSame(f, h.output.configured)
        val report = h.reports.single()!!
        assertFalse(report.comparisonAvailable); assertTrue(report.unavailableReason!!, "32000" in report.unavailableReason!!)
    }
    @Test fun theToggleSwitchesDuringCdRateMultichannelPlayback() {
        var processed = true
        val h = Harness()
        val sink = DownmixAudioSink(h.output.sink, { roles }, engineFactory = { CountingEngine(it).also(h.engines::add) },
            onReport = h.reports::add, processingEnabled = { processed })
        sink.configure(format(rate = 44100), 0, null)
        assertTrue(sink.handleBuffer(input(4410), 0, 1))
        processed = false
        assertTrue(sink.handleBuffer(input(4410), 100_000, 1))
        assertTrue(sink.handleBuffer(input(44100), 200_000, 1))
        assertEquals(1, h.engines.size); assertEquals(1, h.output.configurations); assertEquals(0, h.engines.single().resets)
        assertEquals(listOf(false, true), h.engines.single().comparisons)
        assertFalse(h.reports.last()!!.processingEnabled)
        assertEquals((4800 * 2 + 48000) * 8, h.output.bytes.size())
    }

    @Test fun convertedMultichannelRunsThroughOriginalNativeDmrAndSwitchesBothWays() {
        if (System.getProperty("rtensor.dsp.expectNative") == "true") assertTrue(NativeDownmixEngine.isAvailable)
        else org.junit.Assume.assumeTrue(NativeDownmixEngine.isAvailable)
        for ((sourceRate, renderRate) in PcmRateAdapter.CONVERTIBLE_RATES) for (channels in listOf(6, 8)) {
            val output = RecordingOutput()
            val positions = if (channels == 6) roles else roles + listOf(SpeakerPosition.SL, SpeakerPosition.SR)
            val reports = mutableListOf<DownmixReport?>()
            var selected = true
            var engines = 0
            val sink = DownmixAudioSink(output.sink, { positions },
                engineFactory = { engines++; NativeDownmixEngine(it) }, onReport = reports::add,
                processingEnabled = { selected })
            try {
                sink.configure(format(channels, rate = sourceRate), 0, null)
                assertEquals(renderRate, output.configured!!.sampleRate)
                assertEquals(2, output.configured!!.channelCount)
                val levels = mutableListOf<Double>()
                for ((segment, processed) in listOf(true, false, true).withIndex()) {
                    selected = processed
                    val frames = sourceRate / 10
                    val pcm = buffer(frames * channels * 4).apply {
                        repeat(frames) { frame -> repeat(channels) { channel ->
                            // Centre-only content makes the original FL/FR pair silent.
                            putFloat(if (channel == 2) (kotlin.math.sin((frame + segment * frames) * .037) * .04).toFloat() else 0f)
                        } }; flip()
                    }
                    assertTrue(sink.handleBuffer(pcm, segment * 100_000L, 1))
                    assertEquals(processed, reports.last()!!.processingEnabled)
                    val bytes = output.bytes.toByteArray()
                    val tail = ByteBuffer.wrap(bytes, bytes.size - renderRate / 100 * 8, renderRate / 100 * 8)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    var energy = 0.0; val count = tail.remaining()
                    while (tail.hasRemaining()) { val sample = tail.get().toDouble(); energy += sample * sample }
                    levels += kotlin.math.sqrt(energy / count)
                }
                assertTrue("Processed must render the centre channel: $levels", levels[0] > 1e-5 && levels[2] > 1e-5)
                assertTrue("Original FL/FR must omit the centre channel: $levels", levels[1] < minOf(levels[0], levels[2]) * .001)
                assertEquals(1, engines)
                assertEquals(1, output.configurations)
                assertEquals(listOf(true, false, true), reports.filterNotNull().map { it.processingEnabled })
                assertEquals(renderRate / 10 * 3 * 8, output.bytes.size())
                val samples = ByteBuffer.wrap(output.bytes.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
                var nonzero = false
                while (samples.hasRemaining()) {
                    val sample = samples.get(); assertTrue(sample.isFinite()); nonzero = nonzero || kotlin.math.abs(sample) > 1e-8f
                }
                assertTrue("Native DMR must produce audio at $sourceRate Hz / $channels channels", nonzero)
                sink.playToEndOfStream()
                assertTrue(sink.isEnded)
                output.position = Long.MAX_VALUE
                assertEquals(300_000L, sink.getCurrentPositionUs(true)) // excludes converter/core tail
            } finally { sink.release() }
        }
    }

    /** Exposes a 16-speaker field the way the ABI 3 private core does. */
    private class FieldEngine(format: DownmixFormat) : DownmixEngine by CountingEngine(format) {
        var fields = 0; var processes = 0; var resets = 0
        override val providesField = true
        override val upmix916 = true
        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int) {
            processes++
            repeat(frames) { output.putFloat(input.float); output.putFloat(input.float); repeat(format.inputChannels - 2) { input.float } }
        }
        override fun processField(input: ByteBuffer, field: ByteBuffer, frames: Int) {
            fields++
            repeat(frames) {
                val values = FloatArray(format.inputChannels) { input.float }
                val byId = FloatArray(16)
                format.positions.forEachIndexed { index, position -> byId[position.id] = values[index] }
                byId[SpeakerPosition.TFL.id] = .02f // a generated height channel
                byId.forEach(field::putFloat)
            }
        }
        override fun reset() { resets++ }
    }
    private fun outputFloats(h: Harness): FloatArray {
        val bytes = ByteBuffer.wrap(h.output.bytes.toByteArray()).order(ByteOrder.nativeOrder())
        return FloatArray(bytes.remaining() / 4) { bytes.float }
    }

    @Test fun spatialRendersTheDecodedSpeakersForHeadphonesAndSwitchesLive() {
        var mode = ListeningMode.SPATIAL
        val h = Harness()
        val sink = DownmixAudioSink(h.output.sink, { roles }, engineFactory = { CountingEngine(it).also(h.engines::add) },
            onReport = h.reports::add, listeningMode = { mode })
        sink.configure(format(), 0, null)
        val report = h.reports.last()!!
        assertTrue(report.spatialAvailable); assertEquals(ListeningMode.SPATIAL, report.listeningMode); assertTrue(report.processingEnabled)
        assertTrue(sink.handleBuffer(input(1024), 0, 1))
        val engine = h.engines.single()
        assertEquals(0, engine.processed)
        assertEquals(listOf(false), engine.comparisons)
        val rendered = outputFloats(h)
        assertEquals(2048, rendered.size)
        assertTrue(rendered.all { it.isFinite() } && rendered.any { kotlin.math.abs(it) > 1e-4f })
        mode = ListeningMode.PROCESSED
        assertTrue(sink.handleBuffer(input(1024), 21_333, 1))
        assertTrue(sink.handleBuffer(input(1024), 42_666, 1))
        assertEquals(ListeningMode.PROCESSED, h.reports.last()!!.listeningMode)
        assertEquals("stale history after a direct headphone render", 1, engine.resets)
        assertTrue(engine.processed > 0)
        mode = ListeningMode.ORIGINAL
        assertTrue(sink.handleBuffer(input(1024), 64_000, 1)); assertTrue(sink.handleBuffer(input(1024), 85_333, 1))
        assertEquals(ListeningMode.ORIGINAL, h.reports.last()!!.listeningMode); assertFalse(h.reports.last()!!.processingEnabled)
        assertEquals(listOf(false, false, true), engine.comparisons)
        assertEquals(1, h.output.configurations)
    }

    @Test fun withTheRendererFieldTheUpmixedSpeakersFeedTheHeadphoneRender() {
        val h = Harness(); var engine: FieldEngine? = null
        val sink = DownmixAudioSink(h.output.sink, { roles }, engineFactory = { FieldEngine(it).also { e -> engine = e } },
            onReport = h.reports::add, listeningMode = { ListeningMode.SPATIAL })
        sink.configure(format(rate = 96000), 0, null)
        assertTrue(h.reports.last()!!.upmix916); assertTrue(h.reports.last()!!.spatialAvailable)
        val silent = buffer(512 * 6 * 4).apply { repeat(512 * 6) { putFloat(0f) }; flip() }
        assertTrue(sink.handleBuffer(silent, 0, 1))
        assertEquals(1, engine!!.fields); assertEquals(0, engine!!.processes)
        // Only the generated height channel carries signal, so it reached the ears.
        assertTrue(outputFloats(h).any { kotlin.math.abs(it) > 1e-4f })
        sink.flush()
        assertEquals(1, engine!!.resets)
    }

    @Test fun spatialFallsBackToProcessedWhereThereIsNothingToPlace() {
        val h = Harness()
        val sink = DownmixAudioSink(h.output.sink, { roles }, engineFactory = { CountingEngine(it).also(h.engines::add) },
            onReport = h.reports::add, processStereo = true, listeningMode = { ListeningMode.SPATIAL })
        sink.configure(format(channels = 2), 0, null)
        assertFalse(h.reports.last()!!.spatialAvailable)
        assertEquals(ListeningMode.PROCESSED, h.reports.last()!!.listeningMode)
        assertTrue(sink.handleBuffer(input(256, 2), 0, 1))
        assertEquals(1, h.engines.single().processed)
    }
}
