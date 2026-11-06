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

/** Soft is the 0.3.31 design as a look: its layout and type, with every later behaviour kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoftLookUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)
    private val surround = listOf(FL, FR, FC, LFE, BL, BR)
    private fun soft() = MusicState(look = LookChoice.SOFT, reducedMotion = true, theme = "light")

    @Test fun settingsOffersThreeLooksAndSoftKeepsItsRaspberry() {
        val state = mutableStateOf(soft().copy(look = LookChoice.ROOM, accent = AccentChoice.COBALT))
        val chosen = mutableListOf<LookChoice>()
        compose.setContent { MusicApp(state.value, initialPage = "settings", onLook = { chosen += it; state.value = state.value.copy(look = it) }) }
        for (name in listOf("Listening room", "Soft", "Classic")) compose.onNodeWithContentDescription("$name look").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Soft look").performClick()
        compose.runOnIdle { assertEquals(listOf(LookChoice.SOFT), chosen) }
        compose.onNodeWithContentDescription("Soft look").assertIsSelected()
        compose.onNodeWithText("Soft always uses its raspberry", substring = true).assertExists()
        compose.onNodeWithContentDescription("Cobalt accent colour").assertDoesNotExist()
        // The 0.3.31 caption in the header; the headline reads as written.
        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
        compose.onNodeWithText("Settings").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun softHomeHasTheOriginalHeroAndKeepsTodaysBehaviour() {
        val played = mutableListOf<Pair<Track, List<Track>>>(); var searched = false
        compose.setContent { MusicApp(soft(), initialPage = "explore", onSearch = { searched = true }, onPlay = { t, q -> played += t to q }) }
        compose.onNodeWithText("Explore").assertIsDisplayed()
        compose.onNodeWithText("START WITH A SONG").assertIsDisplayed()
        compose.onNodeWithText("Find your\nnext repeat.").assertIsDisplayed()
        compose.onNodeWithText("Pick something to fill the room").assertDoesNotExist()
        compose.onNodeWithText("Find music").performClick()
        // The dock keeps its tab roles and selection.
        compose.onNodeWithContentDescription("Home").assertIsNotSelected()
        compose.onNodeWithContentDescription("Explore").assertIsSelected()
    }

    @Test fun softPlayerShowsTheCoverWashAndTheSameControls() {
        val state = mutableStateOf(soft().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, positionMs = 72000, durationMs = 224000,
            audioProcessing = AudioProcessingState(available = true, report = DownmixReport(48000, surround))))
        val calls = mutableListOf<String>()
        compose.setContent { MusicApp(state.value, initialPage = "now", onToggle = { calls += "toggle" }, onNext = { calls += "next" }, onPrevious = { calls += "previous" },
            onSave = { calls += "save" }, onSeek = { calls += "seek" }, onProcessing = { calls += "processed=$it"
                state.value = state.value.copy(audioProcessing = state.value.audioProcessing.copy(selectedProcessed = it)) }) }
        // The soft, blurred wash of the cover sits behind everything and takes no touches.
        compose.onNodeWithTag("soft-cover-wash", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Cover for Afterimage. Swipe left for next, right for previous.").assertExists()
        compose.onNodeWithContentDescription("Playback position").assertExists()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertEquals(listOf("toggle", "previous", "next", "save"), calls) }
        // Original/Processed: 0.3.31's two chips, with today's behaviour and explanation.
        calls.clear()
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Original audio").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf("processed=false"), calls) }
        compose.onNodeWithContentDescription("Original audio").assertIsSelected()
        compose.onNodeWithText("Switching to original…").assertExists()
        compose.onNodeWithText("Up next").performScrollTo().assertIsDisplayed()
    }

    @Test fun softBufferingKeepsTransportUsableButPlayWaitsWhileFindingAudio() {
        val state = mutableStateOf(soft().copy(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, buffering = true, durationMs = 224000))
        compose.setContent { MusicApp(state.value, initialPage = "now") }
        compose.onNodeWithContentDescription("Loading").assertIsEnabled()
        compose.onNodeWithContentDescription("Next").assertIsEnabled()
        compose.onNodeWithContentDescription("Previous").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(buffering = false, resolving = true) }
        compose.onNodeWithContentDescription("Loading").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(resolving = false, queueIndex = 1, current = other) }
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
    }

    @Test fun softMissingDspStillExplainsTheDisabledChoice() {
        compose.setContent { MusicApp(soft().copy(current = track, durationMs = 224000,
            audioProcessing = AudioProcessingState(available = false, unavailableReason = "The DSP library is not included")), initialPage = "now") }
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Original audio").assertIsNotEnabled()
        compose.onNodeWithText("Processing unavailable · The DSP library is not included").assertExists()
    }
}
