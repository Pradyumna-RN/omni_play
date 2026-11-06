package labs.rtensor.music

import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.HomeFeed
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.feedSongs
import labs.rtensor.music.ui.homeKnownKeys

/** Songs and genuine provider shelves from one continuation request. */
internal data class HomeFeedMore(val songs: List<Track>, val hasMore: Boolean, val sections: List<FeedSection> = emptyList())

/** Continuations one tap may read while looking for songs Home has not shown yet. */
internal const val HOME_MORE_PAGES = 3

/**
 * Read the feed's next continuations until one has songs Home has not loaded or played, at most
 * [maxPages] of them. A page that only repeats known songs and collections is skipped instead of shown
 * again; when [maxPages] pages bring nothing new the feed counts as finished. [next] asks the
 * provider for its next continuation.
 */
internal suspend fun loadMoreHomeFeed(state: MusicState, maxPages: Int = HOME_MORE_PAGES, next: suspend () -> HomeFeed): HomeFeedMore {
    val known = homeKnownKeys(state)
    val knownAtStart = known.toSet()
    val entryKeys = state.feed.flatMap { it.items }.mapTo(HashSet()) { "${it.kind}:${it.id}" }
    val sections = mutableListOf<FeedSection>()
    repeat(maxPages) {
        val page = next()
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
    return HomeFeedMore(emptyList(), false, sections)
}

/** Home after a pull to refresh: the first page replaces everything, including songs "More" added. */
internal fun MusicState.withHomeFeed(page: HomeFeed, personalized: Boolean): MusicState =
    copy(feed = page.sections, feedMore = emptyList(), feedHasMore = page.hasMore, feedEnded = false, feedPersonalized = personalized, homeFilters = page.filters, homeFilter = page.selectedFilter)

/**
 * Home after "More from your feed": the new songs replace the previous batch, so "New to your ears"
 * shows the next handful. When nothing new came, the last batch stays and Home says so.
 */
internal fun MusicState.withMoreHomeFeed(more: HomeFeedMore): MusicState =
    copy(feed = feed + more.sections, feedMore = if (more.songs.isEmpty()) feedMore else more.songs.distinctBy(Track::libraryKey), feedHasMore = more.hasMore, feedEnded = more.songs.isEmpty() && more.sections.isEmpty())
