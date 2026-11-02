package labs.rtensor.music.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException

/** GVS audio uses bounded query ranges, including on the first request and after seeks. */
@UnstableApi
internal class GoogleVideoDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var original: DataSpec? = null
    private var position = 0L
    private var remaining = 0L
    private var chunkRemaining = 0L
    private var chunked = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val length = (dataSpec.customData as? labs.rtensor.music.data.ResolvedAudio)?.contentLength ?: 0
        chunked = dataSpec.uri.scheme == "https" && dataSpec.uri.host?.endsWith(".googlevideo.com") == true &&
            dataSpec.uri.path == "/videoplayback" && length > 0
        if (!chunked) return upstream.open(dataSpec)
        require(dataSpec.position <= length)
        original = dataSpec; position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) length - position else minOf(dataSpec.length, length - position)
        if (remaining > 0) openChunk()
        return remaining
    }
    private fun openChunk() {
        val spec = checkNotNull(original)
        chunkRemaining = minOf(CHUNK_SIZE, remaining)
        // Preserve the signed raw query byte-for-byte; only replace the range parameter.
        val rawParts = spec.uri.encodedQuery.orEmpty().split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "range" }
        val exactUri = spec.uri.buildUpon().encodedQuery((rawParts + "range=$position-${position + chunkRemaining - 1}").joinToString("&")).build()
        val headers = spec.httpRequestHeaders.filterKeys { !it.equals("Range", true) }
        val request = spec.buildUpon().setUri(exactUri).setPosition(0).setLength(C.LENGTH_UNSET.toLong())
            .setUriPositionOffset(0).setHttpRequestHeaders(headers).build()
        upstream.open(request); opened = true
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) { upstream.close(); opened = false; openChunk() }
        val count = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (count == C.RESULT_END_OF_INPUT) throw EOFException("Audio range ended before its declared size")
        position += count; remaining -= count; chunkRemaining -= count
        return count
    }
    override fun close() {
        try { upstream.close() }
        finally { original = null; chunked = false; opened = false; remaining = 0 }
    }
    companion object { const val CHUNK_SIZE = 1_048_576L }
}
