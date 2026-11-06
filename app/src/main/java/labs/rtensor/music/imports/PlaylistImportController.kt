package labs.rtensor.music.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import labs.rtensor.music.data.*
import labs.rtensor.music.spotify.SpotifyRecommendations
import org.json.JSONObject
import java.util.UUID
import java.text.Normalizer

internal class PlaylistImportController(private val context: Context, private val scope: CoroutineScope,
    private val device: DevicePlaylists, private val youtube: YouTubeSource, private val remote: YouTubePlaylists,
    private val spotify: SpotifyRecommendations, private val connected: () -> Boolean,
    private val publish: (PlaylistImportState) -> Unit, private val saved: (String) -> Unit) {
    private var state = PlaylistImportState()
    private var job: Job? = null
    private var epoch = 0
    private var documents: List<ImportDocument> = emptyList()
    private var origin = "Playlist import"
    private val library = LibraryStore(context)
    private fun update(next: PlaylistImportState) { state = next; publish(next) }
    fun show() { if (state.saving) return; job?.cancel(); epoch++; documents = emptyList(); update(PlaylistImportState(visible = true)) }
    fun dismiss() { if (state.saving) return; job?.cancel(); epoch++; documents = emptyList(); update(PlaylistImportState()) }
    fun accountChanged() { if (state.saving) return; job?.cancel(); epoch++; documents = emptyList(); update(PlaylistImportState(visible = true, error = "The account changed. Choose the playlist again.")) }
    private fun work(action: suspend () -> Unit) {
        if (state.saving) return
        job?.cancel(); val ticket = ++epoch
        update(state.copy(visible = true, busy = true, error = null))
        job = scope.launch {
            try { withTimeout(300_000) { action() }; if (ticket == epoch) update(state.copy(busy = false)) }
            catch (e: CancellationException) { if (e is TimeoutCancellationException && ticket == epoch) update(state.copy(busy = false, error = "The import timed out. Your original playlists are unchanged. Retry with a smaller playlist.")) else throw e }
            catch (e: Exception) { if (ticket == epoch) update(state.copy(busy = false, error = safeMessage(e))) }
        }
    }
    fun account(provider: String) = work {
        documents = emptyList(); origin = provider
        val choices = when (provider) {
            "YouTube Music" -> {
                check(connected()) { "Connect YouTube Music in Settings before importing account playlists. Public playlist links can also be imported." }
                remote.lists().map { PlaylistImportChoice(it.id, it.title, provider) }
            }
            "Spotify" -> {
                val result = mutableListOf<PlaylistImportChoice>(); var offset = 0
                do {
                    val page = spotify.importPlaylists(offset); val entries = page.getJSONArray("items")
                    for (i in 0 until entries.length()) {
                        val p = entries.getJSONObject(i)
                        result += PlaylistImportChoice("spotify:playlist:${p.getString("id")}", p.getString("title"), provider)
                    }
                    val next = page.optInt("nextOffset", -1)
                    check(next == -1 || next > offset) { "Spotify repeated a playlist page. Retry the import." }
                    offset = next
                    check(result.size <= 5000) { "Too many account playlists. Import the one you need by link." }
                } while (offset >= 0)
                result.distinctBy { it.id }
            }
            else -> error("Choose YouTube Music, Spotify, a playlist link or an export file.")
        }
        update(state.copy(choices = choices, preview = emptyList(), needsFolder = false, unavailable = 0,
            message = if (choices.isEmpty()) "No playlists were returned by this account. You can import a playlist link or export file instead." else "Choose a playlist to copy into Music."))
    }
    fun link(value: String) {
        val raw = value.trim()
        val choice = runCatching {
            val uri = Uri.parse(raw)
            require(uri.userInfo == null && uri.port in listOf(-1, 443))
            val host = uri.host?.lowercase()
            when {
                raw.matches(Regex("spotify:playlist:[A-Za-z0-9]{22}")) -> PlaylistImportChoice(raw, "Spotify playlist", "Spotify")
                uri.scheme == "https" && host == "open.spotify.com" -> {
                    val parts = uri.pathSegments; val at = parts.indexOf("playlist")
                    val id = parts.getOrNull(at + 1); require(at >= 0 && id?.matches(Regex("[A-Za-z0-9]{22}")) == true)
                    PlaylistImportChoice("spotify:playlist:$id", "Spotify playlist", "Spotify")
                }
                uri.scheme == "https" && host in setOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com") -> {
                    val id = uri.getQueryParameter("list"); require(id?.matches(Regex("[A-Za-z0-9_-]{2,160}")) == true)
                    PlaylistImportChoice(id!!, "YouTube Music playlist", "YouTube Music")
                }
                else -> error("unsupported")
            }
        }.getOrNull()
        if (choice == null) { update(state.copy(error = "Paste a YouTube Music or Spotify playlist link. For other apps, import an M3U, PLS, XSPF, CSV or JSON export.")); return }
        choose(choice)
    }
    fun choose(choice: PlaylistImportChoice) = work {
        origin = choice.provider; documents = emptyList()
        val tracks = mutableListOf<Track>(); var title = choice.title; var skipped = 0; var pages = 0
        if (choice.provider == "YouTube Music") {
            val session = UUID.randomUUID().toString(); var more = false
            do {
                val page = remote.tracks(choice.id, more, session)
                tracks += page.tracks; skipped += page.skipped
                update(state.copy(message = "Reading ${choice.title} · ${tracks.size} songs…"))
                if (page.title.isNotBlank()) title = page.title.take(150)
                check(++pages <= 250 && tracks.size + skipped <= PlaylistDocuments.MAX_SONGS) { "Import at most 5000 songs at a time." }
                check(!page.hasMore || page.tracks.isNotEmpty() || page.skipped > 0) { "The playlist page did not advance. Retry the import." }
                more = page.hasMore
            } while (more)
        } else if (choice.provider == "Spotify") {
            var offset = 0
            do {
                val page = spotify.importPage(choice.id, offset)
                tracks += spotify.importTracks(page); skipped += page.optInt("skipped", 0)
                update(state.copy(message = "Reading ${choice.title} · ${tracks.size} songs…"))
                page.optString("title").takeIf { it.isNotBlank() }?.let { title = it.take(150) }
                val next = page.optInt("nextOffset", -1)
                check(next == -1 || next > offset) { "Spotify repeated a playlist page. Retry the import." }
                check(++pages <= 250 && tracks.size + skipped <= PlaylistDocuments.MAX_SONGS) { "Import at most 5000 songs at a time." }
                offset = next
            } while (offset >= 0)
        } else error("Choose a supported playlist source.")
        check(tracks.isNotEmpty()) { "This playlist has no available songs, or the account cannot read it." }
        update(state.copy(choices = emptyList(), preview = listOf(PlaylistImportPreview(title, tracks)), unavailable = skipped, needsFolder = false,
            message = "Review this copy before importing. Playlist order and repeated songs are kept."))
    }
    fun file(uri: Uri) = work {
        val name = withContext(Dispatchers.IO) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported playlist"
        }
        documents = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (output.size() <= PlaylistDocuments.MAX_BYTES) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read) }; output.toByteArray() } ?: error("The playlist file is unreadable.")
            require(bytes.size <= PlaylistDocuments.MAX_BYTES) { "Playlist files must be smaller than 2 MB." }
            PlaylistDocuments.parse(bytes.toString(Charsets.UTF_8), name)
        }
        origin = "Playlist file"
        if (documents.any { it.songs.any { song -> localReference(song) && !song.location.startsWith("content://", true) } }) update(state.copy(choices = emptyList(), preview = emptyList(), unavailable = 0, needsFolder = true,
            message = "This playlist refers to local audio. Choose its audio folder so Android can give Music permission to read those files. Files stay on your device."))
        else resolveDocuments(null)
    }
    fun folder(uri: Uri) = work {
        check(documents.isNotEmpty()) { "Choose the playlist file first." }
        check(LocalMasters.hasReadGrant(context, uri.toString())) { "This provider did not grant lasting access. Choose the audio folder using Android's document picker." }
        resolveDocuments(uri)
    }
    private suspend fun resolveDocuments(folder: Uri?) {
        val files = if (folder == null) emptyMap() else withContext(Dispatchers.IO) { val caller = currentCoroutineContext(); LocalPlaylistFiles.index(context, folder) { caller.ensureActive() } }
        var unavailable = 0; val ready = mutableListOf<PlaylistImportPreview>()
        for (document in documents) {
            val tracks = mutableListOf<Track>()
            for (song in document.songs) {
                currentCoroutineContext().ensureActive()
                val track = try {
                    when {
                        localReference(song) -> withContext(Dispatchers.IO) {
                            val uri = if (song.location.startsWith("content://", true)) {
                                val original = Uri.parse(song.location)
                                original.takeIf { LocalMasters.hasReadGrant(context, it.toString()) } ?: runCatching {
                                    val path = android.provider.DocumentsContract.getDocumentId(original).substringAfter(':')
                                    LocalPlaylistFiles.find(path, files)
                                }.getOrNull()
                            } else LocalPlaylistFiles.find(song.location, files)
                            uri?.let { LocalPlaylistFiles.read(context, it, song) }?.takeUnless(library::isRemoved)
                        }
                        else -> onlineSong(song)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (track == null) unavailable++ else tracks += track
            }
            if (tracks.isNotEmpty()) ready += PlaylistImportPreview(document.title, tracks)
        }
        if (ready.isEmpty() && documents.any { it.songs.any(::localReference) }) update(state.copy(needsFolder = true))
        check(ready.isNotEmpty()) { "No playable entries were found. Check the audio folder, account connection or export's song names and artists." }
        update(state.copy(choices = emptyList(), preview = ready, unavailable = unavailable, needsFolder = false,
            message = "Review before importing. Missing, inaccessible or ambiguous recordings are skipped; local files are never uploaded."))
    }
    private suspend fun onlineSong(song: ImportSong): Track? {
        val normalized = YouTubeLink.normalize(song.location)
        if (normalized?.contains("/watch?v=") == true) {
            val id = normalized.substringAfter("?v=")
            return Track("yt:$id", song.title.ifBlank { id }, song.artist, normalized, durationMs = song.durationMs)
        }
        val id = Regex("^(?:spotify:track:|https://open\\.spotify\\.com/(?:intl-[a-z-]+/)?track/)([A-Za-z0-9]{22})(?:[?#].*)?$").matchEntire(song.location)?.groupValues?.get(1)
        if (id != null) {
            if (song.title.isNotBlank() && song.artist.isNotBlank() && song.durationMs in 1..86_400_000)
                return Track("spotify:$id", song.title.take(512), song.artist.take(512), "https://open.spotify.com/track/$id", durationMs = song.durationMs, spotifyId = id)
            val metadata = runCatching { spotify.importPage("spotify:track:$id").let(spotify::importTracks).singleOrNull() }.getOrElse { if (it is CancellationException) throw it; null }
            if (metadata != null) return metadata
        }
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val matches = youtube.search("${song.title} ${song.artist}".take(400)).filter { candidate ->
            text(candidate.title) == text(song.title) && text(candidate.artist).removeSuffix(" topic") in song.artist.split(Regex("[,;&]")).map(::text) &&
                (song.durationMs <= 0 || candidate.durationMs > 0 && kotlin.math.abs(candidate.durationMs - song.durationMs) <= 6000)
        }.distinctBy(Track::libraryKey)
        return matches.singleOrNull()?.let { if (id == null) it else it.copy(spotifyId = id) }
    }
    fun confirm() {
        if (state.busy || state.preview.isEmpty()) return
        val preview = state.preview.toList(); val unavailable = state.unavailable; val from = origin
        job?.cancel(); val ticket = ++epoch
        update(state.copy(busy = true, saving = true, error = null))
        job = scope.launch {
            try {
                val created = withContext(NonCancellable + Dispatchers.IO) { device.importCopies(preview, from) }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (ticket == epoch) {
                        documents = emptyList(); update(PlaylistImportState())
                        val count = preview.sumOf { it.tracks.size }
                        saved("Imported ${created.size} playlist${if (created.size == 1) "" else "s"} · $count songs${if (unavailable > 0) " · $unavailable unavailable entries skipped" else ""}.")
                    }
                }
            } catch (e: Exception) { if (ticket == epoch) update(state.copy(busy = false, saving = false, error = safeMessage(e))) }
        }
    }
    companion object {
        private fun text(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().replace(Regex("(?i)\\b(official|audio|video|lyrics?|visualizer)\\b"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        internal fun localReference(song: ImportSong): Boolean = song.location.isNotBlank() &&
            (song.location.startsWith("content://", true) || song.location.startsWith("file://", true) || !song.location.contains(":") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(song.location))
        private fun safeMessage(error: Exception): String = when (error) {
            is IllegalArgumentException, is IllegalStateException -> error.message?.take(240) ?: "The playlist could not be imported."
            is labs.rtensor.music.spotify.SpotifyCatalogueException -> error.message ?: "Connect Spotify and retry."
            is SourceException -> error.message?.take(240) ?: "The provider could not read this playlist."
            else -> "The playlist could not be imported. Check access to the source and retry."
        }
    }
}
