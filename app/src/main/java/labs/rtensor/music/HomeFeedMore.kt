package labs.rtensor.music

import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.HomeFeed
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.feedSongs
import labs.rtensor.music.ui.homeKnownKeys

/** Songs and genuine provider shelves from one continuation request. */
internal data class HomeFeedMore(val songs: List<Track>, val hasMore: Boolean, val sections: List<FeedSection> = emptyList())

/** Continuations one fetch may read while looking for songs Home has not shown yet. */
internal const val HOME_MORE_PAGES = 3

/**
 * Read the feed's next continuations until one has songs Home has not loaded or played, at most
 * [maxPages] of them. A page that only repeats known songs and collections is skipped instead of shown
 * again; after [maxPages] the provider's hasMore is retained rather than inventing an end. [next] asks the
 * provider for its next continuation.
 */
internal suspend fun loadMoreHomeFeed(state: MusicState, maxPages: Int = HOME_MORE_PAGES, next: suspend () -> HomeFeed): HomeFeedMore {
    val known = homeKnownKeys(state)
    val knownAtStart = known.toSet()
    val entryKeys = (state.feed + state.feedContinuations).flatMap { it.items }.mapTo(HashSet()) { "${it.kind}:${it.id}" }
    val sections = mutableListOf<FeedSection>()
    var hasMore = state.feedHasMore
    repeat(maxPages) {
        val page = next()
        hasMore = page.hasMore
        val songs = feedSongs(page.sections).filter { known.add(it.libraryKey()) }
        val added = page.sections.map { section -> section.copy(items = section.items.filter {
            val recording = it.videoId.isNotBlank() || it.kind == "spotify_track"
            (if (recording) it.asTrack().libraryKey() !in knownAtStart else it.kind in setOf("playlist", "album", "artist", "library_artist", "spotify_playlist", "spotify_album")) && entryKeys.add("${it.kind}:${it.id}")
        }) }.filter { it.items.isNotEmpty() }
        sections += added
        val newCollection = added.any { section -> section.items.any { it.videoId.isBlank() && it.kind != "spotify_track" } }
        if (songs.isNotEmpty() || newCollection) return HomeFeedMore(songs, page.hasMore, sections)
        if (!page.hasMore) return HomeFeedMore(emptyList(), false, sections)
    }
    return HomeFeedMore(emptyList(), hasMore, sections)
}

/** Home after a pull to refresh: the first page replaces everything, including songs "More" added. */
internal fun MusicState.withHomeFeed(page: HomeFeed, personalized: Boolean): MusicState =
    copy(feed = page.sections, feedContinuations = emptyList(), feedPage = 0, feedMore = emptyList(), feedHasMore = page.hasMore, feedEnded = false, feedPersonalized = personalized, homeFilters = page.filters, homeFilter = page.selectedFilter)

/** Continuations append without replacing songs or reshuffling the opening shelves. */
internal fun MusicState.withMoreHomeFeed(more: HomeFeedMore): MusicState =
    copy(feedContinuations = feedContinuations + more.sections,
        feedMore = (feedMore + more.songs).distinctBy(Track::libraryKey),
        feedPage = feedPage + 1, feedHasMore = more.hasMore, feedEnded = !more.hasMore)
