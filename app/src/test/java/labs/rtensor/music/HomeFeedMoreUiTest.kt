package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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

    /** Home is a lazy list: bring its "More from your feed" item into view. */
    private fun scrollToMore() = compose.onAllNodes(hasScrollToKeyAction()).filter(hasAnyDescendant(hasText("For your ears"))).onFirst().performScrollToKey("more")

    @Test fun moreFromYourFeedSwapsInTheNextSetOfSongs() {
        val feed = PagedFeed(listOf(firstPage, (0..2).map { song("p2-$it") }))
        val state = mutableStateOf(start(feed).copy(reducedMotion = true))
        var taps = 0
        compose.setContent { MusicApp(state.value, onMoreFeed = { taps++; state.value = tap(state.value, feed) }) }
        scrollToMore()
        compose.onNodeWithText("Song p2-0").assertDoesNotExist()
        compose.onAllNodesWithText("Song p1-5").assertCountEquals(1)
        compose.onNodeWithText("More from your feed").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
        // The group now shows the next set instead of the first one.
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("unheard")
        for (i in 0..2) compose.onNodeWithText("Song p2-$i").assertExists()
        compose.onNodeWithText("Song p1-5").assertDoesNotExist()
        compose.onNodeWithText("More from your feed").assertDoesNotExist()
    }

    @Test fun whenTheFeedHasNothingNewHomeSaysSo() {
        val repeat = listOf(song("f0"), song("p1-4"))
        val feed = PagedFeed(listOf(firstPage, repeat, repeat, repeat, listOf(song("p5-0"))))
        val state = mutableStateOf(start(feed).copy(reducedMotion = true))
        compose.setContent { MusicApp(state.value, onMoreFeed = { state.value = tap(state.value, feed) }) }
        scrollToMore(); compose.onNodeWithText("More from your feed").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("More from your feed").assertDoesNotExist()
        compose.onNodeWithText("Nothing new in your feed right now", substring = true).assertExists()
    }
}
