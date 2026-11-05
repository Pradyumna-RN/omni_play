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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import labs.rtensor.music.CatalogueMessages
import labs.rtensor.music.catalogueErrorMessage
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.LocalMasters
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Track
import labs.rtensor.music.recommendations.RecommendationEngine
import java.util.concurrent.ConcurrentHashMap

/** A play request from the car that could not be met; [message] is shown to the driver. */
internal class CarRequestException(val code: Int, message: String) : Exception(message)

/**
 * The Android Auto browse tree: Home, Saved and Recent, plus search and play requests.
 *
 * Everything is read from the on-device stores (Saved, listening history and the Home
 * recommendations the phone last loaded), so the car works without the phone UI. Reads run
 * on [io]; no call blocks the session's thread. Items carry only a stable media ID and display
 * metadata; a tapped ID is resolved again here into the same [PlayableItems] the phone queues.
 */
@UnstableApi
internal class CarLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The app's own catalogue search (the Explore search box). */
    private val catalogueSearch: suspend (String) -> List<Track>,
    /** A live Home feed for the YouTube Music provider, used only before the phone has cached one. */
    private val liveHome: suspend () -> List<Track>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Tab(val id: String, val title: String, val playableStyle: Int) {
        HOME("home", "Home", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
        SAVED("saved", "Saved", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
        RECENT("recent", "Recent", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
    }

    private data class SearchOutcome(val tracks: List<Track>, val localCount: Int, val error: String?)

    private val rootLimits = ConcurrentHashMap<String, Int>()
    @Volatile private var liveHomeTracks: List<Track> = emptyList()
    private val searches = object : LinkedHashMap<String, Deferred<SearchOutcome>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<SearchOutcome>>) = size > 4
    }

    fun root(params: LibraryParams?, browser: String): LibraryResult<MediaItem> {
        // Resumption and assistant suggestions need a single "recent" item; Music does not offer them.
        if (params?.isRecent == true || params?.isSuggested == true)
            return LibraryResult.ofError(SessionError(SessionError.ERROR_NOT_SUPPORTED, "Music does not offer suggested or recent roots."))
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }?.let { rootLimits[browser] = it }
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putBoolean(SEARCH_SUPPORTED, true)
        }
        return LibraryResult.ofItem(folder(ROOT, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, null), LibraryParams.Builder().setExtras(extras).build())
    }

    /** Tabs shown at the root, newest settings first: Recent only while history is kept. */
    fun tabs(browser: String, params: LibraryParams? = null): List<Tab> {
        val limit = listOfNotNull(rootLimits[browser], params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }).minOrNull() ?: Int.MAX_VALUE
        return Tab.entries.filter { it != Tab.RECENT || LibraryStore(context).trackHistory }.take(limit.coerceAtLeast(1))
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int, params: LibraryParams?, browser: String): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize < 1) return LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "Invalid page."))
        val items = when {
            parentId == ROOT -> tabs(browser, params).map(::tabItem)
            parentId.startsWith(TAB) -> {
                val tab = Tab.entries.firstOrNull { it.id == parentId.removePrefix(TAB) }
                    ?: return LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "This list is not available."))
                val tracks = tracks(tab)
                if (tracks.isEmpty()) listOf(notice(tab)) else tracks.map { song(it, tab.id) }
            }
            else -> return LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "This list is not available."))
        }
        return LibraryResult.ofItemList(page(items, page, pageSize), params)
    }

    suspend fun item(mediaId: String): LibraryResult<MediaItem> {
        if (mediaId == ROOT) return LibraryResult.ofItem(folder(ROOT, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, null), null)
        Tab.entries.firstOrNull { TAB + it.id == mediaId }?.takeIf { it != Tab.RECENT || LibraryStore(context).trackHistory }?.let { return LibraryResult.ofItem(tabItem(it), null) }
        locate(mediaId)?.let { (tracks, index, tab) -> return LibraryResult.ofItem(song(tracks[index], tab), null) }
        return LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "This song is no longer available."))
    }

    /** Start (or join) a search; returns how many results it found. */
    suspend fun search(query: String): Int = searchOutcome(query).tracks.size

    suspend fun searchPage(query: String, page: Int, pageSize: Int, params: LibraryParams?): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize < 1) return LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "Invalid page."))
        val outcome = searchOutcome(query)
        if (outcome.tracks.isEmpty() && outcome.error != null) return LibraryResult.ofError(SessionError(SessionError.ERROR_IO, outcome.error))
        val items = outcome.tracks.mapIndexed { index, track ->
            song(track, SEARCH, group = if (index < outcome.localCount) "In your library" else "Results")
        }
        return LibraryResult.ofItemList(page(items, page, pageSize), params)
    }

    /**
     * Turn a car request into player items. A single song chosen from a list queues that list
     * from the song, since the phone's automatic radio does not run while its screen is closed.
     * A voice request ("play …") searches; an empty one plays Saved.
     */
    suspend fun play(requests: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaItemsWithStartPosition {
        val single = requests.singleOrNull()
        if (single != null) {
            val query = single.requestMetadata.searchQuery
            val (tracks, index) = when {
                single.mediaId.startsWith(SONG) -> locate(single.mediaId)?.let { it.first to it.second }
                    ?: throw CarRequestException(SessionError.ERROR_BAD_VALUE, "This song is no longer available. Open the list again.")
                query != null && query.isBlank() -> (tracks(Tab.SAVED).ifEmpty { tracks(Tab.HOME) }).takeIf { it.isNotEmpty() }?.let { it to 0 }
                    ?: throw CarRequestException(SessionError.ERROR_SESSION_SETUP_REQUIRED, "Save some songs in Music on your phone first.")
                query != null -> searchOutcome(query).let { found ->
                    if (found.tracks.isEmpty()) throw CarRequestException(SessionError.ERROR_IO, found.error ?: "No songs found for “${query.trim()}”.")
                    found.tracks to 0
                }
                else -> throw CarRequestException(SessionError.ERROR_BAD_VALUE, "This song is no longer available.")
            }
            // Like the phone, a queue holds at most 100 songs; keep the chosen one inside it.
            val window = if (index < 100) tracks.take(100) else tracks.subList(index, minOf(tracks.size, index + 100))
            val chosen = tracks[index]
            val items = playable(window)
            val start = items.indexOfFirst { it.first.libraryKey() == chosen.libraryKey() }
            if (start < 0) throw CarRequestException(SessionError.ERROR_NOT_SUPPORTED, "This song cannot be played from the car.")
            return MediaItemsWithStartPosition(items.map { it.second }, start, C.TIME_UNSET)
        }
        val items = playable(requests.mapNotNull { locate(it.mediaId)?.let { (tracks, index) -> tracks[index] } })
        if (items.isEmpty()) throw CarRequestException(SessionError.ERROR_BAD_VALUE, "These songs are no longer available.")
        return MediaItemsWithStartPosition(items.map { it.second }, startIndex.takeIf { it in items.indices } ?: 0,
            if (startIndex in items.indices) startPositionMs else C.TIME_UNSET)
    }

    /** Resolve each requested song on its own, for "add to queue". */
    suspend fun resolveEach(requests: List<MediaItem>): List<MediaItem> =
        playable(requests.mapNotNull { locate(it.mediaId)?.let { (tracks, index) -> tracks[index] } }).map { it.second }

    private suspend fun playable(tracks: List<Track>): List<Pair<Track, MediaItem>> = withContext(io) {
        val store = LibraryStore(context)
        val masters = LocalMasters(context).entries.filterNot { it.uri in store.removedLocalUris }
        tracks.filterNot(store::isRemoved)
            .map { it to PlayableItems.build(it, PlaybackRoute.choose(it, masters)) }
            .filter { PlayableItems.permitted(it.second) }
    }

    private suspend fun tracks(tab: Tab): List<Track> = withContext(io) {
        val store = LibraryStore(context)
        when (tab) {
            Tab.SAVED -> store.tracks("saved")
            Tab.RECENT -> if (!store.trackHistory) emptyList() else
                (store.tracks("history") + ListeningHistory.latestPerSong(ListeningHistory(context).events).map { it.track })
                    .filterNot(store::isRemoved).distinctBy { it.libraryKey() }.take(100)
            Tab.HOME -> store.tracks(LibraryStore.HOME_RECOMMENDATIONS).ifEmpty {
                if (store.recommendationEngine != RecommendationEngine.YOUTUBE) emptyList()
                else liveHomeTracks.ifEmpty {
                    try { withTimeout(20_000) { liveHome() }.filterNot(store::isRemoved).distinctBy { it.libraryKey() }.take(100).also { liveHomeTracks = it } }
                    catch (e: CancellationException) { if (e is kotlinx.coroutines.TimeoutCancellationException) emptyList() else throw e }
                    catch (_: Exception) { emptyList() }
                }
            }
        }
    }

    /** The list a song ID came from and its position, read afresh so removed songs never play. */
    private suspend fun locate(mediaId: String): Triple<List<Track>, Int, String>? {
        if (!mediaId.startsWith(SONG)) return null
        val tab = mediaId.removePrefix(SONG).substringBefore(':')
        val key = mediaId.removePrefix(SONG).substringAfter(':', "")
        if (key.isEmpty()) return null
        val lists = if (tab == SEARCH) synchronized(searches) { searches.values.toList().reversed() }
            .mapNotNull { it.done()?.tracks }
            .map { found -> withContext(io) { val store = LibraryStore(context); found.filterNot(store::isRemoved) } }
        else listOf(tracks(Tab.entries.firstOrNull { it.id == tab } ?: return null))
        for (tracks in lists) {
            val index = tracks.indexOfFirst { it.libraryKey() == key }
            if (index >= 0) return Triple(tracks, index, tab)
        }
        return null
    }

    private suspend fun searchOutcome(query: String): SearchOutcome {
        val q = query.trim().take(400)
        if (q.isEmpty()) return SearchOutcome(emptyList(), 0, null)
        val key = q.lowercase()
        val pending = synchronized(searches) {
            searches[key]?.takeUnless { it.isCancelled || it.done()?.error != null }
                ?: scope.async { runSearch(q) }.also { searches[key] = it }
        }
        return pending.await()
    }

    /** Matching Saved songs first (they include imported files), then the app's own catalogue search. */
    private suspend fun runSearch(query: String): SearchOutcome {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val local = withContext(io) {
            LibraryStore(context).tracks("saved").filter { track ->
                val text = "${track.title} ${track.artist}".lowercase()
                words.all(text::contains)
            }.take(10)
        }
        var error: String? = null
        val remote: List<Track> = try { catalogueSearch(query) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = catalogueErrorMessage(e, CatalogueMessages.SEARCH); emptyList<Track>() }
        val store = withContext(io) { LibraryStore(context) }
        val localKeys = local.mapTo(HashSet()) { it.libraryKey() }
        val results = (local + remote.filter { it.libraryKey() !in localKeys }).filterNot(store::isRemoved).distinctBy { it.libraryKey() }.take(50)
        return SearchOutcome(results, local.size, error)
    }

    private fun tabItem(tab: Tab) = folder(TAB + tab.id, tab.title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, tab.playableStyle) })

    private fun folder(id: String, title: String, type: Int, extras: Bundle?) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(type).setExtras(extras).build()).build()

    private fun song(track: Track, tab: String, group: String? = null) = MediaItem.Builder()
        .setMediaId("$SONG$tab:${track.libraryKey()}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setSubtitle(track.artist)
            .setArtworkUri(track.artwork.takeIf(String::isNotBlank)?.let(android.net.Uri::parse))
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(group?.let { Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } })
            .build()).build()

    /** One row that explains an empty list. It cannot be played, and nothing in the car signs in. */
    private fun notice(tab: Tab): MediaItem {
        val store = LibraryStore(context)
        val (title, hint) = when (tab) {
            Tab.HOME -> "No recommendations yet" to (if (store.recommendationEngine == RecommendationEngine.SPOTIFY)
                "Open Music on your phone to load your Spotify recommendations." else "Open Music on your phone to load Home.")
            Tab.SAVED -> "No saved songs yet" to "Save songs in Music on your phone."
            Tab.RECENT -> if (store.trackHistory) "Nothing played yet" to "Songs you play appear here."
                else "Listening history is off" to "Turn it on in Music on your phone."
        }
        return MediaItem.Builder().setMediaId(NOTICE + tab.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(hint)
                .setIsBrowsable(false).setIsPlayable(false).build()).build()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<SearchOutcome>.done(): SearchOutcome? =
        if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null

    private fun page(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
    }

    companion object {
        const val ROOT = "car:root"
        const val TAB = "car:tab:"
        const val SONG = "car:song:"
        const val NOTICE = "car:notice:"
        const val SEARCH = "search"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        /** A car request names a browse ID or a voice query; it never carries a URI to play. */
        fun isRequest(item: MediaItem): Boolean = item.localConfiguration == null &&
            (item.mediaId.startsWith(SONG) || (item.mediaId.isEmpty() && item.requestMetadata.searchQuery != null))
    }
}
