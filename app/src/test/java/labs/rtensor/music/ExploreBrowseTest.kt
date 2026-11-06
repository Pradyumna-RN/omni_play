package labs.rtensor.music

import labs.rtensor.music.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExploreBrowseTest {
    private val song = FeedEntry("yt:aaaaaaaaaaa", "Provider song", "Artist", "", "song", "", "aaaaaaaaaaa")
    private val section = FeedSection("New music", listOf(song))
    @Test fun navigationParametersSurviveTheBridgeAndKeepCategoriesDistinct() {
        val rock = FeedEntry.parse(JSONObject().put("id", "FEmusic_category:ROCK").put("title", "Rock").put("kind", "browse").put("browseId", "FEmusic_category").put("params", "ROCK"))
        val pop = rock.copy(id = "FEmusic_category:POP", title = "Pop", params = "POP")
        val state = MusicState().browsePageLoaded(HomeFeed(listOf(FeedSection("Genres", listOf(rock, pop))), false), false)
        assertEquals("ROCK", state.collectionSections[0].items[0].params)
        assertEquals(listOf("Rock", "Pop"), state.collectionSections[0].items.map { it.title })
    }
    @Test fun anEmptyExploreRefreshPreservesTheLastGoodRowsAndReportsWhy() {
        val state = MusicState(explore = listOf(section), exploreBusy = true).exploreLoaded(emptyList())
        assertEquals(listOf(section), state.explore)
        assertFalse(state.exploreBusy)
        assertTrue(state.exploreError!!.contains("no Explore items"))
    }
    @Test fun genuineRelatedRowsRemainUsableWhenTheGeneralExploreRequestFails() {
        val state = MusicState().exploreLoaded(listOf(section), "General Explore unavailable")
        assertEquals(listOf(section), state.explore)
        assertEquals("General Explore unavailable", state.exploreError)
    }
    @Test fun aValidExploreRefreshReplacesOldRowsAndClearsTheProblem() {
        val state = MusicState(exploreError = "old failure").exploreLoaded(listOf(section))
        assertEquals(listOf(section), state.explore)
        assertNull(state.exploreError)
    }
    @Test fun categoryContinuationsAppendOnlyNewItemsAndKeepExistingContent() {
        val before = MusicState(collectionSections = listOf(section))
        val second = song.copy(id = "yt:bbbbbbbbbbb", videoId = "bbbbbbbbbbb", title = "Second song")
        val next = before.browsePageLoaded(HomeFeed(listOf(section.copy(items = listOf(song, second))), true), true)
        assertEquals(listOf(song, second), next.collectionSections.flatMap { it.items })
        assertTrue(next.collectionHasMore)
        assertEquals(listOf(section), next.browsePageLoaded(HomeFeed(listOf(section), false), false).collectionSections)
    }
    @Test fun nestedBrowseBackRestoresTheCategoryAndCursorWithoutRewindingPlayback() {
        val history = CollectionBrowsingHistory()
        val parent = FeedEntry("genres", "Moods & genres", "", "", "browse", "FEmusic_moods_and_genres", "")
        val old = MusicState(collectionEntry = parent, collectionSections = listOf(section), collectionHasMore = true)
        history.entering(old, true, 2, 1)
        val playing = song.asTrack()
        val frame = history.pop()!!
        val restored = frame.restore(MusicState(current = playing, playing = true, positionMs = 42_000, error = "Audio output failed", errorOrigin = ErrorOrigin.PLAYBACK))
        assertEquals(parent, restored.collectionEntry)
        assertEquals(listOf(section), restored.collectionSections)
        assertEquals(2, frame.offset)
        assertEquals(42_000L, restored.positionMs)
        assertTrue(restored.playing)
        assertEquals(playing, restored.current)
        assertEquals("Audio output failed", restored.error)
        assertEquals(ErrorOrigin.PLAYBACK, restored.errorOrigin)
        assertNull(history.pop())
    }
    @Test fun choosingAnotherExploreRootDoesNotRetainAnUnrelatedBackStack() {
        val history = CollectionBrowsingHistory()
        history.entering(MusicState(collectionEntry = song), true, null, 0)
        history.entering(MusicState(), false, null, 0)
        assertNull(history.pop())
    }

}
