package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.SearchSuggestion
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.UserPlaylist
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.SongActionCallbacks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = Track("yt:aaaaaaaaaaa", "First song", "First artist", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 180000)
    private val second = Track("yt:bbbbbbbbbbb", "Second song", "Second artist", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 200000)
    private fun base() = MusicState(theme = "light", reducedMotion = true, look = LookChoice.SOFT)

    @Test fun longPressDoesNotPlayAndTheMenuDispatchesExactlyOneQueueAction() {
        val calls = mutableListOf<String>()
        compose.setContent { MusicApp(base().copy(query = "First", searched = true, results = listOf(first)),
            onPlay = { _, _ -> calls += "play" }, songActions = SongActionCallbacks(playNext = { calls += "next:${it.id}" }, addToQueue = { calls += "queue:${it.id}" })) }
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("Play next").assertIsDisplayed()
        compose.onNodeWithText("Add to playlist").assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Play next").performClick()
        compose.runOnIdle { assertEquals(listOf("next:${first.id}"), calls) }
        compose.onNodeWithContentDescription("More options for ${first.title}").performClick()
        compose.onNodeWithText("Add to queue").performClick()
        compose.runOnIdle { assertEquals(listOf("next:${first.id}", "queue:${first.id}"), calls) }
    }
    @Test fun playlistCreationDefaultsToYouTubeSyncAndPassesTheEnteredName() {
        val state = mutableStateOf(base().copy(account = AccountState(connected = true)))
        val created = mutableListOf<Triple<String, List<Track>, Boolean>>()
        compose.setContent { MusicApp(state.value, initialPage = "library", songActions = SongActionCallbacks(createPlaylist = { name, tracks, remote ->
            created += Triple(name, tracks, remote); state.value = state.value.copy(actionMessage = "Playlist created")
        })) }
        compose.onNodeWithContentDescription("Playlists").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Create playlist").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Playlist name").performTextInput("My mix")
        compose.onNodeWithText("Private on your YouTube Music account").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Create playlist").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("My mix", emptyList<Track>(), true)), created)
            state.value = state.value.copy(actionMessage = null)
        }
        compose.mainClock.autoAdvance = true
    }
    @Test fun addToExistingPlaylistIdentifiesItsAccountAndDoesNotPretendToSaveLocally() {
        val playlist = UserPlaylist("PLowned", "My synced list", remote = true)
        val added = mutableListOf<Pair<UserPlaylist, Track>>()
        compose.setContent { MusicApp(base().copy(query = "First", searched = true, results = listOf(first), account = AccountState(connected = true), playlists = listOf(playlist)),
            songActions = SongActionCallbacks(addToPlaylist = { list, track -> added += list to track })) }
        compose.onNodeWithContentDescription("More options for ${first.title}").performClick()
        compose.onNodeWithText("Add to playlist").performClick()
        compose.onNodeWithText(playlist.title).performClick()
        compose.runOnIdle { assertEquals(listOf(playlist to first), added) }
    }
    @Test fun deletingASyncedPlaylistRequiresExplicitConfirmationAndCancelKeepsIt() {
        val playlist = UserPlaylist("PLowned", "My synced list", remote = true)
        val deleted = mutableListOf<UserPlaylist>()
        compose.setContent { MusicApp(base().copy(account = AccountState(connected = true), playlistSelection = playlist),
            initialPage = "library", songActions = SongActionCallbacks(deletePlaylist = { deleted += it })) }
        compose.onNodeWithContentDescription("Playlists").performClick()
        compose.onNodeWithText("Delete playlist").performClick()
        compose.onNodeWithText("Delete playlist?").assertIsDisplayed()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("Delete playlist").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(listOf(playlist), deleted) }
    }
    @Test fun querySuggestionsCanSearchOrPlayAndSongsHaveTheirOwnOptions() {
        var selected = ""; val played = mutableListOf<Track>()
        compose.setContent { MusicApp(base().copy(query = "fir", suggestions = listOf(SearchSuggestion("first artist"), SearchSuggestion(first.title, first))),
            onSuggestion = { selected = it }, onPlay = { track, _ -> played += track }) }
        compose.onNodeWithText("Search suggestions").assertIsDisplayed()
        compose.onNodeWithText("first artist").performClick()
        compose.runOnIdle { assertEquals("first artist", selected) }
        compose.onNodeWithContentDescription("More options for ${first.title}").assertExists()
        compose.onNodeWithText(first.title).performClick()
        compose.runOnIdle { assertEquals(listOf(first), played) }
    }
    @Test fun softTransportIsCentredLikeIsSeparateAndPressDoesNotPaintASquare() {
        val state = mutableStateOf(base().copy(reducedMotion = false, current = first, queue = listOf(first, second), queueIndex = 0, playing = true, durationMs = 180000))
        compose.setContent { MusicApp(state.value, initialPage = "now", onToggle = { state.value = state.value.copy(playing = !state.value.playing) },
            onSave = { state.value = state.value.copy(saved = listOf(first)) }) }
        val pause = compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInWindow
        val previous = compose.onNodeWithContentDescription("Previous").fetchSemanticsNode().boundsInWindow
        val next = compose.onNodeWithContentDescription("Next").fetchSemanticsNode().boundsInWindow
        assertEquals(195f, pause.center.x, 1f)
        assertEquals(pause.center.x, (previous.center.x + next.center.x) / 2f, 1f)
        val like = compose.onNodeWithContentDescription("Save").fetchSemanticsNode().boundsInWindow
        assertTrue(like.bottom < pause.top)
        compose.mainClock.autoAdvance = false
        val before = screenshot()
        compose.onNodeWithContentDescription("Save").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(80)
        val pressed = screenshot()
        for ((x, y) in listOf(like.left + 1 to like.top + 1, like.right - 2 to like.top + 1, like.left + 1 to like.bottom - 2, like.right - 2 to like.bottom - 2))
            assertEquals("Pressed state is clipped to the circle", before.getPixel(x.toInt(), y.toInt()), pressed.getPixel(x.toInt(), y.toInt()))
        compose.onNodeWithContentDescription("Save").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.mainClock.autoAdvance = false
        repeat(12) {
            compose.mainClock.advanceTimeBy(16)
            val play = compose.onNode(hasContentDescription("Play") or hasContentDescription("Pause")).fetchSemanticsNode().boundsInWindow
            assertTrue(abs(play.center.x - pause.center.x) < 1f)
            assertEquals(pause.width, play.width, .1f); assertEquals(pause.height, play.height, .1f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play").assertExists()
        saveScreenshot("player-soft-centred-actions")
    }
    private fun screenshot(): Bitmap {
        var image: Bitmap? = null
        compose.runOnIdle { val view = compose.activity.window.decorView; image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        return image!!
    }
    private fun saveScreenshot(name: String) {
        val file = File("build/reports/screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { screenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
