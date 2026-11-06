package labs.rtensor.music.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One song saved on this device for offline playback. */
data class DownloadedSong(val track: Track, val fileName: String, val bytes: Long, val savedAt: Long) {
    fun json(): JSONObject = JSONObject().put("track", track.json()).put("file", fileName).put("bytes", bytes).put("at", savedAt)
    companion object {
        fun parse(o: JSONObject) = DownloadedSong(Track.fromJson(o.getJSONObject("track")), o.getString("file"), o.optLong("bytes"), o.optLong("at"))
    }
}

/**
 * Songs downloaded for offline playback: audio files in [directory] plus a small index. A file that
 * the index names but that has gone missing (cleared storage, user deleted it) is dropped on load,
 * so the app never promises offline audio it cannot play.
 */
class DownloadStore(private val directory: File) {
    private val index = File(directory, "index.json")
    @Volatile private var songs: Map<String, DownloadedSong> = load()

    val all: List<DownloadedSong> get() = songs.values.sortedByDescending { it.savedAt }
    val keys: Set<String> get() = songs.keys

    fun has(track: Track): Boolean = songs.containsKey(track.libraryKey())
    fun song(track: Track): DownloadedSong? = songs[track.libraryKey()]
    fun file(track: Track): File? = song(track)?.let { File(directory, it.fileName) }?.takeIf { it.isFile }

    /** The file a new download is written to; it only becomes a download once [commit] is called. */
    fun partFile(track: Track): File = File(directory.also { it.mkdirs() }, "${safeName(track)}.part")
    fun finalFile(track: Track, extension: String): File = File(directory.also { it.mkdirs() }, "${safeName(track)}.$extension")

    @Synchronized fun commit(track: Track, file: File) {
        songs = songs + (track.libraryKey() to DownloadedSong(track.copy(playlistItemId = ""), file.name, file.length(), System.currentTimeMillis()))
        persist()
    }

    @Synchronized fun remove(track: Track) {
        val entry = songs[track.libraryKey()] ?: return
        File(directory, entry.fileName).delete()
        songs = songs - track.libraryKey()
        persist()
    }

    @Synchronized fun clear() {
        songs.values.forEach { File(directory, it.fileName).delete() }
        songs = emptyMap(); persist()
    }

    fun totalBytes(): Long = songs.values.sumOf { it.bytes }

    private fun persist() {
        directory.mkdirs()
        val temp = File(directory, "index.json.tmp")
        temp.writeText(JSONArray().also { array -> songs.values.forEach { array.put(it.json()) } }.toString())
        if (!temp.renameTo(index)) { index.writeText(temp.readText()); temp.delete() }
    }

    private fun load(): Map<String, DownloadedSong> = runCatching {
        if (!index.exists()) return emptyMap()
        val array = JSONArray(index.readText())
        (0 until array.length()).mapNotNull { runCatching { DownloadedSong.parse(array.getJSONObject(it)) }.getOrNull() }
            .filter { File(directory, it.fileName).isFile }.associateBy { it.track.libraryKey() }
    }.getOrDefault(emptyMap())

    companion object {
        /** File names come from the recording identity, never from a title, so no path can escape. */
        fun safeName(track: Track): String = track.libraryKey().replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

        fun extensionFor(mimeType: String?): String = when {
            mimeType == null -> "m4a"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "m4a"
            mimeType.contains("mpeg") -> "mp3"
            mimeType.contains("ogg") -> "ogg"
            else -> "m4a"
        }
    }
}
