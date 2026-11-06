package labs.rtensor.music.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserPlaylist(val id: String, val title: String, val remote: Boolean = false,
    val contains: Boolean = false, val editable: Boolean = true, val importedFrom: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("remote", remote).put("importedFrom", importedFrom)
    companion object {
        fun parse(o: JSONObject) = UserPlaylist(o.getString("id"), o.getString("title"), o.optBoolean("remote"), o.optBoolean("contains"), o.optBoolean("editable", true), o.optString("importedFrom"))
    }
}

data class PlaylistPage(val tracks: List<Track>, val hasMore: Boolean = false, val title: String = "", val skipped: Int = 0)
data class SearchSuggestion(val text: String, val track: Track? = null)

/** Only device playlists are persisted here. YouTube's account is the source of truth for synced lists. */
class DevicePlaylists(context: Context) {
    private val prefs = context.getSharedPreferences("music_playlists", Context.MODE_PRIVATE)
    private val library = LibraryStore(context)
    private fun all(): List<JSONObject> = runCatching {
        val data = JSONArray(prefs.getString("lists", "[]"))
        (0 until data.length()).map { data.getJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun write(lists: List<JSONObject>) { check(prefs.edit().putString("lists", JSONArray(lists).toString()).commit()) { "The playlist could not be saved on this device." } }
    @Synchronized fun lists(selected: Track? = null): List<UserPlaylist> = all().map { o ->
        UserPlaylist.parse(o).copy(contains = selected != null && songs(o).any { it.libraryKey() == selected.libraryKey() })
    }
    private fun songs(o: JSONObject): List<Track> {
        val tracks = o.optJSONArray("tracks") ?: JSONArray()
        return (0 until tracks.length()).mapNotNull { runCatching { Track.fromJson(tracks.getJSONObject(it)) }.getOrNull() }.filterNot(library::isRemoved)
    }
    @Synchronized fun tracks(id: String) = all().firstOrNull { it.optString("id") == id }?.let(::songs).orEmpty()
    @Synchronized fun create(title: String, tracks: List<Track>): UserPlaylist {
        require(title.trim().length in 1..150) { "Enter a playlist name of 1–150 characters." }
        val list = UserPlaylist("device:${UUID.randomUUID()}", title.trim())
        val songs = tracks.filterNot(library::isRemoved).distinctBy(Track::libraryKey)
        write(all() + list.json().put("tracks", JSONArray(songs.map { it.json() })))
        return list
    }
    /** One atomic write; imported occurrence order and repeats are kept. Originals are never edited. */
    @Synchronized fun importCopies(previews: List<labs.rtensor.music.imports.PlaylistImportPreview>, origin: String): List<UserPlaylist> {
        require(previews.isNotEmpty() && previews.sumOf { it.tracks.size } <= 5000) { "Import at most 5000 songs at a time." }
        require(previews.all { it.title.trim().length in 1..150 && it.tracks.isNotEmpty() }) { "Choose a non-empty playlist with a valid name." }
        check(previews.all { it.tracks.none(library::isRemoved) }) { "A recording was removed while importing. Review the import again." }
        val result = previews.map { UserPlaylist("device:${UUID.randomUUID()}", it.title.trim(), importedFrom = origin.take(100)) }
        val added = previews.zip(result).map { (preview, playlist) -> playlist.json().put("tracks", JSONArray(preview.tracks.map { it.copy(playlistItemId = "").json() })) }
        val combined = all() + added
        require(JSONArray(combined).toString().length <= 8 * 1024 * 1024) { "The device playlist library is too large for this import." }
        write(combined)
        return result
    }
    @Synchronized fun add(id: String, track: Track) {
        require(!library.isRemoved(track)) { "This recording was removed from Music." }
        update(id) { o -> o.put("tracks", JSONArray((songs(o).let { existing -> if (existing.any { it.libraryKey() == track.libraryKey() }) existing else existing + track }).map { it.json() })) }
    }
    @Synchronized fun remove(id: String, index: Int, expected: String) = update(id) { o ->
        check(songs(o).getOrNull(index)?.libraryKey() == expected) { "The playlist changed. Refresh it before removing a song." }
        o.put("tracks", JSONArray(songs(o).filterIndexed { i, _ -> i != index }.map { it.json() }))
    }
    @Synchronized fun rename(id: String, title: String) {
        require(title.trim().length in 1..150) { "Enter a playlist name of 1–150 characters." }
        update(id) { it.put("title", title.trim()) }
    }
    @Synchronized fun delete(id: String) { write(all().filterNot { it.optString("id") == id }) }
    private fun update(id: String, edit: (JSONObject) -> Unit) {
        val lists = all(); val list = lists.firstOrNull { it.optString("id") == id } ?: error("This playlist no longer exists.")
        edit(list); write(lists)
    }
}

/** All remote mutations require a user action and await confirmation before reporting success. */
class YouTubePlaylists(private val call: suspend (String, JSONObject) -> JSONObject) {
    private val mutation = Mutex()
    suspend fun lists(videoId: String? = null): List<UserPlaylist> {
        val result = call("playlists", JSONObject().apply { videoId?.let { put("videoId", it) } }).getJSONArray("playlists")
        return (0 until result.length()).map { UserPlaylist.parse(result.getJSONObject(it)) }
    }
    suspend fun create(name: String, ids: List<String>): UserPlaylist = mutation.withLock {
        UserPlaylist.parse(call("playlist-create", JSONObject().put("title", name).put("videoIds", JSONArray(ids))).getJSONObject("playlist"))
    }
    suspend fun tracks(id: String, more: Boolean = false, importSession: String? = null): PlaylistPage {
        val data = call("playlist-tracks", JSONObject().put("id", id).put("more", more).apply { importSession?.let { put("importSession", it) } })
        val tracks = data.getJSONArray("tracks")
        return PlaylistPage((0 until tracks.length()).map { Track.fromJson(tracks.getJSONObject(it)) }, data.optBoolean("hasMore"), data.optString("title"), data.optInt("skipped"))
    }
    suspend fun add(id: String, videoId: String) = change("playlist-add", id, JSONObject().put("videoId", videoId))
    suspend fun remove(id: String, itemId: String) = change("playlist-remove", id, JSONObject().put("itemId", itemId))
    suspend fun rename(id: String, title: String) = change("playlist-rename", id, JSONObject().put("title", title))
    suspend fun delete(id: String) = change("playlist-delete", id)
    private suspend fun change(method: String, id: String, args: JSONObject = JSONObject()) = mutation.withLock {
        check(call(method, args.put("id", id)).optBoolean("success")) { "YouTube did not confirm the playlist change. Refresh before retrying." }
    }
    suspend fun rate(videoId: String, rating: String) = mutation.withLock {
        check(call("rate-song", JSONObject().put("videoId", videoId).put("rating", rating)).optBoolean("success")) { "YouTube did not confirm the rating." }
    }
}
