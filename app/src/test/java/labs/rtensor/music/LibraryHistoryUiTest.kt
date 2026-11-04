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
class LibraryHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val local = Track("local:1", "My recording", "Me", "content://audio/document/1", source = Source.LOCAL)

    @Test fun settingsCountsTheSameUniqueRecordingsAsHistoryRatherThanRepeatedPlays() {
        val online = Track("yt:aaaaaaaaaaa", "Song", "Artist", "https://music.youtube.com/watch?v=aaaaaaaaaaa")
        val events = listOf(local, local.copy(id = "legacy-local"), online, online.copy(id = "aaaaaaaaaaa"))
            .mapIndexed { i, t -> PlayEvent(t, 10L + i, 30_000, false) }
        compose.setContent { MusicApp(MusicState(playEvents = events, history = listOf(local, online), reducedMotion = true), initialPage = "settings") }
        compose.onNodeWithText("2 recordings in History").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4 plays · 2 recent recordings").assertDoesNotExist()
    }

    @Test fun removingTheCurrentImportClearsSavedAndMiniPlayerTogether() {
        val state = mutableStateOf(MusicState(saved = listOf(local), current = local, queue = listOf(local),
            queueIndex = 0, playing = true, reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "library", onSave = { state.value = state.value.withoutLocalRecording(it) }) }
        compose.onNodeWithContentDescription("Remove My recording from app").performClick()
        compose.onNodeWithText("0 SAVED").assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.value.saved.isEmpty()); assertNull(state.value.current) }
        compose.onAllNodesWithText("My recording").assertCountEquals(0)
    }
}
