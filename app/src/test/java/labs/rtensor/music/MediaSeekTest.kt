package labs.rtensor.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import labs.rtensor.music.data.ResolvedAudio
import labs.rtensor.music.playback.GoogleVideoDataSource
import labs.rtensor.music.playback.PlaybackDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Real HTTP and MP4 seek-map/extractor; no provider or fake successful decoder. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaSeekTest {
    @Test fun realMp4ExtractorCanSeekForwardAndBackOverQueryRanges() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/audio/seek-fixture.m4a")).readBytes()
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val starts = CopyOnWriteArrayList<Long>()
        val server = executor.submit {
            try {
                while (!socket.isClosed) socket.accept().use { c ->
                    c.soTimeout = 5000
                    val reader = c.getInputStream().bufferedReader()
                    val line = reader.readLine()
                    while (!reader.readLine().isNullOrBlank()) { }
                    val range = Uri.parse("http://localhost" + line.split(' ')[1]).getQueryParameter("range")!!.split('-').map(String::toInt)
                    val end = minOf(range[1], bytes.lastIndex)
                    starts += range[0].toLong()
                    val size = end - range[0] + 1
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes, range[0], size); flush()
                    }
                }
            } catch (_: java.net.SocketException) { }
        }
        val http = PlaybackDataSource.httpFactory().createDataSource()
        val redirect = object : DataSource by http {
            override fun open(spec: DataSpec): Long = http.open(spec.withUri(Uri.parse("http://127.0.0.1:${socket.localPort}/audio?" + spec.uri.encodedQuery)))
        }
        val source = GoogleVideoDataSource(redirect, chunkSize = 16_384)
        val uri = "https://r1-fixture.googlevideo.com/videoplayback?sig=a%2Bb"
        val spec = DataSpec.Builder().setUri(uri).setCustomData(ResolvedAudio(uri, "audio/mp4", emptyMap(), bytes.size.toLong())).build()
        val extractor = Mp4Extractor()
        var map: SeekMap? = null
        var samples = 0; var latest = -1L
        val dummy = DummyTrackOutput()
        val output = object : TrackOutput by dummy {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                latest = timeUs; samples++
            }
        }
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        fun open(position: Long): DefaultExtractorInput {
            source.close(); source.open(spec.buildUpon().setPosition(position).build())
            return DefaultExtractorInput(source, position, bytes.size.toLong())
        }
        try {
            var input = open(0)
            val holder = PositionHolder()
            fun readUntilSample() {
                val before = samples
                repeat(1000) {
                    when (extractor.read(input, holder)) {
                        Extractor.RESULT_SEEK -> input = open(holder.position)
                        Extractor.RESULT_END_OF_INPUT -> fail("No sample after seek")
                    }
                    if (samples > before) return
                }
                fail("Extractor failed to produce a sample")
            }
            readUntilSample()
            assertTrue(checkNotNull(map).isSeekable)
            for (timeUs in listOf(8_000_000L, 2_000_000L)) {
                val point = checkNotNull(map).getSeekPoints(timeUs).first
                extractor.seek(point.position, point.timeUs)
                input = open(point.position)
                readUntilSample()
                assertTrue("Seek sample time $latest should match $timeUs", kotlin.math.abs(latest - timeUs) < 100_000)
                assertTrue(starts.contains(point.position))
            }
            assertTrue(starts.any { it > 0 })
        } finally {
            source.close(); extractor.release(); socket.close(); executor.shutdownNow()
        }
        server.cancel(true)
    }
}
