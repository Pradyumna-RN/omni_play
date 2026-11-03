package labs.rtensor.music

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import labs.rtensor.music.playback.PlaybackDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackRetryTest {
    private val request = DataSpec.Builder().setUri("https://www.youtube.com/watch?v=i1o1p_DD6TU").setKey("fixture").build()
    @Test fun deniedStreamCanReopenOnceAfterRequestingVerifiedResolution() {
        val upstream = DeniedFixture(false); var forced = false; var reported = 0
        val source = PlaybackDataSource(upstream, retryDenied = { assertSame(request, it); forced = true; true }) { _, _ -> reported++ }
        assertEquals(100L, source.open(request)); assertTrue(forced); assertEquals(2, upstream.opens); assertEquals(1, upstream.closes); assertEquals(0, reported)
    }
    @Test fun repeated403IsReportedAfterOneRetryWithoutAnEndlessLoop() {
        val upstream = DeniedFixture(true); var reported: Int? = null; var retries = 0
        val source = PlaybackDataSource(upstream, retryDenied = { retries++ == 0 }) { original, status -> assertSame(request, original); reported = status }
        try { source.open(request); fail("A rejected retry must not be accepted") } catch (_: HttpDataSource.InvalidResponseCodeException) {}
        assertEquals(2, upstream.opens); assertEquals(403, reported)
    }
    @Test fun laterRangeFailurePreservesTheCatalogueIdentity() {
        var original: DataSpec? = null; var status: Int? = null
        val upstream = object : DataSource {
            override fun open(dataSpec: DataSpec) = 100L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), request, byteArrayOf())
            override fun close() {}
            override fun getUri(): Uri? = null
            override fun addTransferListener(listener: TransferListener) {}
        }
        val source = PlaybackDataSource(upstream) { spec, code -> original = spec; status = code }
        source.open(request)
        try { source.read(ByteArray(1), 0, 1); fail("Must preserve the HTTP failure") } catch (_: HttpDataSource.InvalidResponseCodeException) {}
        assertSame(request, original); assertEquals(403, status)
    }
    @Test fun deniedRangeReadRefreshesAndResumesAtTheExactSeekedByte() {
        val original = request.buildUpon().setPosition(4000).setLength(20).build()
        val opens = mutableListOf<DataSpec>(); var position = 0L; var deny = false; var refreshed = false
        val upstream = object : DataSource {
            override fun open(spec: DataSpec): Long { opens += spec; position = spec.position; return spec.length }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (deny && !refreshed) throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), original, byteArrayOf())
                repeat(length) { buffer[offset + it] = ((position + it) % 251).toByte() }; position += length; deny = true; return length
            }
            override fun close() {}
            override fun getUri(): Uri? = null
            override fun addTransferListener(listener: TransferListener) {}
        }
        val source = PlaybackDataSource(upstream, retryDenied = { assertSame(original, it); refreshed = true; true }) { _, _ -> fail("Successful recovery must not report a fatal HTTP error") }
        source.open(original)
        val bytes = ByteArray(8)
        source.read(bytes, 0, 3); source.read(bytes, 3, 5)
        assertEquals(listOf(4000L, 4003L), opens.map { it.position })
        assertEquals(17L, opens.last().length)
        assertEquals(original.key, opens.last().key)
        repeat(8) { assertEquals(((4000 + it) % 251).toByte(), bytes[it]) }
        source.close()
    }

    @Test fun repeatedPathFailuresHaveAFiniteSafetyBudget() {
        val upstream = DeniedFixture(true)
        val source = PlaybackDataSource(upstream, retryDenied = { true }) { _, code -> assertEquals(403, code) }
        try { source.open(request); fail("Rejected media is never successful playback") }
        catch (_: HttpDataSource.InvalidResponseCodeException) {}
        assertEquals(PlaybackDataSource.MAX_ATTEMPTS, upstream.opens)
    }
    private class DeniedFixture(val always: Boolean) : DataSource {
        var opens = 0; var closes = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            if (always || opens == 1) throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, byteArrayOf())
            return 100
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun close() { closes++ }
        override fun getUri(): Uri? = null
        override fun addTransferListener(listener: TransferListener) {}
    }
}
