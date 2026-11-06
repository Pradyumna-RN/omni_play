package labs.rtensor.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the UI shows for downloads in flight: progress 0..1 per song (-1 while waiting), and the last failure. */
data class DownloadProgress(val active: Map<String, Float> = emptyMap(), val failed: Map<String, String> = emptyMap())

/**
 * Downloads YouTube recordings through the same resolver playback uses. Two songs at a time, each in
 * bounded byte ranges (Google's video servers refuse open-ended ones). A song only becomes a download
 * once every byte has arrived: partial files are never played.
 */
class DownloadManager(private val store: DownloadStore, private val resolve: suspend (Track, Boolean) -> ResolvedAudio,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onChange: () -> Unit = {}) {
    private val state = MutableStateFlow(DownloadProgress())
    private val jobs = HashMap<String, Job>()
    private val gate = Semaphore(2)
    val progress: StateFlow<DownloadProgress> = state

    @Synchronized fun enqueue(tracks: Collection<Track>) {
        for (track in tracks) {
            val key = track.libraryKey()
            if (track.source != Source.YOUTUBE || store.has(track) || jobs.containsKey(key)) continue
            state.update { it.copy(active = it.active + (key to -1f), failed = it.failed - key) }
            jobs[key] = scope.launch { gate.withPermit { run(track, key) } }
        }
    }

    @Synchronized fun cancel(track: Track) {
        val key = track.libraryKey()
        jobs.remove(key)?.cancel()
        state.update { it.copy(active = it.active - key) }
        store.partFile(track).delete()
    }

    @Synchronized fun cancelAll() {
        jobs.values.forEach { it.cancel() }; jobs.clear()
        state.update { it.copy(active = emptyMap()) }
    }

    fun remove(track: Track) { cancel(track); store.remove(track); onChange() }

    private suspend fun run(track: Track, key: String) {
        try {
            var failure: Exception? = null
            for (attempt in 0..1) {
                try { fetch(track, key, forceProof = attempt > 0); failure = null; break }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e; store.partFile(track).delete() }
            }
            failure?.let { error -> state.update { it.copy(failed = it.failed + (key to (error.message ?: "Download failed"))) } }
        } finally {
            synchronized(this) { jobs.remove(key) }
            state.update { it.copy(active = it.active - key) }
            onChange()
        }
    }

    private suspend fun fetch(track: Track, key: String, forceProof: Boolean) {
        val audio = resolve(track, forceProof)
        val part = store.partFile(track)
        part.delete()
        val total = if (audio.contentLength > 0) audio.contentLength else probeLength(audio)
        withContext(Dispatchers.IO) {
            part.outputStream().buffered().use { out ->
                if (total > 0 && isGoogleVideo(audio.url)) {
                    var position = 0L
                    while (position < total) {
                        val end = minOf(position + CHUNK, total) - 1
                        copyRange(audio, position, end, out)
                        position = end + 1
                        state.update { it.copy(active = it.active + (key to (position.toFloat() / total).coerceAtMost(.99f))) }
                    }
                } else {
                    copyAll(audio, out) { read -> if (total > 0) state.update { it.copy(active = it.active + (key to (read.toFloat() / total).coerceAtMost(.99f))) } }
                }
            }
        }
        if (total > 0 && part.length() != total) throw IOException("The download ended early.")
        if (part.length() == 0L) throw IOException("The download was empty.")
        val final = store.finalFile(track, DownloadStore.extensionFor(audio.mimeType))
        final.delete()
        if (!part.renameTo(final)) { part.copyTo(final, overwrite = true); part.delete() }
        store.commit(track, final)
    }

    private fun request(audio: ResolvedAudio, url: String) = Request.Builder().url(url).apply {
        audio.headers.forEach { (name, value) -> header(name, value) }
    }

    private suspend fun copyRange(audio: ResolvedAudio, start: Long, end: Long, out: java.io.OutputStream) {
        // Keep the signed query byte-for-byte; only the range parameter is replaced.
        val base = audio.url.substringBefore('?')
        val query = audio.url.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "range" }
        val url = base + "?" + (query + "range=$start-$end").joinToString("&")
        client.newCall(request(audio, url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The server refused the download (${response.code}).")
            val body = response.body ?: throw IOException("The server sent nothing.")
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (!scope.isActive) throw CancellationException()
                    out.write(buffer, 0, count)
                }
            }
        }
    }

    private fun copyAll(audio: ResolvedAudio, out: java.io.OutputStream, progress: (Long) -> Unit) {
        client.newCall(request(audio, audio.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The server refused the download (${response.code}).")
            val body = response.body ?: throw IOException("The server sent nothing.")
            val buffer = ByteArray(64 * 1024); var read = 0L
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count); read += count; progress(read)
                }
            }
        }
    }

    private fun probeLength(audio: ResolvedAudio): Long = runCatching {
        client.newCall(request(audio, audio.url).head().build()).execute().use { it.header("Content-Length")?.toLongOrNull() ?: 0L }
    }.getOrDefault(0L)

    private fun isGoogleVideo(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host?.endsWith(".googlevideo.com") == true && uri.path == "/videoplayback"
    }.getOrDefault(false)

    companion object { const val CHUNK = 1_048_576L }
}
