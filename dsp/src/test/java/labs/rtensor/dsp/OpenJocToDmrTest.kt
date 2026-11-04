package labs.rtensor.dsp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import labs.rtensor.decoder.openjoc.OpenJocAudioDecoder
import labs.rtensor.decoder.openjoc.OpenJocLibrary
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteOrder

/** Real reference recording, real object decoder and real private renderer.
 * The terminal AudioSink consumes PCM without Android hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class OpenJocToDmrTest {
    @Test fun completeJocMusicPassesThroughAllThreeListeningModesWithoutSyntheticHeights() {
        if (System.getProperty("rtensor.openjoc.expectNative") == "true") assertTrue(OpenJocLibrary.isAvailable())
        else assumeTrue(OpenJocLibrary.isAvailable())
        if (System.getProperty("rtensor.dsp.expectNative") == "true") assertTrue(NativeDownmixEngine.isAvailable)
        else assumeTrue(NativeDownmixEngine.isAvailable)
        val path = System.getProperty("rtensor.openjoc.recording")
        assumeTrue("Supply an authorized reference recording", path != null)
        val encoded = File(path!!).readBytes()
        assertEquals(18226176, encoded.size) // the published 189.856 s Dolby reference fixture
        val decoder = OpenJocAudioDecoder(65536)
        val positions = FfmpegSpeakerLayout.positions(decoder.channelLayoutMask, decoder.channelCount)
        var mode = ListeningMode.PROCESSED
        var writtenFrames = 0L; var sourceFrames = 0L; var heightEnergy = 0.0
        var peak = 0f; var ended = false; var outputConfigurations = 0
        val reports = mutableListOf<DownmixReport?>()
        val modeEnergy = mutableMapOf<ListeningMode, Double>()
        val output = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                "configure" -> { val f=args!![0] as Format;assertEquals(2,f.channelCount);assertEquals(48000,f.sampleRate);outputConfigurations++;null }
                "handleBuffer" -> {
                    val pcm = args!![0] as java.nio.ByteBuffer
                    writtenFrames += pcm.remaining() / 8
                    var energy = 0.0
                    while(pcm.hasRemaining()) { val x=pcm.float;assertTrue(x.isFinite());peak=maxOf(peak,kotlin.math.abs(x));energy+=x*x }
                    modeEnergy[mode] = modeEnergy.getOrDefault(mode,0.0)+energy
                    true
                }
                "playToEndOfStream" -> { ended=true;null }
                "isEnded" -> ended
                "hasPendingData" -> false
                "getCurrentPositionUs" -> Long.MAX_VALUE
                else -> null
            }
        } as AudioSink
        val sink = DownmixAudioSink(output, { positions }, onReport = reports::add, listeningMode = { mode })
        try {
            sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(12).setSampleRate(48000)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT).build(),0,null)
            var at=0;var queuedEos=false;var done=false
            val deadline=System.nanoTime()+600_000_000_000L
            while(!done && System.nanoTime()<deadline) {
                if(!queuedEos) decoder.dequeueInputBuffer()?.let { input ->
                    input.timeUs=(at/3072)*32_000L
                    if(at==encoded.size) { input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);queuedEos=true }
                    else {
                        val n=minOf(6*3072,encoded.size-at);input.ensureSpaceForWrite(n);input.data!!.put(encoded,at,n);input.flip();at+=n
                    }
                    decoder.queueInputBuffer(input)
                }
                while(true) {
                    val out=decoder.dequeueOutputBuffer() ?: break
                    try {
                        if(out.isEndOfStream()) { done=true;continue }
                        val pcm=out.data!!.order(ByteOrder.nativeOrder())
                        val view=pcm.duplicate().order(ByteOrder.nativeOrder())
                        var channel=0
                        while(view.hasRemaining()) { val v=view.float.toDouble();if(channel>=8)heightEnergy+=v*v;channel=(channel+1)%12 }
                        mode=when(out.timeUs/32_000_000) { 1L,5L -> ListeningMode.ORIGINAL;3L -> ListeningMode.SPATIAL;else -> ListeningMode.PROCESSED }
                        sourceFrames+=pcm.remaining()/(12*4)
                        assertTrue(sink.handleBuffer(pcm,out.timeUs,1))
                    } finally { out.release() }
                }
                Thread.sleep(1)
            }
            assertTrue("The whole reference recording must finish",done)
            sink.playToEndOfStream()
            assertTrue(sink.isEnded)
            assertEquals(9113088L+32,sourceFrames) // includes the decoder's drain padding
            assertTrue(writtenFrames>=sourceFrames)
            assertTrue(heightEnergy>1.0)
            assertTrue("Every listening mode must produce finite audio",ListeningMode.entries.all { modeEnergy.getOrDefault(it,0.0)>1e-5 })
            assertTrue(peak<=1.0001f)
            assertEquals(1,outputConfigurations)
            assertTrue(reports.filterNotNull().all { it.sourceChannels==12 && !it.upmix916 })
            assertTrue(reports.filterNotNull().map { it.listeningMode }.toSet().containsAll(ListeningMode.entries))
            assertEquals(sourceFrames*1_000_000/48000,sink.getCurrentPositionUs(true))
        } finally { sink.release();decoder.release() }
    }
}
