package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition.*
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Apple is the default design: the platform's type and grouped surfaces, system blue until an accent is chosen, with every later behaviour kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppleLookUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)
    private val surround = listOf(FL, FR, FC, LFE, BL, BR)
    private fun apple() = MusicState(look = LookChoice.APPLE, reducedMotion = true, theme = "light")

    @Test fun settingsOffersThreeLooksAndAppleFollowsTheChosenAccent() {
        val state = mutableStateOf(apple().copy(look = LookChoice.ROOM, accent = AccentChoice.COBALT))
        val chosen = mutableListOf<LookChoice>()
        compose.setContent { MusicApp(state.value, initialPage = "settings",
            onLook = { chosen += it; state.value = state.value.copy(look = it) },
            onAccent = { state.value = state.value.copy(accent = it) }) }
        for (name in listOf("Apple", "Listening room", "Classic")) compose.onNodeWithContentDescription("$name look").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Apple look").performClick()
        compose.runOnIdle { assertEquals(listOf(LookChoice.APPLE), chosen) }
        compose.onNodeWithContentDescription("Apple look").assertIsSelected()
        compose.onNodeWithContentDescription("Cobalt accent colour").performScrollTo().assertIsSelected()
        compose.onNodeWithText("The one strong colour on Play, highlights and the dock.").performScrollTo().assertExists()
        // Choosing "From the cover" keeps the look and points at system blue as the fallback.
        compose.onNodeWithContentDescription("From the cover accent colour").performClick()
        compose.onNodeWithText("Taken from the artwork of the song that is playing. System blue is used when there is none.").assertExists()
        // Apple's own description, and its large title without Classic's full stop or mono caption.
        compose.onNodeWithText("system blue unless you pick an accent", substring = true).assertExists()
        compose.onNodeWithText("Settings.").assertDoesNotExist()
        compose.onNodeWithText("SETTINGS").assertDoesNotExist()
    }

    @Test fun appleHomeHasTheGroupedHeroAndKeepsTodaysBehaviour() {
        var played = 0
        compose.setContent { MusicApp(apple(), onPlay = { _, _ -> played++ }) }
        compose.onNodeWithText("Room to listen").assertIsDisplayed()
        compose.onNodeWithText("Start with a song").assertIsDisplayed()
        compose.onNodeWithText("Find your\nnext repeat").assertIsDisplayed()
        compose.onNodeWithText("Pick something to fill the room").assertDoesNotExist()
        // The hero's action focuses the search field rather than starting playback.
        compose.onNodeWithText("Find music").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(0, played) }
        compose.onNodeWithContentDescription("Search music").assertIsFocused()
        compose.onNodeWithText("Room to listen").assertIsDisplayed()
        // The dock keeps its tab roles and selection.
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        compose.onNodeWithContentDescription("Explore").assertIsNotSelected()
    }

    @Test fun applePlayerShowsTheCoverWashAndTheSameControls() {
        val state = mutableStateOf(apple().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, positionMs = 72000, durationMs = 224000,
            audioProcessing = AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        val calls = mutableListOf<String>()
        compose.setContent { MusicApp(state.value, initialPage = "now", onToggle = { calls += "toggle" }, onNext = { calls += "next" }, onPrevious = { calls += "previous" },
            onSave = { calls += "save" }, onSeek = { calls += "seek" }, onProcessing = { calls += "processed=$it"
                state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = it)) }) }
        // The blurred wash of the cover sits behind everything and takes no touches.
        compose.onNodeWithTag("apple-cover-wash", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Now playing").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Cover for Afterimage. Swipe left for next, right for previous, down to minimize.").assertExists()
        compose.onNodeWithContentDescription("Playback position").assertExists()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertEquals(listOf("toggle", "previous", "next", "save"), calls) }
        // Original/Processed: one switch for TalkBack, laid out as the platform's two segments.
        calls.clear()
        val processed = compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Original audio").assertDoesNotExist()
        compose.onNodeWithText("Original").assertExists()
        processed.performClick()
        compose.runOnIdle { assertEquals(listOf("processed=false"), calls) }
        compose.onNodeWithContentDescription("Processed audio").assertIsOff()
        compose.onNodeWithText("Switching to original…").assertExists()
        compose.onNodeWithText("Up next").performScrollTo().assertIsDisplayed()
    }

    @Test fun appleBufferingKeepsTransportUsableButPlayWaitsWhileFindingAudio() {
        val state = mutableStateOf(apple().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, buffering = true, durationMs = 224000))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        compose.onNodeWithContentDescription("Loading").assertIsEnabled()
        compose.onNodeWithContentDescription("Next").assertIsEnabled()
        compose.onNodeWithContentDescription("Previous").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(buffering = false, resolving = true) }
        compose.onNodeWithContentDescription("Loading").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(resolving = false, queueIndex = 1, current = other) }
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
    }

    @Test fun appleMissingDspStillExplainsTheDisabledChoice() {
        compose.setContent { MusicApp(apple().copy(current = track, durationMs = 224000,
            audioProcessing = AudioProcessingState(available = false, unavailableReason = "The DSP library is not included")), initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Processing unavailable · The DSP library is not included").assertExists()
    }

    /** Dropping the cover down hands the player back to the mini player at the bottom of Home. */
    @Test fun applePlayerSwipesDownToTheMinimizedPlayer() {
        val state = mutableStateOf(apple().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, durationMs = 224000))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        compose.onNodeWithText("Now playing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cover for Afterimage. Swipe left for next, right for previous, down to minimize.")
            .performTouchInput { swipeDown() }
        compose.onNodeWithText("Now playing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mini player progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        // And the dock is back, so the swipe landed on Home, not on an in-between page.
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
    }
}
