package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.spotify.SpotifyLoginScreen
import labs.rtensor.music.ui.MusicTheme
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
class SpotifyLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun continueBecomesConnectOnSpotifyAndNeverShowsSensitiveUrlParameters() {
        val url = mutableStateOf("https://accounts.google.com/login?code=PRIVATE_CODE&login_hint=private@example.test")
        var connects = 0
        compose.setContent { MusicTheme("light") { SpotifyLoginScreen(url.value, "Finish signing in", false, false, {}, { connects++ }, {}, {}) { Text("Browser") } } }
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("accounts.google.com").assertIsDisplayed()
        compose.onNodeWithText("PRIVATE_CODE", substring = true).assertDoesNotExist()
        compose.onNodeWithText("private@example.test", substring = true).assertDoesNotExist()
        compose.runOnIdle { url.value = "https://open.spotify.com/" }
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertEquals(2, connects) }
    }
    @Test fun checkingAccountDisablesDuplicateConnectionsButLeavesCancelAvailable() {
        var cancels = 0
        compose.setContent { MusicTheme("light") { SpotifyLoginScreen("https://open.spotify.com/", "Checking your Spotify account…", true, false, { cancels++ }, {}, {}, {}) {} } }
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Reload").assertIsNotEnabled()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }
    @Test fun failedVerificationKeepsConnectSignInAndReloadActionsUsable() {
        var retry = 0; var login = 0; var reload = 0
        compose.setContent { MusicTheme("light") { SpotifyLoginScreen("https://open.spotify.com/", "Could not verify the Spotify account yet.", false, false, {}, { retry++ }, { login++ }, { reload++ }) {} } }
        compose.onNodeWithText("Could not verify the Spotify account yet.").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick(); compose.onNodeWithText("Sign in").performClick(); compose.onNodeWithText("Reload").performClick()
        compose.runOnIdle { assertEquals(listOf(1,1,1), listOf(retry,login,reload)) }
    }
}
