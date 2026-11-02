package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MusicApp
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
            file.parentFile.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun homeAndNavigation() {
        compose.setContent { MusicApp(MusicState(theme = "light", reducedMotion = true)) }
        compose.onNodeWithText("Room to listen.").assertIsDisplayed()
        capture("home")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Find your sound.").assertIsDisplayed()
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
        compose.setContent { MusicApp(MusicState(query = "Serein", searched = true, results = listOf(track, other), saved = listOf(track), reducedMotion = true, theme = "light"), initialPage = "search") }
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
        compose.setContent { MusicApp(MusicState(searched = true, results = listOf(track, other, track.copy()), reducedMotion = true, theme = "light"), initialPage = "search") }
        compose.onAllNodesWithText("Afterimage").assertCountEquals(2)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h740dp-mdpi")
    fun compactPlayerKeepsTransportReachable() {
        compose.setContent { MusicApp(MusicState(current = track, durationMs = 224000, reducedMotion = true, theme = "light"), initialPage = "now") }
        compose.onNodeWithText("Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsDisplayed()
        capture("player-320")
    }
}
