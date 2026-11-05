package labs.rtensor.music

import labs.rtensor.music.data.HomeFeed
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.feedSongs
import labs.rtensor.music.ui.homeKnownKeys

/** Songs one tap on Home's "More from your feed" adds, and whether the button stays. */
internal data class HomeFeedMore(val songs: List<Track>, val hasMore: Boolean)

/** Continuations one tap may read while looking for songs Home has not shown yet. */
internal const val HOME_MORE_PAGES = 3

/**
 * Read the feed's next continuations until one has songs Home has not loaded or played, at most
 * [maxPages] of them. A page that only repeats songs already on Home is skipped instead of shown
 * again; when [maxPages] pages bring nothing new the feed counts as finished. [next] asks the
 * provider for its next continuation.
 */
internal suspend fun loadMoreHomeFeed(state: MusicState, maxPages: Int = HOME_MORE_PAGES, next: suspend () -> HomeFeed): HomeFeedMore {
    val known = homeKnownKeys(state)
    repeat(maxPages) {
        val page = next()
        val songs = feedSongs(page.sections).filter { known.add(it.libraryKey()) }
        if (songs.isNotEmpty()) return HomeFeedMore(songs, page.hasMore)
        if (!page.hasMore) return HomeFeedMore(emptyList(), false)
    }
    return HomeFeedMore(emptyList(), false)
}

/** Home after a pull to refresh: the first page replaces everything, including songs "More" added. */
internal fun MusicState.withHomeFeed(page: HomeFeed, personalized: Boolean): MusicState =
    copy(feed = page.sections, feedMore = emptyList(), feedHasMore = page.hasMore, feedEnded = false, feedPersonalized = personalized)

/** Home after "More from your feed": new songs go after the ones already there; nothing is re-sorted. */
internal fun MusicState.withMoreHomeFeed(more: HomeFeedMore): MusicState =
    copy(feedMore = (feedMore + more.songs).distinctBy(Track::libraryKey), feedHasMore = more.hasMore, feedEnded = more.songs.isEmpty())
