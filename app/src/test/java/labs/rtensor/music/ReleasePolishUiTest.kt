package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MinTextContrast
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.contrastRatio
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Release review fixes: what users and TalkBack actually get. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleasePolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("fixture:1", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("fixture:2", "Soft Signals", "Hollow Coves", "https://www.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)

    private fun snapshot(): Bitmap {
        compose.waitForIdle()
        var image: Bitmap? = null
        compose.runOnIdle { val v = compose.activity.window.decorView; image = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }
        return image!!
    }

    @Test fun thePlayingSongIsMarkedInListsInEveryLook() {
        val state = androidx.compose.runtime.mutableStateOf(MusicState(reducedMotion = true, accent = AccentChoice.SIGNAL, current = track,
            query = "x", searched = true, results = listOf(track, other)))
        compose.setContent { MusicApp(state.value) }
        for (look in LookChoice.entries) for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(look = look, theme = theme) }
            // Not by colour alone: the playing row says so, the other row doesn't.
            compose.onAllNodesWithText("Playing · ").assertCountEquals(1)
            compose.onNode(hasText("Afterimage") and hasText("Playing · ") and hasClickAction()).assertExists()
            compose.onNode(hasText("Soft Signals") and hasText("Playing · ") and hasClickAction()).assertDoesNotExist()
        }
    }
}
