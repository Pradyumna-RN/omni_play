package labs.rtensor.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import labs.rtensor.music.data.ResolvedAudio
import labs.rtensor.music.data.ArtworkUrl
import labs.rtensor.music.playback.GoogleVideoDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleVideoDataSourceTest {
    private val url = "https://r1-fixture.googlevideo.com/videoplayback?sig=a%2Bb&pot=fixture"
    private fun spec(length: Long) = DataSpec.Builder().setUri(url)
        .setCustomData(ResolvedAudio(url, "audio/mp4", emptyMap(), length)).build()
    @Test fun readsAcrossRangesWithoutEndingTheSongAfterTheFirstChunk() {
        val size = GoogleVideoDataSource.CHUNK_SIZE * 2 + 17
        val fake = RangedFixture(); val source = GoogleVideoDataSource(fake)
        assertEquals(size, source.open(spec(size)))
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
        source.close()
        val actual = out.toByteArray(); assertEquals(size.toInt(), actual.size)
        for (i in actual.indices) assertEquals((i % 251).toByte(), actual[i])
        assertEquals(listOf("0-1048575", "1048576-2097151", "2097152-2097168"), fake.requests.map { it.uri.getQueryParameter("range") })
        fake.requests.forEach { assertTrue(it.uri.encodedQuery!!.startsWith("sig=a%2Bb&pot=fixture&")); assertEquals(0L, it.position) }
    }
    @Test fun seekRequestsTheCorrectAbsoluteRangeWithoutSkippingBytesTwice() {
        val fake = RangedFixture(); val source = GoogleVideoDataSource(fake)
        source.open(spec(4_000_000).buildUpon().setPosition(1_100_000).setLength(32).build())
        val bytes = ByteArray(64); assertEquals(32, source.read(bytes, 0, 64)); assertEquals((1_100_000 % 251).toByte(), bytes[0])
        assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, 64)); source.close()
        assertEquals("1100000-1100031", fake.requests.single().uri.getQueryParameter("range"))
    }
    @Test(expected = EOFException::class) fun truncatedRangeIsAnErrorInsteadOfASuccessfulShortSong() {
        val source = GoogleVideoDataSource(RangedFixture(truncated = true)); source.open(spec(100))
        source.read(ByteArray(200), 0, 200); source.read(ByteArray(1), 0, 1)
    }
    @Test fun imageRenditionsPreserveIdentityAndOnlyResizeKnownProviderUrls() {
        assertEquals("https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj", ArtworkUrl.original("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj"))
        assertEquals("https://i.ytimg.com/vi/fixture/maxresdefault.jpg", ArtworkUrl.original("https://i.ytimg.com/vi/fixture/default.jpg"))
        val other = "https://example.test/image=w60-h60"; assertEquals(other, ArtworkUrl.original(other))
    }
    private class RangedFixture(val truncated: Boolean = false) : DataSource {
        val requests = mutableListOf<DataSpec>(); private var current = 0L; private var left = 0L
        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec
            val parts = dataSpec.uri.getQueryParameter("range")!!.split('-').map(String::toLong)
            current = parts[0]; left = parts[1] - current + 1
            if (truncated) left--
            return left
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), left).toInt()
            repeat(n) { buffer[offset + it] = ((current + it) % 251).toByte() }; current += n; left -= n; return n
        }
        override fun close() {}
        override fun getUri(): Uri? = requests.lastOrNull()?.uri
        override fun addTransferListener(listener: TransferListener) {}
    }
}
