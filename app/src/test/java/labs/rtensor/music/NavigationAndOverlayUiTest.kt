package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import labs.rtensor.music.data.*
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Navigation, back handling, overlays and error actions of the Compose shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationAndOverlayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("aaaaaaaaaaa", "Afterimage", "Serein", "https://music.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 200_000)
    private val other = Track("bbbbbbbbbbb", "Second light", "Serein", "https://music.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 180_000)

    /** Waits for the last tap to settle first, as a person's gesture would. */
    private fun systemBack() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }

    @Test fun systemBackFromATabMakesHomeTheRootForLaterPages() {
        compose.setContent { MusicApp(MusicState(reducedMotion = true)) }
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Explore.").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        // Settings opened from Home must return to Home, not to the tab left a moment ago.
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Settings.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        // System back from Settings resolves to the same page as the header's back.
        compose.onNodeWithContentDescription("Open settings").performClick()
        systemBack()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test fun switchingTabsKeepsAPlaybackErrorWhenThereIsNoSearchToClear() {
        val queries = mutableListOf<String>()
        val error = "YouTube rejected the audio stream (HTTP 403)."
        val state = mutableStateOf(MusicState(current = track, queue = listOf(track), queueIndex = 0, error = error, reducedMotion = true))
        compose.setContent {
            MusicApp(state.value, onQuery = { q ->
                queries += q
                // Mirrors MusicViewModel.setQuery: a blank query also clears the shared error.
                state.value = if (q.isBlank()) state.value.copy(query = "", searched = false, error = null) else state.value.copy(query = q)
            })
        }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.runOnIdle { assertTrue("No search was open, so nothing must be cleared: $queries", queries.isEmpty()) }
        // An open search is still cleared when you change tabs.
        compose.onNodeWithContentDescription("Search music").performTextInput("Serein")
        compose.onNodeWithContentDescription("Home").performClick()
        compose.runOnIdle { assertEquals("", queries.last()) }
    }
}
