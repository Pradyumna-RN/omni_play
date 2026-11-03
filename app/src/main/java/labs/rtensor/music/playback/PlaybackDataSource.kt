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
    private var attempts = 0

    private fun retry(spec: DataSpec, error: HttpDataSource.InvalidResponseCodeException): Boolean {
        if (error.responseCode == 403 && attempts < MAX_ATTEMPTS && retryDenied(spec)) {
            upstream.close()
            return true
        }
        onHttpFailure(spec, error.responseCode)
        return false
    }
    private fun openAt(spec: DataSpec): Long {
        while (true) {
            attempts++
            try { return upstream.open(spec) }
            catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!retry(checkNotNull(originalRequest), error)) throw error
            }
        }
    }
    override fun open(dataSpec: DataSpec): Long {
        originalRequest = dataSpec; bytesRead = 0; attempts = 0
        return openAt(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val count = try { upstream.read(buffer, offset, length) }
            catch (error: HttpDataSource.InvalidResponseCodeException) {
                val original = originalRequest ?: throw error
                if (!retry(original, error)) throw error
                // Preserve the exact next unread byte across any number of bounded path changes.
                openAt(original.subrange(bytesRead))
                continue
            }
            if (count > 0) bytesRead += count
            return count
        }
    }
    // CacheDataSource persists a changed getUri() as a redirect. Exposing the signed
    // CDN URL here would bypass resolution/headers/custom range metadata on later seeks.
    override fun getUri(): android.net.Uri? = originalRequest?.uri ?: upstream.uri
    override fun close() { try { upstream.close() } finally { originalRequest = null } }

    companion object {
        const val MAX_ATTEMPTS = 7
        fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            // A factory user agent overwrites DataSpec's resolved/probed user agent.
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    }
}
