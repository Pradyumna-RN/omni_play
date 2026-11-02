package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.updates.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Screenshot fixtures live only in the test APK; the product contains no fake feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=jNQXAC9IVRw", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=jNQXAC9IVRw", durationMs = 194000)

    private fun capture(name: String) {
        compose.waitForIdle()
        // PixelCopy's forced-redraw callback is not implemented by this Robolectric
        // version. Draw the actual laid-out Android view onto a native Canvas.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val file = File("build/reports/screenshots/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun homeAndNavigation() {
        compose.setContent { MusicApp(MusicState(theme = "light", reducedMotion = true)) }
        compose.onNodeWithText("Room to listen.").assertIsDisplayed()
        capture("home")
        compose.onNodeWithContentDescription("Search music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Explore.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        capture("explore")
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Music source").assertIsDisplayed()
        capture("settings")
    }

    @Test fun playerHasOneSaveActionAndWorkingTransportCallback() {
        val state = mutableStateOf(MusicState(current = track, playing = true, positionMs = 72000, durationMs = 224000, queue = listOf(track, other), queueIndex = 0, reducedMotion = true, theme = "light"))
        compose.setContent {
            MusicApp(state.value, initialPage = "now", onSave = { state.value = state.value.copy(saved = listOf(it)) }, onToggle = { state.value = state.value.copy(playing = false) })
        }
        compose.onAllNodesWithContentDescription("Save").assertCountEquals(1)
        compose.onNodeWithContentDescription("Save").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Saved").assertExists()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Play").assertExists()
        capture("player")
    }

    @Test fun searchAndSavedLayouts() {
        val state = mutableStateOf(MusicState(query = "Serein", searched = true, results = listOf(track, other), saved = listOf(track), reducedMotion = true, theme = "light"))
        compose.setContent { MusicApp(state.value, onQuery = { state.value = state.value.copy(query = it, searched = it.isNotEmpty()) }) }
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        capture("search")
        compose.onNodeWithContentDescription("Saved").performClick()
        compose.onNodeWithText("Worth keeping.").assertIsDisplayed()
        capture("saved")
    }

    @Test fun darkPlayerLayout() {
        compose.setContent { MusicApp(MusicState(current = track, playing = false, positionMs = 72000, durationMs = 224000, reducedMotion = true, theme = "dark"), initialPage = "now") }
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        capture("player-dark")
    }

    @Test fun repeatedPlaylistEntriesRemainVisible() {
        compose.setContent { MusicApp(MusicState(query = "test", searched = true, results = listOf(track, other, track.copy()), reducedMotion = true, theme = "light")) }
        compose.onAllNodesWithText("Afterimage").assertCountEquals(2)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h740dp-mdpi")
    fun compactPlayerKeepsTransportReachable() {
        compose.setContent { MusicApp(MusicState(current = track, durationMs = 224000, reducedMotion = true, theme = "light"), initialPage = "now") }
        compose.onNodeWithText("Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsDisplayed()
        capture("player-320")
    }

    @Test fun signInOpensYouTubeWithoutATvCode() {
        var opened = false
        compose.setContent { MusicApp(MusicState(reducedMotion = true, theme = "light"), initialPage = "settings", onConnect = { opened = true }) }
        compose.onNodeWithText("Sign in to YouTube").performScrollTo().performClick()
        org.junit.Assert.assertTrue(opened)
        compose.onNodeWithText("Open Google sign-in").assertDoesNotExist()
        compose.onNodeWithText("Getting a sign-in code…").assertDoesNotExist()
        capture("account-sign-in")
    }
    @Test fun exploreUsesProviderCardsAndTheSearchBarSubmitsInline() {
        val state = mutableStateOf(MusicState(explore = listOf(FeedSection("Fresh discoveries", listOf(FeedEntry("yt:jNQXAC9IVRw", track.title, track.artist, "", "song", "", "jNQXAC9IVRw")))), reducedMotion = true, theme = "light"))
        var submitted = false
        compose.setContent { MusicApp(state.value, onQuery = { state.value = state.value.copy(query = it, searched = false) }, onSearch = { submitted = true; state.value = state.value.copy(searched = true, results = listOf(track)) }) }
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Fresh discoveries").assertIsDisplayed()
        capture("explore-provider")
        compose.onNodeWithContentDescription("Search music").performTextInput("Serein")
        compose.onNodeWithContentDescription("Search music").performImeAction()
        org.junit.Assert.assertTrue(submitted)
        compose.onNodeWithText("1 RESULTS").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Explore.").assertIsDisplayed()
    }
    @Test fun playerErrorProvidesCopyableDiagnostics() {
        compose.setContent { MusicApp(MusicState(current = track, error = "YouTube rejected the audio stream (HTTP 403).", errorDiagnostic = "YTMUSIC: media probe HTTP 403", reducedMotion = true, theme = "light"), initialPage = "now") }
        compose.onNodeWithText("YouTube rejected the audio stream (HTTP 403).").assertIsDisplayed()
        compose.onNodeWithText("Copy details").performClick()
        val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        org.junit.Assert.assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("media probe HTTP 403"))
        capture("player-error")
    }
    @Test fun verifiedUpdateHasAnExplicitInstallAction() {
        var installed = false
        compose.setContent { MusicApp(MusicState(update = UpdateState(apkPath = "/test/verified.apk", message = "Verified update. Ready to install."), reducedMotion = true, theme = "light"), initialPage = "settings", onInstallUpdate = { installed = true }) }
        compose.onNodeWithText("Install update").performScrollTo().performClick()
        org.junit.Assert.assertTrue(installed)
        capture("app-updates")
    }
    @Test fun providerFeedOpensItsAlbumAndPlaysTheOriginalTrackList() {
        val album = FeedEntry("MPREb_fixture", "Late Windows", "Serein", "", "album", "MPREb_fixture", "")
        val song = FeedEntry(track.id, track.title, track.artist, "", "song", "", "jNQXAC9IVRw")
        val state = mutableStateOf(MusicState(account = AccountState(connected = true), feed = listOf(FeedSection("Your provider’s picks", listOf(album, song, song.copy()))), reducedMotion = true, theme = "light"))
        var playedOriginal = false
        compose.setContent { MusicApp(state.value, onBrowse = { state.value = state.value.copy(collectionEntry = it, collection = listOf(track)) }, onPlay = { t, queue -> playedOriginal = t == track && queue == listOf(track) }) }
        compose.onNodeWithText("Your provider’s picks").assertIsDisplayed()
        capture("provider-feed")
        compose.onNodeWithText("Late Windows").performClick()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        compose.onNodeWithText("Play all").performClick()
        org.junit.Assert.assertTrue(playedOriginal)
    }
}
