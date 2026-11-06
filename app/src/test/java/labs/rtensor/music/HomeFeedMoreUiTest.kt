package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.HomeFeedMoreTest.Companion.firstPage
import labs.rtensor.music.HomeFeedMoreTest.Companion.song
import labs.rtensor.music.HomeFeedMoreTest.Companion.start
import labs.rtensor.music.HomeFeedMoreTest.Companion.tap
import labs.rtensor.music.HomeFeedMoreTest.PagedFeed
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
class HomeFeedMoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun home() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    @Test fun reachingTheEndAppendsTheNextPageAndKeepsTheOpeningSongs() {
        val feed = PagedFeed(listOf(firstPage, (0..2).map { song("p2-$it") }))
        val state = mutableStateOf(start(feed).copy(reducedMotion = true))
        var requests = 0
        compose.setContent { MusicApp(state.value, initialPage = "home", onMoreFeed = { requests++; state.value = tap(state.value, feed) }) }
        home().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { requests == 1 }
        compose.runOnIdle { assertEquals(firstPage, state.value.feed.single().items) }
        home().performScrollToKey("provider-shelf:0:Page 0")
        compose.onNodeWithText("Song p1-0").assertExists()
        home().performScrollToKey("provider-shelf:1:Page 1")
        for (i in 0..2) compose.onNodeWithText("Song p2-$i").assertExists()
        home().performScrollToKey("feed-tail")
        compose.onNodeWithText("You’re caught up").assertIsDisplayed()
        compose.onNodeWithText("More from your feed").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun duplicatePagesKeepAdvancingUntilYouTubeActuallyEnds() {
        val repeat = listOf(song("f0"), song("p1-4"))
        val feed = PagedFeed(listOf(firstPage, repeat, repeat, repeat, listOf(song("p5-0"))))
        val state = mutableStateOf(start(feed).copy(reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "home", onMoreFeed = { state.value = tap(state.value, feed) }) }
        home().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { !state.value.feedHasMore }
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2, 3, 4), feed.served)
            assertEquals(listOf("Song p5-0"), state.value.feedMore.map { it.title })
        }
        home().performScrollToKey("feed-tail")
        compose.onNodeWithText("You’re caught up").assertIsDisplayed()
        compose.onNodeWithText("More from your feed").assertDoesNotExist()
    }
}
