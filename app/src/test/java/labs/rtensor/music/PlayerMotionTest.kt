package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Compose frame-clock and pointer-input checks, not a phone frame-rate benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val one = Track("fixture:one", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val two = one.copy(id = "fixture:two", title = "Soft Signals", artist = "Hollow Coves")
    private val three = one.copy(id = "fixture:three", title = "Night Windows", artist = "Elian")
    private val queue = listOf(one, two, three)
    private fun initial() = MusicState(current = one, queue = queue, queueIndex = 0, durationMs = one.durationMs, theme = "light", reducedMotion = false)
    private fun cover(track: Track = one) = compose.onNodeWithContentDescription("Cover for ${track.title}. Swipe left for next, right for previous.")
    private fun frame(ms: Long = 32) {
        // Include Android measure/layout between frames, as a real choreographer does.
        // A single large virtual-clock jump can finish Compose work before layout starts it.
        var remaining = ms
        while (remaining > 0) {
            compose.waitForIdle()
            val step = minOf(16L, remaining)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            remaining -= step
        }
    }
    private fun x(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/animation-checks/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun dragLeft(node: SemanticsNodeInteraction, amount: Float) {
        node.performTouchInput { down(center) }
        for (step in 0..3) {
            node.performTouchInput { advanceEventTime(32); moveBy(Offset(-amount / 4, 0f)) }
            frame()
        }
    }

    @Test fun longCoverSwipeContinuesOutwardThenAdvancesExactlyOnce() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial()); var next = 0; var previous = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onNext = { next++; state.value = state.value.copy(current = two, queueIndex = 1) }, onPrevious = { previous++; state.value = state.value.copy(current = one, queueIndex = 0) }) }
        frame()
        val settledX = x(cover())
        dragLeft(cover(), 220f)
        val dragged = x(cover())
        capture("cover-drag")
        cover().performTouchInput { up() }
        frame(48)
        val released = x(cover())
        capture("cover-release-48ms")
        assertTrue("Cover reversed on release: dragged=$dragged, released=$released", released <= dragged + 1f)
        // During the crossfade the outgoing cover keeps its release pose and the
        // incoming cover starts centred; neither may snap the old image back.
        capture("cover-sequence-000")
        repeat(18) { index ->
            frame(16); capture("cover-sequence-${(index + 1).toString().padStart(3, '0')}")
            if (compose.onAllNodesWithContentDescription("Cover for ${one.title}. Swipe left for next, right for previous.").fetchSemanticsNodes().isNotEmpty())
                assertTrue(x(cover()) <= dragged + 1f)
            if (compose.onAllNodesWithContentDescription("Cover for ${two.title}. Swipe left for next, right for previous.").fetchSemanticsNodes().isNotEmpty())
                assertEquals(settledX, x(cover(two)), 1f)
        }
        frame(300)
        assertEquals(1, next)
        cover(two).assertIsDisplayed()
        cover().assertDoesNotExist()
        capture("cover-next-settled")
        dragLeft(cover(two), -220f)
        val rightDrag = x(cover(two))
        cover(two).performTouchInput { up() }; frame(48)
        assertTrue(x(cover(two)) >= rightDrag - 1f)
        frame(500); assertEquals(1, previous); cover(one).assertIsDisplayed()
    }

    @Test fun smallAndBlockedCoverSwipesReturnWithoutSkipping() {
        compose.mainClock.autoAdvance = false
        var next = 0
        compose.setContent { MusicApp(initial().copy(queueIndex = 2, current = three), initialPage = "now", onNext = { next++ }) }
        frame(); val start = x(cover(three))
        dragLeft(cover(three), 220f)
        cover(three).performTouchInput { up() }; frame(400)
        assertEquals(0, next); assertEquals(start, x(cover(three)), 1f)
        dragLeft(cover(three), 30f)
        cover(three).performTouchInput { up() }; frame(400)
        assertEquals(0, next); assertEquals(start, x(cover(three)), 1f)
    }

    @Test fun titleAndControlsCrossfadeThenSettleWithoutMovingOnBuffering() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial())
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        frame(); val play = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        capture("track-before")
        compose.runOnIdle { state.value = state.value.copy(current = two, queueIndex = 1, buffering = true) }
        capture("track-sequence-000")
        repeat(5) { index -> frame(16); capture("track-sequence-${(index + 1).toString().padStart(3, '0')}") }
        compose.onNodeWithText(one.title).assertExists(); compose.onNodeWithText(two.title).assertExists()
        capture("track-crossfade-80ms")
        repeat(15) { index -> frame(16); capture("track-sequence-${(index + 6).toString().padStart(3, '0')}") }
        compose.onNodeWithText(one.title).assertDoesNotExist(); compose.onNodeWithText(two.title).assertIsDisplayed()
        val loading = compose.onNodeWithContentDescription("Loading").fetchSemanticsNode().boundsInRoot
        assertEquals(play.center.y, loading.center.y, 1f)
        compose.runOnIdle { state.value = state.value.copy(buffering = false) }
        frame(300)
        assertEquals(play.center.y, compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot.center.y, 1f)
        capture("track-after")
    }

    @Test fun progressAdvancesWithFramesAndStopsWhenPaused() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial().copy(playing = true, positionMs = 72000))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        fun fraction() = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        frame(); compose.onNodeWithContentDescription("Pause").assertIsDisplayed(); val before = fraction()
        frame(1000); val after = fraction()
        assertTrue(after > before); assertEquals(1000f / one.durationMs, after - before, .001f)
        compose.runOnIdle { state.value = state.value.copy(playing = false, positionMs = 73000) }
        frame(); val paused = fraction(); frame(4000)
        assertEquals(paused, fraction(), .00001f)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist(); compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loadingTrack = two) }
        frame(32)
        compose.onNodeWithContentDescription("Playback position").assertIsNotEnabled()
        assertEquals(0f, fraction(), .00001f)
    }

    @Test fun timelineDragSeeksOnceAndStaysWithinDuration() {
        compose.mainClock.autoAdvance = false
        var seeks = 0; var target = -1L
        val state = mutableStateOf(initial())
        compose.setContent { MusicApp(state.value, initialPage = "now", onSeek = { seeks++; target = it; state.value = state.value.copy(positionMs = it) }) }
        frame()
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.performTouchInput { swipe(Offset(width * .2f, centerY), Offset(width * .8f, centerY), durationMillis = 250) }
        frame(300)
        assertEquals(1, seeks); assertTrue(target in 140000..200000)
        assertEquals(target.toFloat() / one.durationMs, timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
    }

    @Test fun reducedMotionChangesTrackWithoutLeavingOutgoingContent() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial().copy(reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        frame()
        compose.runOnIdle { state.value = state.value.copy(current = two, queueIndex = 1) }
        frame(32)
        compose.onNodeWithText(one.title).assertDoesNotExist()
        cover().assertDoesNotExist(); cover(two).assertIsDisplayed()
    }

    @Test fun inlineQueueJumpsToTheOriginalIndexWithoutRebuildingPlayback() {
        var skipped = -1; var replayed = false
        compose.setContent { MusicApp(initial().copy(reducedMotion = true), initialPage = "now", onSkipTo = { skipped = it }, onPlay = { _, _ -> replayed = true }) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(three.title))
        compose.onNodeWithText(three.title).performClick()
        assertEquals(2, skipped); assertFalse(replayed)
    }

    @Test fun coverVerticalSwipeScrollsToTheInlineQueue() {
        compose.setContent { MusicApp(initial().copy(reducedMotion = true), initialPage = "now") }
        cover().performTouchInput { swipeUp(durationMillis = 400) }
        compose.onNodeWithText("Up next").assertIsDisplayed()
        compose.onNodeWithText(two.title).assertIsDisplayed()
        capture("queue-under-player")
    }

    @Test fun miniPlayerSwipeUpOpensAndBackReturnsToTheMiniPlayer() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicApp(initial()) }
        frame()
        val mini = compose.onNodeWithText(one.title)
        mini.performTouchInput { down(center); advanceEventTime(30); moveBy(Offset(0f, -100f)) }
        frame(); mini.performTouchInput { up() }
        frame(80); capture("open-player-80ms")
        frame(500); cover().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        frame(500); capture("mini-player-returned")
        compose.onNodeWithText("For your ears").assertIsDisplayed()
        compose.onNodeWithText(one.title).assertIsDisplayed()
    }

    @Test fun miniPlayerSwipeSkipsOnceAndItsNextButtonHonoursTheQueueEnd() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial()); var next = 0
        compose.setContent { MusicApp(state.value, onNext = { next++; state.value = state.value.copy(current = three, queueIndex = 2) }) }
        frame()
        val mini = compose.onNodeWithText(one.title)
        mini.performTouchInput { down(center); advanceEventTime(32); moveBy(Offset(-120f, 0f)) }
        frame(); mini.performTouchInput { up() }; frame(500)
        assertEquals(1, next); compose.onNodeWithText(three.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next recording").assertIsNotEnabled()
    }

    @Test fun homeAndExplorePullToRefreshFireOnceAndIgnorePullsWhileBusy() {
        compose.mainClock.autoAdvance = false
        val entry = FeedEntry(one.id, one.title, one.artist, "", "song", "", "aaaaaaaaaaa")
        val state = mutableStateOf(MusicState(feed = listOf(FeedSection("Home picks", listOf(entry))), explore = listOf(FeedSection("Explore picks", listOf(entry)))))
        var home = 0; var explore = 0
        compose.setContent { MusicApp(state.value, onRefreshFeed = { home++; state.value = state.value.copy(feedBusy = true) }, onRefreshExplore = { explore++; state.value = state.value.copy(exploreBusy = true) }) }
        frame()
        fun pull(title: String) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeDown(durationMillis = 600) }
            frame(600)
        }
        pull("For your ears"); assertEquals(1, home)
        pull("For your ears"); assertEquals(1, home)
        compose.onNodeWithContentDescription("Explore").performClick(); frame(600)
        compose.runOnIdle { state.value = state.value.copy(feedBusy = false) }; frame(600)
        pull("Home picks"); assertEquals(2, home)
        pull("Home picks"); assertEquals(2, home)
        compose.onNodeWithContentDescription("Discover").performClick(); frame(600)
        pull("Explore picks"); assertEquals(1, explore)
        pull("Explore picks"); assertEquals(1, explore)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h740dp-mdpi")
    fun compactLargeTextKeepsControlsAndQueueReachable() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            MusicApp(initial().copy(current = one.copy(title = "An unusually long recording title that needs several lines"), reducedMotion = true), initialPage = "now")
        } }
        compose.onNodeWithContentDescription("Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(three.title))
        compose.onNodeWithText(three.title).assertIsDisplayed()
        capture("player-compact-large-text")
    }
    @Test fun softSkipAnimationUsesTheNewQueueIndexWhenTheSameSongWasRequeued() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(initial().copy(current = three, queueIndex = 2, look = labs.rtensor.music.data.LookChoice.SOFT))
        var nextCalls = 0
        compose.setContent { MusicApp(state.value, initialPage = "now", onNext = {
            nextCalls++; state.value = state.value.copy(current = two, queueIndex = 1)
        }, onPrevious = { state.value = state.value.copy(current = three, queueIndex = 0) }) }
        frame(32)
        val settled = x(cover(three))
        // Starting the same recording on its own resets the queue index without changing its id.
        compose.runOnIdle { state.value = state.value.copy(queue = listOf(three, two), queueIndex = 0) }
        frame(32)
        compose.onNodeWithContentDescription("Next").performClick()
        frame(32)
        assertEquals(1, nextCalls)
        assertTrue("Next must arrive from the right after requeueing", x(cover(two)) > settled + 1f)
        frame(400)
        assertEquals(settled, x(cover(two)), .5f)
        compose.onNodeWithContentDescription("Previous").performClick()
        frame(32)
        assertTrue("Previous must arrive from the left", x(cover(three)) < settled - 1f)
        frame(400)
        assertEquals(settled, x(cover(three)), .5f)
    }

}
