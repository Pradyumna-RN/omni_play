package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
class HomePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val one = Track("yt:aaaaaaaaaaa", "Akale", "Release", "https://music.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 180000)
    private val two = Track("yt:bbbbbbbbbbb", "Another song", "Another artist", "https://music.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 210000)

    @Test fun lastPlayedPausesAndResumesTheCurrentSongWithoutRebuildingItsQueue() {
        val alias = one.copy(id = "aaaaaaaaaaa", artist = "Updated artist")
        val state = mutableStateOf(MusicState(history = listOf(one, two), current = alias, playing = true,
            positionMs = 42000, queue = listOf(alias, two), queueIndex = 0, reducedMotion = true))
        var toggles = 0; var starts = 0
        compose.setContent { MusicApp(state.value, initialPage = "explore", onToggle = { toggles++; state.value = state.value.copy(playing = !state.value.playing) }, onPlay = { _, _ -> starts++ }) }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Play").assertIsDisplayed().performClick()
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, toggles); assertEquals(0, starts)
            assertEquals(42000L, state.value.positionMs); assertEquals(listOf(alias, two), state.value.queue)
            state.value = state.value.copy(buffering = true, playing = false)
        }
        compose.onNodeWithText("Loading").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(buffering = false, playing = true) }
        compose.onNodeWithText("Pause").assertIsDisplayed()
    }

    @Test fun lastPlayedStartsOnlyThatSongWhenAnotherSongIsPlaying() {
        val state = MusicState(history = listOf(one, two), current = two, playing = true, queue = listOf(two), queueIndex = 0, reducedMotion = true)
        var selected: Track? = null; var queue = emptyList<Track>(); var toggles = 0
        compose.setContent { MusicApp(state, initialPage = "explore", onPlay = { t, q -> selected = t; queue = q }, onToggle = { toggles++ }) }
        compose.onNodeWithText("Play").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(one, selected); assertEquals(listOf(one), queue); assertEquals(0, toggles) }
    }

    @Test fun recentlyPlayedSelectionDoesNotPassHistoryAsTheQueue() {
        var queue = emptyList<Track>()
        compose.setContent { MusicApp(MusicState(history = listOf(one, two), reducedMotion = true), initialPage = "explore", onPlay = { _, q -> queue = q }) }
        compose.onNodeWithText(two.title).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(two), queue) }
    }

    @Test fun mixedSongSelectionSeedsRadioAndPlayMixKeepsTheExplicitMix() {
        val state = mutableStateOf(MusicState(forYou = listOf(one, two), reducedMotion = true))
        val queues = mutableListOf<List<Track>>()
        compose.setContent { MusicApp(state.value, initialPage = "explore", onPlay = { t, q -> queues += q; state.value = state.value.copy(current = t, queue = q, queueIndex = 0) }) }
        // Home's shelves are lazy items: bring each one into view before using it.
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("unheard")
        compose.onNodeWithText(two.title).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("for-you")
        compose.onNodeWithText("Play mix").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(two), listOf(one, two)), queues) }
    }

    @Test fun savedSongSelectionSeedsRadioAndPlayAllKeepsTheSavedList() {
        val state = mutableStateOf(MusicState(saved = listOf(one, two), reducedMotion = true))
        val queues = mutableListOf<List<Track>>()
        compose.setContent { MusicApp(state.value, initialPage = "library", onPlay = { t, q -> queues += q; state.value = state.value.copy(current = t, queue = q, queueIndex = 0) }) }
        compose.onNodeWithText(two.title).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(two), listOf(one, two)), queues) }
    }

    @Test fun historyAndSearchSelectionsDoNotQueueTheOtherDisplayedResults() {
        val state = mutableStateOf(MusicState(playEvents = listOf(PlayEvent(one, 1800000000000, 30000, false),
            PlayEvent(two, 1799999999000, 30000, false)), reducedMotion = true))
        val queues = mutableListOf<List<Track>>()
        compose.setContent { MusicApp(state.value, initialPage = "library", onPlay = { t, q -> queues += q; state.value = state.value.copy(current = t, queue = q, queueIndex = 0) }) }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText(two.title).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { state.value = state.value.copy(query = "songs", searched = true, results = listOf(one, two)) }
        compose.onNodeWithText(one.title).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(two), listOf(one)), queues) }
    }

    @Test fun recommendationErrorsHaveARetryActionWithoutRestartingAudio() {
        var retries = 0; var starts = 0
        compose.setContent { MusicApp(MusicState(current = one, queue = listOf(one), queueIndex = 0,
            radioError = "Couldn’t load YouTube Music recommendations.", reducedMotion = true), initialPage = "now",
            onRetryRadio = { retries++ }, onPlay = { _, _ -> starts++ }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Retry recommendations"))
        compose.onNodeWithText("Retry recommendations").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, starts) }
    }
}
