package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.*
import labs.rtensor.music.imports.*
import labs.rtensor.music.ui.*
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
class PlaylistImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun libraryPlaylistsOffersImportAndTheAccountSources() {
        val state = mutableStateOf(MusicState(reducedMotion = true))
        var source = ""
        compose.setContent { MusicApp(state.value, initialPage = "library", songActions = SongActionCallbacks(
            showPlaylistImport = { state.value = state.value.copy(playlistImport = PlaylistImportState(visible = true)) },
            importPlaylistAccount = { source = it })) }
        compose.onNodeWithContentDescription("Playlists").performClick()
        compose.onNodeWithText("Import playlist").performScrollTo().performClick()
        compose.onNodeWithText("Import playlists").assertIsDisplayed()
        compose.onNodeWithText("Spotify").performClick()
        assertEquals("Spotify", source)
        compose.onNodeWithText("YouTube Music").performClick()
        assertEquals("YouTube Music", source)
    }
    @Test fun previewKeepsRepeatedSongsAndRequiresAnExplicitImport() {
        val track = Track("yt:aaaaaaaaaaa", "Repeat song", "Artist", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val state = mutableStateOf(MusicState(reducedMotion = true, playlistImport = PlaylistImportState(visible = true,
            preview = listOf(PlaylistImportPreview("Copy", listOf(track, track))), unavailable = 2)))
        var commits = 0
        compose.setContent { MusicApp(state.value, songActions = SongActionCallbacks(confirmPlaylistImport = { commits++ })) }
        assertEquals(0, commits)
        compose.onNodeWithText("2 songs · copy on this device").assertIsDisplayed()
        compose.onNodeWithText("2 unavailable entries will be skipped.").assertIsDisplayed()
        compose.onNodeWithText("Import to Music").performClick(); assertEquals(1, commits)
        compose.runOnIdle { state.value = state.value.copy(playlistImport = state.value.playlistImport.copy(busy = true, saving = true)) }
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsNotEnabled()
    }
    @Test fun localPlaylistExplainsAndOffersTheAudioFolderGrant() {
        var chosen = 0
        compose.setContent { MusicApp(MusicState(reducedMotion = true, playlistImport = PlaylistImportState(visible = true, needsFolder = true,
            message = "Choose its audio folder. Local files stay on your device.")), songActions = SongActionCallbacks(choosePlaylistFolder = { chosen++ })) }
        compose.onNodeWithText("Choose audio folder").performClick()
        assertEquals(1, chosen)
    }
}
