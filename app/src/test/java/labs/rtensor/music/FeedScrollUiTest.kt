package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.*
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun song(i: Int) = FeedEntry("yt:${"%011d".format(i)}", "Song $i", "Artist $i", "", "song", "", "%011d".format(i))
    private val verticalMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun vertical() = compose.onAllNodes(verticalMatcher).onFirst()
    private fun songText(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(verticalMatcher))

    @Test fun homeReturnsToTheSameSongAfterPlaybackUpdatesHistory() {
        val sections = (0..24).map { FeedSection("Shelf $it", listOf(song(it))) }
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, feed = sections, current = song(99).asTrack()))
        compose.setContent { MusicApp(state.value, initialPage = "home", onPlay = { track, _ -> state.value = state.value.copy(current = track, history = listOf(track), playing = true) }) }
        vertical().performScrollToKey("provider-shelf:8:Shelf 8")
        val before = songText("Song 8").getUnclippedBoundsInRoot()
        songText("Song 8").performClick()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        songText("Song 8").assertIsDisplayed()
        val after = songText("Song 8").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, after.top.value, 1f)
    }
    @Test fun exploreKeepsItsVerticalAndHorizontalPositionAcrossThePlayer() {
        val sections = (0..12).map { i -> FeedSection("Explore $i", (0..11).map { song(i * 20 + it) }) }
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, explore = sections, current = song(999).asTrack()))
        compose.setContent { MusicApp(state.value, initialPage = "explore", onPlay = { track, _ -> state.value = state.value.copy(current = track, playing = true) }) }
        compose.onNodeWithContentDescription("Discover").performClick()
        vertical().performScrollToKey("provider-shelf:5:Explore 5")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasText("Song 100"))).performScrollToIndex(2)
        val title = "Song 108"
        val before = songText(title).getUnclippedBoundsInRoot()
        songText(title).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        songText(title).assertIsDisplayed()
        val after = songText(title).getUnclippedBoundsInRoot()
        assertEquals(before.top.value, after.top.value, 1f)
        assertEquals(before.left.value, after.left.value, 1f)
    }
    @Test fun appendedPagesKeepExistingShelvesAndTheirPosition() {
        val initial = (0..18).map { FeedSection("Shelf $it", listOf(song(it))) }
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, feed = initial))
        compose.setContent { MusicApp(state.value, initialPage = "home") }
        vertical().performScrollToKey("provider-shelf:8:Shelf 8")
        val before = songText("Song 8").getUnclippedBoundsInRoot()
        compose.runOnIdle { state.value = state.value.withMoreHomeFeed(HomeFeedMore(listOf(song(200).asTrack()), false, listOf(FeedSection("New page", listOf(song(200)))))) }
        val after = songText("Song 8").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, after.top.value, 1f)
        compose.runOnIdle { assertEquals(initial, state.value.feed) }
        vertical().performScrollToKey("provider-shelf:19:New page")
        songText("Song 200").assertIsDisplayed()
    }
    @Test fun anAutomaticPageFailureWaitsForRetryWithoutRefreshingTheFeed() {
        var more = 0; var refresh = 0
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, feed = listOf(FeedSection("Songs", listOf(song(1)))), feedHasMore = true))
        compose.setContent { MusicApp(state.value, initialPage = "home", onMoreFeed = { more++; state.value = state.value.copy(feedError = "Connection lost") }, onRefreshFeed = { refresh++ }) }
        vertical().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { more == 1 }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, more); assertEquals(0, refresh) }
        compose.onNodeWithText("Retry loading more").performClick()
        compose.runOnIdle { assertEquals(2, more); assertEquals(0, refresh) }
    }
}
