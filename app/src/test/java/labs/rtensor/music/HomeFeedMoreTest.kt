package labs.rtensor.music

import kotlinx.coroutines.runBlocking
import labs.rtensor.music.data.*
import labs.rtensor.music.ui.homeRecommendationGroups
import org.junit.Assert.*
import org.junit.Test

/** Home's append-only continuations, driven the way MusicViewModel.refreshFeed drives it. */
class HomeFeedMoreTest {
    /** Behaves like the YouTube engine's "home" call: a refresh starts over, "more" follows the continuation. */
    class PagedFeed(private val pages: List<List<FeedEntry>>) {
        val served = mutableListOf<Int>()
        private var at = -1
        suspend fun home(more: Boolean): HomeFeed {
            at = if (more && at in 0 until pages.lastIndex) at + 1 else 0
            served += at
            return HomeFeed(listOf(FeedSection("Page $at", pages[at])), hasMore = at < pages.lastIndex)
        }
    }

    companion object {
        fun song(id: String) = FeedEntry(id, "Song $id", "Artist", "", "song", "", id.removePrefix("yt:"), 180000, "Artist")
        fun track(id: String) = song(id).asTrack()
        val playlist = FeedEntry("playlist", "A genre playlist", "", "", "playlist", "VLplaylist", "")
        /** "Mixed for you" (forYou) and the first feed page: Home shows f0-f3 and p1-1..p1-5. */
        val mixedForYou = (0..3).map { track("f$it") }
        val firstPage = (0..5).map { song("p1-$it") } + playlist
        fun start(feed: PagedFeed): MusicState = runBlocking {
            MusicState(forYou = mixedForYou).withHomeFeed(feed.home(false), personalized = true)
        }
        fun tap(state: MusicState, feed: PagedFeed): MusicState = runBlocking {
            state.withMoreHomeFeed(loadMoreHomeFeed(state) { feed.home(true) })
        }
        fun MusicState.shownKeys() = homeRecommendationGroups(this).let { it.fromListening + it.newToYou }.map(Track::libraryKey)
    }

    @Test fun continuationAdvancesOnEveryTap() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("p2-0")), listOf(song("p3-0")), listOf(song("p4-0"))))
        var state = start(feed)
        state = tap(state, feed); state = tap(state, feed)
        assertEquals(listOf(0, 1, 2), feed.served)
        // Continuations accumulate in provider order.
        assertEquals(listOf(track("p2-0"), track("p3-0")), state.feedMore)
    }

    @Test fun continuationsKeepTheOpeningShelvesAndAppendTheirSongs() {
        val feed = PagedFeed(listOf(firstPage, (0..3).map { song("p2-$it") }, listOf(song("p3-0"))))
        val before = homeRecommendationGroups(start(feed).also { feed.served.clear() })
        assertEquals((1..5).map { track("p1-$it") }, before.newToYou)
        val after = tap(start(feed), feed)
        assertEquals(before, homeRecommendationGroups(after))
        assertEquals((0..3).map { track("p2-$it") }, after.feedMore)
    }

    @Test fun songsHomeAlreadyShowsAreNotAddedAgain() {
        // f1 is in "From your listening", p1-3 and an alias of p1-5 are in "New to your ears".
        val feed = PagedFeed(listOf(firstPage, listOf(song("f1"), song("p1-3"), song("p2-0"), song("yt:p1-5"), song("p2-1"), song("p2-0")), listOf(song("p3-0"))))
        val start = start(feed)
        val before = homeRecommendationGroups(start)
        val after = tap(start, feed)
        assertEquals(listOf(track("p2-0"), track("p2-1")), after.feedMore)
        assertTrue(before.newToYou.isNotEmpty())
        assertEquals("Home never shows a recording twice", after.shownKeys().distinct(), after.shownKeys())
    }

    @Test fun eachContinuationAccumulatesSongsAndKeepsTheOpeningMix() {
        val feed = PagedFeed(listOf(firstPage, (0..7).map { song("p2-$it") }, listOf(song("p3-0"), song("p3-1")), listOf(song("p4-0"))))
        val first = start(feed)
        val second = tap(first, feed)
        val third = tap(second, feed)
        // Every new song remains available below the opening shelves.
        assertEquals((0..7).map { track("p2-$it") }, second.feedMore)
        assertEquals(second.feedMore + listOf(track("p3-0"), track("p3-1")), third.feedMore)
        for (later in listOf(second, third)) assertEquals(homeRecommendationGroups(first).fromListening, homeRecommendationGroups(later).fromListening)
    }

    @Test fun aPageOfOnlyKnownSongsFetchesTheNextPage() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("f0"), song("p1-2"), playlist), listOf(song("p3-0")), listOf(song("p4-0"))))
        val start = start(feed)
        val before = homeRecommendationGroups(start)
        val after = tap(start, feed)
        assertEquals(listOf(0, 1, 2), feed.served)
        assertEquals(listOf(track("p3-0")), after.feedMore)
        assertTrue(before.newToYou.isNotEmpty())
        assertTrue(after.feedHasMore)
    }

    @Test fun theLastPageHidesTheButton() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("p2-0"))))
        val after = tap(start(feed), feed)
        assertEquals(track("p2-0"), after.feedMore.last())
        assertFalse(after.feedHasMore)
    }

    @Test fun duplicateBatchesDoNotInventAnEndWhileTheProviderHasMore() {
        val repeat = listOf(song("f0"), song("p1-4"), playlist)
        val feed = PagedFeed(listOf(firstPage, repeat, repeat, repeat, listOf(song("p5-0"))))
        val start = start(feed)
        val after = tap(start, feed)
        assertEquals("at most three continuations per tap", listOf(0, 1, 2, 3), feed.served)
        assertEquals(homeRecommendationGroups(start), homeRecommendationGroups(after))
        assertTrue(after.feedHasMore)
        assertFalse(after.feedEnded)
        assertEquals(listOf(track("p5-0")), tap(after, feed).feedMore)
    }

    @Test fun onlyIndividualSongsAreAdded() {
        val video = FeedEntry("noVideo", "An entry without a recording", "", "", "song", "", "")
        val feed = PagedFeed(listOf(firstPage, listOf(playlist, video, song("p2-0")), listOf(song("p3-0"))))
        val after = tap(start(feed), feed).feedMore
        assertEquals(track("p2-0"), after.last())
        assertTrue(after.none { it.title == playlist.title || it.title == video.title })
    }

    @Test fun pullToRefreshStartsOverInsteadOfAppending() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("p2-0")), listOf(song("p3-0"))))
        val start = start(feed)
        val refreshed = runBlocking { tap(start, feed).withHomeFeed(feed.home(false), personalized = true) }
        assertEquals(homeRecommendationGroups(start), homeRecommendationGroups(refreshed))
        assertTrue(refreshed.feedHasMore)
        assertEquals(track("p2-0"), tap(refreshed, feed).feedMore.last())
    }

    @Test fun anAddedSongLaterMixedForYouStillAppearsOnce() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("p2-0")), listOf(song("p3-0"))))
        val after = tap(start(feed), feed).let { it.copy(forYou = it.forYou + track("p2-0")) }
        assertEquals(1, after.shownKeys().count { it == "p2-0" })
    }

    @Test fun theCarListsAddedSongsToo() {
        val feed = PagedFeed(listOf(firstPage, listOf(song("p2-0")), listOf(song("p3-0"))))
        assertTrue(track("p2-0") in carHomeTracks(tap(start(feed), feed)))
    }
    @Test fun aCollectionOnlyPageAddsItsShelfWithoutEndingTheFeed() {
        val newList = playlist.copy(id = "new-playlist", title = "Community mix", browseId = "VLnew-playlist")
        val feed = PagedFeed(listOf(firstPage, listOf(newList), listOf(song("p3-0"))))
        val before = start(feed)
        val after = tap(before, feed)
        assertEquals(listOf(0, 1), feed.served)
        assertEquals(before.feed, after.feed)
        assertEquals(listOf(FeedSection("Page 1", listOf(newList))), after.feedContinuations)
        assertTrue(after.feedHasMore)
        assertFalse(after.feedEnded)
        assertEquals(before.feedMore, after.feedMore)
        assertEquals(listOf(track("p3-0")), tap(after, feed).feedMore)
    }
    @Test fun refreshReplacesMoodFiltersAndTheirSelection() {
        val before = MusicState(homeFilters = listOf("Relax"), homeFilter = "Relax")
        val refreshed = before.withHomeFeed(HomeFeed(emptyList(), hasMore = false, filters = listOf("Energise")), personalized = false)
        assertEquals(listOf("Energise"), refreshed.homeFilters)
        assertNull(refreshed.homeFilter)
    }

}
