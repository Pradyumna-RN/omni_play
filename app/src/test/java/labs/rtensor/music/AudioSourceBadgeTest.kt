package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.ui.MusicTheme
import labs.rtensor.music.ui.RouteLabel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioSourceBadgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun audioSourceChangesWhenAppleFallsBackToYoutube() {
        val label = mutableStateOf("Apple Music · AAC compressed 48.0 kHz · stereo · unchanged")
        compose.setContent { MusicTheme("dark") { RouteLabel(label.value) } }
        compose.onNodeWithText("Apple Music", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("AAC compressed 48.0 kHz · stereo · unchanged", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { label.value = "YouTube · compressed stream" }
        compose.onNodeWithText("Apple Music", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Audio source: YouTube · compressed stream").assertIsDisplayed()
    }
}
