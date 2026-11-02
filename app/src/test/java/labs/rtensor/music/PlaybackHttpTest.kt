package labs.rtensor.music

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import labs.rtensor.music.playback.PlaybackDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual Media3 HTTP requests against loopback fixtures; no provider connection. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackHttpTest {
    private val providerHeaders = mapOf(
        "User-Agent" to "Fixture Android WebView browser",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )

    @Test fun mediaRequestRetainsTheHeadersThatPassedTheProviderProbe() {
        FixtureServer(200).use { server ->
            val source = PlaybackDataSource.httpFactory().createDataSource()
            try {
                val spec = DataSpec.Builder().setUri(server.url + "?signature=fixture%2Bvalue&pot=fixture-proof")
                    .setHttpRequestHeaders(providerHeaders).build()
                assertEquals(4L, source.open(spec))
                val received = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    received.write(buffer, 0, count)
                }
                assertArrayEquals(byteArrayOf(0, 1, 2, 3), received.toByteArray())
            } finally { source.close() }
            val request = server.request()
            assertEquals(providerHeaders["User-Agent"], request.headers["user-agent"])
            assertEquals(providerHeaders["Origin"], request.headers["origin"])
            assertEquals(providerHeaders["Referer"], request.headers["referer"])
            assertEquals("GET /audio?signature=fixture%2Bvalue&pot=fixture-proof HTTP/1.1", request.line)
            assertNull(request.headers["cookie"])
            assertNull(request.headers["authorization"])
        }
    }

    @Test fun rewrittenMediaFailureReportsTheOriginalCatalogueRequestAndHttpStatus() {
        FixtureServer(403).use { server ->
            val original = DataSpec.Builder().setUri("https://www.youtube.com/watch?v=fEbBLFPmdV8")
                .setKey("fixture-play-request").build()
            val resolving = ResolvingDataSource.Factory(PlaybackDataSource.httpFactory()) { spec ->
                spec.withUri(Uri.parse(server.url)).withAdditionalHeaders(providerHeaders)
            }
            var failedSpec: DataSpec? = null
            var failedStatus: Int? = null
            val source = PlaybackDataSource(resolving.createDataSource()) { spec, status ->
                failedSpec = spec; failedStatus = status
            }
            try {
                source.open(original)
                fail("The refused media response must not become successful playback.")
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                assertEquals(403, error.responseCode)
            } finally { source.close() }
            assertSame(original, failedSpec)
            assertEquals("fixture-play-request", failedSpec?.key)
            assertEquals(403, failedStatus)
            assertEquals(providerHeaders["User-Agent"], server.request().headers["user-agent"])
        }
    }

    private data class ReceivedRequest(val line: String, val headers: Map<String, String>)

    private class FixtureServer(private val status: Int) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${socket.localPort}/audio"
        private val response = executor.submit<ReceivedRequest> {
            socket.accept().use { connection ->
                connection.soTimeout = 5000
                val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = requireNotNull(reader.readLine())
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\nContent-Type: audio/mp4\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    write(byteArrayOf(0, 1, 2, 3)); flush()
                }
                ReceivedRequest(line, headers)
            }
        }
        fun request(): ReceivedRequest = response.get(10, TimeUnit.SECONDS)
        override fun close() { socket.close(); executor.shutdownNow() }
    }
}
