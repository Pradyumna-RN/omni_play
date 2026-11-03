package labs.rtensor.music

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import labs.rtensor.music.data.ResolvedAudio
import labs.rtensor.music.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Full synthetic audio files over real HTTP, the production transport/cache and MP4 extractor. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullTrackPlaybackTest {
    @Test fun threeMinuteTrack() = verifyTrack(180)
    @Test fun threeAndAHalfMinuteTrack() = verifyTrack(210)
    @Test fun fourMinuteTrack() = verifyTrack(240)

    private data class Request(val start: Long, val path: String, val status: Int, val agent: String?)
    private fun verifyTrack(seconds: Int) {
        val expected = checkNotNull(javaClass.getResourceAsStream("/audio/full-$seconds.m4a")).readBytes()
        assertTrue("Must cross at least two real one-MiB boundaries", expected.size > 2 * GoogleVideoDataSource.CHUNK_SIZE)
        val requests = CopyOnWriteArrayList<Request>()
        val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        executor.submit {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.net.SocketException) { break }
                executor.submit {
                    connection.use { c ->
                        try {
                            c.soTimeout = 5000
                            val reader = c.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@submit
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isBlank()) break
                                headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
                            }
                            val uri = Uri.parse("http://localhost" + line.split(' ')[1])
                            val range = uri.getQueryParameter("range")!!.split('-').map(String::toInt)
                            val path = uri.getQueryParameter("client")!!
                            val refused = path == "VISIONOS_MUSIC" || (path == "VISIONOS" && range[0] >= GoogleVideoDataSource.CHUNK_SIZE)
                            val status = if (refused) 403 else 200
                            requests += Request(range[0].toLong(), path, status, headers["user-agent"])
                            assertFalse("Query-range requests must not also send a Range header", headers.containsKey("range"))
                            assertTrue(line.contains("signature=a%2Bb"))
                            val end = minOf(range[1], expected.lastIndex)
                            val size = if (refused) 0 else end - range[0] + 1
                            c.getOutputStream().apply {
                                write("HTTP/1.1 $status ${if (refused) "Forbidden" else "OK"}\r\nContent-Type: audio/mp4\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                                if (!refused) write(expected, range[0], size)
                                flush()
                            }
                        } catch (_: java.io.IOException) { /* Extractor seeks can close a response early. */ }
                    }
                }
            }
        }
        val cacheDir = File.createTempFile("full-audio-cache", "").apply { delete(); mkdirs() }
        val cache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(16L * 1024 * 1024))
        val paths = PlaybackPaths()
        val key = "track-$seconds"
        val catalogue = Uri.parse("https://www.youtube.com/watch?v=zAiIgYOH4Ys")
        val http = PlaybackDataSource.httpFactory().createDataSource()
        val redirect = object : DataSource by http {
            override fun open(spec: DataSpec): Long = http.open(spec.withUri(Uri.parse("http://127.0.0.1:${socket.localPort}/audio?${spec.uri.encodedQuery}")))
        }
        val resolving = ResolvingDataSource.Factory(DataSource.Factory { GoogleVideoDataSource(redirect) }) { spec ->
            assertEquals("Cache reopens must keep the catalogue URI", catalogue, spec.uri)
            val path = PlaybackPaths.ALL.first { it !in paths.rejected(checkNotNull(spec.key)) }
            paths.record(checkNotNull(spec.key), path)
            val url = "https://r1-fixture.googlevideo.com/videoplayback?signature=a%2Bb&client=$path"
            spec.withUri(Uri.parse(url)).withAdditionalHeaders(mapOf("User-Agent" to "fixture-$path"))
                .buildUpon().setCustomData(ResolvedAudio(url, "audio/mp4", emptyMap(), expected.size.toLong(), path)).build()
        }
        val data = DataSource.Factory {
            PlaybackDataSource(resolving.createDataSource(), retryDenied = { spec -> assertEquals(catalogue, spec.uri); paths.rejectCurrent(checkNotNull(spec.key)) }) { _, status -> fail("Should recover from fixture HTTP $status") }
        }
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(data).createDataSource()
        val spec = DataSpec.Builder().setUri(catalogue).setKey(key).build()
        val report = File("build/reports/full-track-audio/full-$seconds.m4a").apply { parentFile!!.mkdirs() }
        val extractor = Mp4Extractor()
        try {
            assertEquals(expected.size.toLong(), source.open(spec))
            assertEquals(catalogue, source.uri)
            report.outputStream().use { out ->
                val buffer = ByteArray(32768)
                while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
            }
            source.close()
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), MessageDigest.getInstance("SHA-256").digest(report.readBytes()))
            assertEquals(setOf("VISIONOS", "VISIONOS_MUSIC"), paths.rejected(key))
            val failures = requests.filter { it.status == 403 }
            assertEquals(2, failures.size)
            assertTrue(failures.all { it.start == GoogleVideoDataSource.CHUNK_SIZE })
            assertTrue(requests.all { it.agent == "fixture-${it.path}" })
            val networkCount = requests.size
            // Cached seeks retain exact bytes without opening the network again.
            for (position in listOf(1_200_000L, 16L, expected.size - 64L)) {
                source.open(spec.buildUpon().setPosition(position).setLength(32).build())
                val chunk = ByteArray(32); var read = 0
                while (read < chunk.size) { val n = source.read(chunk, read, chunk.size - read); assertTrue(n > 0); read += n }
                source.close()
                assertArrayEquals(expected.copyOfRange(position.toInt(), position.toInt() + 32), chunk)
            }
            assertEquals("Cached seeks should not repeat HTTP requests", networkCount, requests.size)

            // Start a fresh queue item, cache only its header, then seek outside that
            // cache. The reopened request must still be resolved from the catalogue URI.
            val fresh = spec.buildUpon().setKey("$key-uncached").build()
            source.open(fresh); assertTrue(source.read(ByteArray(512), 0, 512) > 0); source.close()
            val position = expected.size * 3L / 4
            source.open(fresh.buildUpon().setPosition(position).setLength(32).build())
            val tail = ByteArray(32); var filled = 0
            while (filled < 32) { val n = source.read(tail, filled, 32 - filled); assertTrue(n > 0); filled += n }
            source.close()
            assertArrayEquals(expected.copyOfRange(position.toInt(), position.toInt() + 32), tail)
            assertEquals(setOf("VISIONOS", "VISIONOS_MUSIC"), paths.rejected("$key-uncached"))

            var map: SeekMap? = null; var samples = 0; var latest = -1L
            val dummy = DummyTrackOutput()
            val output = object : TrackOutput by dummy {
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++; latest = timeUs }
            }
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = output
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) { map = seekMap }
            })
            fun open(position: Long): DefaultExtractorInput {
                source.close(); source.open(spec.buildUpon().setPosition(position).build())
                return DefaultExtractorInput(source, position, expected.size.toLong())
            }
            var input = open(0)
            val holder = PositionHolder()
            var ended = false
            repeat(seconds * 100) {
                if (!ended) when (extractor.read(input, holder)) {
                    Extractor.RESULT_SEEK -> input = open(holder.position)
                    Extractor.RESULT_END_OF_INPUT -> ended = true
                }
            }
            assertTrue("Extractor must reach actual EOF", ended)
            assertTrue(samples > seconds * 20)
            assertTrue("Last sample must reach the complete track", latest >= seconds * 1_000_000L - 100_000)
            assertTrue(checkNotNull(map).isSeekable)
            for (time in listOf(seconds * 2_000_000L / 3, 2_000_000L)) {
                val point = checkNotNull(map).getSeekPoints(time).first
                extractor.seek(point.position, point.timeUs); input = open(point.position)
                val before = samples
                var found = false
                repeat(200) {
                    if (!found) {
                        when (extractor.read(input, holder)) {
                            Extractor.RESULT_SEEK -> input = open(holder.position)
                            Extractor.RESULT_END_OF_INPUT -> fail("Seek unexpectedly ended the recording")
                        }
                        if (samples > before) found = true
                    }
                }
                assertTrue(found); assertTrue(kotlin.math.abs(latest - time) < 100_000)
            }
            println("FULL_TRACK duration=${seconds}s bytes=${expected.size} samples=$samples httpRequests=${requests.size} mediaFailures=${failures.size} exactBytes=true eof=true cachedSeeks=true")
        } finally {
            source.close(); extractor.release(); cache.release(); cacheDir.deleteRecursively()
            socket.close(); executor.shutdownNow()
        }
    }
}
