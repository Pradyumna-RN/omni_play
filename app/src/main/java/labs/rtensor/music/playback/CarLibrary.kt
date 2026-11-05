package labs.rtensor.music.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import labs.rtensor.music.CatalogueMessages
import labs.rtensor.music.catalogueErrorMessage
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.LocalMasters
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.recommendations.RecommendationEngine

/** A play request from the car that Media3 could not satisfy; [message] is shown to the driver. */
internal class CarRequestException(val code: Int, message: String) : Exception(message)

/**
 * The library Android Auto browses: Home, Saved and Recent, plus search and play requests.
 * Everything is read from the stores the phone UI writes, so it works with the app closed.
 * Songs become session items through [PlayableItems], exactly as the phone UI builds them.
 */
@UnstableApi
internal class CarLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The app's own search, as used by the Explore search box. */
    private val search: suspend (String) -> List<Track>,
    /** A live Home feed for when the phone has not cached recommendations yet (YouTube Music only). */
    private val liveHome: suspend () -> List<Track>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    internal enum class Tab(val id: String, val title: String, val playableStyle: Int) {
        HOME("home", "Home", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
        SAVED("saved", "Saved", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
        RECENT("recent", "Recent", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
    }

    private class SearchOutcome(val tracks: List<Track>, val localCount: Int, val error: String?)

    private val rootLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val searches = object : LinkedHashMap<String, Deferred<SearchOutcome>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<SearchOutcome>>) = size > 4
    }
    @Volatile private var liveHomeTracks: List<Track> = emptyList()

    private val store get() = LibraryStore(context)

    fun root(params: LibraryParams?, browser: String): LibraryResult<MediaItem> {
        // No playback resumption or assistant suggestions: say so instead of returning the full tree.
        if (params?.isRecent == true || params?.isSuggested == true)
            return LibraryResult.ofError(SessionError(SessionError.ERROR_NOT_SUPPORTED, "Music does not offer suggestions here."))
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }?.let { rootLimits[browser] = it }
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putBoolean(SEARCH_SUPPORTED, true)
        }
        return LibraryResult.ofItem(folder(ROOT, "Music", null), LibraryParams.Builder().setExtras(extras).build())
    }

    /** The tabs this driver may see: Recent only while listening history is on. */
    fun tabs(browser: String, params: LibraryParams? = null): List<Tab> {
        val visible = Tab.entries.filter { it != Tab.RECENT || store.trackHistory }
        val limit = listOfNotNull(rootLimits[browser], params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }).minOrNull()
        return if (limit == null) visible else visible.take(limit)
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int, params: LibraryParams?, browser: String): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize < 1) return error(SessionError.ERROR_BAD_VALUE, "This page is not available.")
        val items: List<MediaItem> = when (parentId) {
            ROOT -> tabs(browser, params).map(::tabItem)
            else -> {
                val tab = tabFor(parentId) ?: return error(SessionError.ERROR_BAD_VALUE, "This section is not available.")
                val tracks = tracks(tab)
                if (tracks.isEmpty()) listOf(notice(tab)) else tracks.map { song(tab.id, it) }
            }
        }
        return LibraryResult.ofItemList(page(items, page, pageSize), params)
    }

    suspend fun item(mediaId: String): LibraryResult<MediaItem> {
        if (mediaId == ROOT) return LibraryResult.ofItem(folder(ROOT, "Music", null), null)
        tabFor(mediaId)?.let { tab -> if (tab != Tab.RECENT || store.trackHistory) return LibraryResult.ofItem(tabItem(tab), null) }
        val (tab, key) = parseSong(mediaId) ?: return error(SessionError.ERROR_BAD_VALUE, NOT_FOUND)
        val track = listFor(tab).firstOrNull { it.libraryKey() == key } ?: return error(SessionError.ERROR_BAD_VALUE, NOT_FOUND)
        return LibraryResult.ofItem(song(tab, track), null)
    }

    /** Run (or join) a search and return how many results it found. */
    suspend fun search(query: String): Int = searchOutcome(query).tracks.size

    suspend fun searchResult(query: String, page: Int, pageSize: Int, params: LibraryParams?): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize < 1) return error(SessionError.ERROR_BAD_VALUE, "This page is not available.")
        val outcome = searchOutcome(query)
        if (outcome.tracks.isEmpty() && outcome.error != null) return error(SessionError.ERROR_IO, outcome.error)
        val items = outcome.tracks.mapIndexed { index, track ->
            song(SEARCH, track, if (index < outcome.localCount) "In your library" else "Search results")
        }
        return LibraryResult.ofItemList(page(items, page, pageSize), params)
    }

    /**
     * Turn the car's request into the player queue. A song tapped in a list queues that list from
     * the song, because automatic radio is run by the phone screen and may not be running.
     * A voice request ("play …") searches; an empty one plays Saved, then Home, then Recent.
     */
    suspend fun play(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaItemsWithStartPosition {
        if (items.size == 1) {
            val request = items.single()
            val (tracks, index) = if (parseSong(request.mediaId) != null) locate(request.mediaId)
                else voice(request.requestMetadata.searchQuery.orEmpty())
            val window = if (index < MAX_QUEUE) tracks.take(MAX_QUEUE) else tracks.subList(index, minOf(tracks.size, index + MAX_QUEUE))
            val selected = tracks[index]
            val playable = playable(window)
            val start = playable.indexOfFirst { it.first.libraryKey() == selected.libraryKey() }
            if (start < 0) throw CarRequestException(SessionError.ERROR_NOT_SUPPORTED, "This song cannot be played from the car.")
            return MediaItemsWithStartPosition(playable.map { it.second }, start, C.TIME_UNSET)
        }
        val playable = playable(items.mapNotNull { runCatching { locate(it.mediaId) }.getOrNull()?.let { (list, index) -> list[index] } })
        if (playable.isEmpty()) throw CarRequestException(SessionError.ERROR_BAD_VALUE, NOT_FOUND)
        val start = startIndex.takeIf { it in playable.indices } ?: 0
        return MediaItemsWithStartPosition(playable.map { it.second }, start, if (start == startIndex) startPositionMs else C.TIME_UNSET)
    }

    /** For "add to queue": each requested song on its own, with no list expansion. */
    suspend fun resolveEach(items: List<MediaItem>): List<MediaItem> =
        playable(items.mapNotNull { runCatching { locate(it.mediaId) }.getOrNull()?.let { (list, index) -> list[index] } }).map { it.second }

    private suspend fun voice(query: String): Pair<List<Track>, Int> {
        val tracks = if (query.isBlank()) {
            Tab.entries.firstNotNullOfOrNull { tab -> tracks(tab).takeIf { it.isNotEmpty() } }
                ?: throw CarRequestException(SessionError.ERROR_INVALID_STATE, "Nothing to play yet. Save or play songs in Music on your phone.")
        } else {
            val outcome = searchOutcome(query)
            outcome.tracks.ifEmpty { throw CarRequestException(SessionError.ERROR_IO, outcome.error ?: "No songs found for “${query.trim()}”.") }
        }
        return tracks to 0
    }

    private suspend fun locate(mediaId: String): Pair<List<Track>, Int> {
        val (tab, key) = parseSong(mediaId) ?: throw CarRequestException(SessionError.ERROR_BAD_VALUE, NOT_FOUND)
        val list = listFor(tab)
        val index = list.indexOfFirst { it.libraryKey() == key }
        if (index < 0) throw CarRequestException(SessionError.ERROR_BAD_VALUE, NOT_FOUND)
        return list to index
    }

    private suspend fun listFor(tab: String): List<Track> =
        if (tab == SEARCH) synchronized(searches) { searches.values.toList() }.asReversed()
            .filter { it.isCompleted }.flatMap { runCatching { it.getCompleted().tracks }.getOrDefault(emptyList()) }.distinctBy { it.libraryKey() }
        else tabFor(TAB_PREFIX + tab)?.let { tracks(it) }.orEmpty()

    /** Build the same session items the phone UI plays, routed to the user's own files where they match. */
    private suspend fun playable(tracks: List<Track>): List<Pair<Track, MediaItem>> = withContext(io) {
        val removed = store.removedLocalUris
        val masters = LocalMasters(context).entries.filterNot { it.uri in removed }
        tracks.filterNot { it.source == Source.LOCAL && it.url in removed }
            .map { it to PlayableItems.build(it, PlaybackRoute.choose(it, masters)) }
            .filter { PlayableItems.permitted(it.second) }
    }

    internal suspend fun tracks(tab: Tab): List<Track> = withContext(io) {
        val library = store
        when (tab) {
            Tab.SAVED -> library.tracks("saved")
            Tab.RECENT -> if (!library.trackHistory) emptyList() else
                (library.tracks("history") + ListeningHistory.latestPerSong(ListeningHistory(context).events).map { it.track })
                    .filterNot(library::isRemoved).distinctBy { it.libraryKey() }.take(MAX_QUEUE)
            Tab.HOME -> library.tracks(LibraryStore.HOME_RECOMMENDATIONS).ifEmpty { liveHome(library) }
        }
    }

    private suspend fun liveHome(library: LibraryStore): List<Track> {
        if (library.recommendationEngine != RecommendationEngine.YOUTUBE) return emptyList()
        liveHomeTracks.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            withTimeout(LIVE_HOME_TIMEOUT_MS) { liveHome() }.filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
                .take(MAX_QUEUE).also { liveHomeTracks = it }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) emptyList() else throw e
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun searchOutcome(query: String): SearchOutcome {
        val clean = query.trim().take(400)
        if (clean.isBlank()) return SearchOutcome(emptyList(), 0, null)
        val running = synchronized(searches) {
            searches.getOrPut(clean.lowercase()) { scope.async(io) { runSearch(clean) } }
        }
        return running.await()
    }

    private suspend fun runSearch(query: String): SearchOutcome {
        val library = store
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        // Saved songs first, so the driver's own imports are found even though catalogue search cannot see them.
        val local = library.tracks("saved").filter { track ->
            val text = "${track.title} ${track.artist}".lowercase()
            words.all { it in text }
        }
        var failure: String? = null
        val remote = try { search(query) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            failure = catalogueErrorMessage(e, CatalogueMessages.SEARCH); emptyList()
        }
        val tracks = (local + remote.filterNot(library::isRemoved)).distinctBy { it.libraryKey() }.take(MAX_SEARCH)
        return SearchOutcome(tracks, local.size.coerceAtMost(tracks.size), failure)
    }

    private fun tabItem(tab: Tab) = folder(TAB_PREFIX + tab.id, tab.title, Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, tab.playableStyle)
    })

    private fun folder(id: String, title: String, extras: Bundle?) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(extras).build()
    ).build()

    private fun song(tab: String, track: Track, group: String? = null) = MediaItem.Builder()
        .setMediaId(SONG_PREFIX + tab + ":" + track.libraryKey())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist.ifBlank { null })
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(track.artwork.takeIf { it.startsWith("https://") || it.startsWith("content://") }?.let(android.net.Uri::parse))
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(group?.let { Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } })
            .build())
        .build()

    private fun notice(tab: Tab): MediaItem {
        val library = store
        val (title, detail) = when (tab) {
            Tab.HOME -> "No recommendations yet" to (if (library.recommendationEngine == RecommendationEngine.SPOTIFY)
                "Open Music on your phone to load your Spotify recommendations." else "Open Music on your phone to load Home.")
            Tab.SAVED -> "No saved songs yet" to "Save songs in Music on your phone."
            Tab.RECENT -> if (library.trackHistory) "Nothing played yet" to "Songs you play appear here."
                else "Listening history is off" to "Turn it on in Music on your phone."
        }
        return MediaItem.Builder().setMediaId(NOTICE_PREFIX + tab.id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(detail).setArtist(detail)
                .setIsBrowsable(false).setIsPlayable(false).build()
        ).build()
    }

    private fun tabFor(id: String) = Tab.entries.firstOrNull { TAB_PREFIX + it.id == id }

    private fun <T> error(code: Int, message: String) = LibraryResult.ofError<T>(SessionError(code, message))

    private fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
    }

    companion object {
        const val ROOT = "car:root"
        const val TAB_PREFIX = "car:tab:"
        const val SONG_PREFIX = "car:song:"
        const val NOTICE_PREFIX = "car:notice:"
        const val SEARCH = "search"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val MAX_QUEUE = 100
        private const val MAX_SEARCH = 50
        private const val LIVE_HOME_TIMEOUT_MS = 20_000L
        private const val NOT_FOUND = "This song is no longer available. Browse again."

        /** A car request carries only a browse id or a voice query, never a URI the car chose. */
        fun isRequest(item: MediaItem) = item.localConfiguration == null &&
            (parseSong(item.mediaId) != null || (item.mediaId.isEmpty() && item.requestMetadata.searchQuery != null))

        fun parseSong(mediaId: String): Pair<String, String>? {
            if (!mediaId.startsWith(SONG_PREFIX)) return null
            val rest = mediaId.removePrefix(SONG_PREFIX)
            val tab = rest.substringBefore(':', "")
            val key = rest.substringAfter(':', "")
            return if (tab.isBlank() || key.isBlank()) null else tab to key
        }
    }
}
