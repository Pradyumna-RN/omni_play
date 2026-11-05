package labs.rtensor.music

import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.HomeFeed

/** What one tap on Home's "More from your feed" brought back. */
internal data class HomeFeedMore(val sections: List<FeedSection>, val hasMore: Boolean)

/** Fetch the next page of the Home feed; [next] asks the provider for its continuation. */
internal suspend fun loadMoreHomeFeed(@Suppress("UNUSED_PARAMETER") state: MusicState, next: suspend () -> HomeFeed): HomeFeedMore {
    val page = next()
    return HomeFeedMore(page.sections, page.hasMore)
}

/** Home after a pull to refresh: the first page replaces everything. */
internal fun MusicState.withHomeFeed(page: HomeFeed, personalized: Boolean): MusicState =
    copy(feed = page.sections, feedHasMore = page.hasMore, feedPersonalized = personalized)

/** Home after "More from your feed". */
internal fun MusicState.withMoreHomeFeed(more: HomeFeedMore): MusicState =
    copy(feed = feed + more.sections, feedHasMore = more.hasMore)
