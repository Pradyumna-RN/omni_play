package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import labs.rtensor.music.data.Track
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
class PlaybackClockUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:clock", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 200000)
    private val screen = MusicState(current = track, durationMs = 200000, queue = listOf(track), queueIndex = 0, reducedMotion = true)

    @Test fun timelineAndSeekingUseTheClockWithoutUpdatingTheWholeScreenSnapshot() {
        val clock = MutableStateFlow(0L); var sought = -1L
        compose.setContent { MusicApp(screen, initialPage = "now", playbackPosition = clock, onSeek = { sought = it; clock.value = it }) }
        compose.runOnIdle { clock.value = 100000 }
        compose.onNodeWithText("1:40").assertIsDisplayed()
        val timeline = compose.onNodeWithContentDescription("Playback position")
        assertEquals(.5f, timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        timeline.performSemanticsAction(SemanticsActions.SetProgress) { it(.25f) }
        compose.onNodeWithText("0:50").assertIsDisplayed()
        compose.runOnIdle { assertEquals(50000L, sought); assertEquals(0L, screen.positionMs) }
    }

    @Test fun miniPlayerAndFullPlayerShareTheClockAcrossNavigation() {
        val clock = MutableStateFlow(0L)
        compose.setContent { MusicApp(screen, playbackPosition = clock) }
        compose.runOnIdle { clock.value = 100000 }
        val mini = compose.onNodeWithContentDescription("Mini player progress", useUnmergedTree = true)
        assertEquals(.5f, mini.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        compose.onNodeWithText("Afterimage").performClick()
        compose.onNodeWithText("1:40").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0L, screen.positionMs) }
    }
}
