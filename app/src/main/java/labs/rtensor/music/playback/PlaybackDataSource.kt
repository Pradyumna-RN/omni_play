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
    private val onHttpFailure: (DataSpec, Int) -> Unit,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (error: HttpDataSource.InvalidResponseCodeException) {
        onHttpFailure(dataSpec, error.responseCode)
        throw error
    }

    companion object {
        fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            // A factory user agent overwrites DataSpec's resolved/probed user agent.
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    }
}
