package labs.rtensor.music.data

import android.content.Context
import android.net.Uri

/** The one store and manager shared by the screen and the playback service (same process). */
class Downloads private constructor(context: Context) {
    val store = DownloadStore(java.io.File(context.filesDir, "downloads"))
    private val youtube = YouTubeSource(context)
    @Volatile var onChange: () -> Unit = {}
    val manager = DownloadManager(store, { track, forceProof -> youtube.resolve(track, forceProof = forceProof) }, onChange = { onChange() })

    /** The saved file for [track], or null when it must be streamed. */
    fun offlineUri(track: Track): Uri? = store.file(track)?.let(Uri::fromFile)

    companion object {
        @Volatile private var shared: Downloads? = null
        fun get(context: Context): Downloads = shared ?: synchronized(this) {
            shared ?: Downloads(context.applicationContext).also { shared = it }
        }
    }
}
