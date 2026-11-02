package labs.rtensor.music.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/** Reports the original catalogue request before MediaController serializes its error. */
@UnstableApi
internal class PlaybackDataSource(
    private val upstream: DataSource,
    private val retryDenied: (DataSpec) -> Boolean = { false },
    private val onHttpFailure: (DataSpec, Int) -> Unit,
) : DataSource by upstream {
    private var originalRequest: DataSpec? = null
    private var bytesRead = 0L
    override fun open(dataSpec: DataSpec): Long {
        bytesRead = 0
        originalRequest = dataSpec
        try { return upstream.open(dataSpec) }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode == 403 && retryDenied(dataSpec)) {
                upstream.close()
                try { return upstream.open(dataSpec) }
                catch (retry: HttpDataSource.InvalidResponseCodeException) { onHttpFailure(dataSpec, retry.responseCode); throw retry }
            }
            onHttpFailure(dataSpec, error.responseCode); throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = try { upstream.read(buffer, offset, length) }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            val original = originalRequest ?: throw error
            if (error.responseCode == 403 && retryDenied(original)) {
                upstream.close()
                try {
                    // Resume from the byte after the last delivered byte, including after a seek.
                    upstream.open(original.subrange(bytesRead))
                    upstream.read(buffer, offset, length)
                } catch (retry: HttpDataSource.InvalidResponseCodeException) {
                    onHttpFailure(original, retry.responseCode); throw retry
                }
            } else { onHttpFailure(original, error.responseCode); throw error }
        }
        if (count > 0) bytesRead += count
        return count
    }
    override fun close() { try { upstream.close() } finally { originalRequest = null } }

    companion object {
        fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            // A factory user agent overwrites DataSpec's resolved/probed user agent.
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    }
}
