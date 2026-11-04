package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.SourceProtocol
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
class SourceSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeAccountAndSceneControlsReachTheirCallbacksWithoutAServer() {
        var signIns = 0
        val sceneRequests = mutableListOf<Boolean>()
        compose.setContent { MusicApp(MusicState(reducedMotion = true,
            audioProcessing = labs.rtensor.music.playback.AudioProcessingState(available = true, objectDecoderAvailable = true)),
            initialPage = "settings", onConnectTidal = { signIns++ }, onObjectDecoding = sceneRequests::add) }
        compose.onNodeWithContentDescription("Scene decoding (experimental)").performScrollTo().performClick()
        compose.onNodeWithText("Playback sources").performScrollTo().performClick()
        compose.onNodeWithText("TIDAL · experimental").assertExists()
        compose.onNodeWithText("Sign in to TIDAL").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), sceneRequests); assertEquals(1, signIns) }
    }

    @Test fun settingsOffersAccountServersAndUserInstalledAddonsWithoutChangingDiscovery() {
        val requests = mutableListOf<Pair<MusicProvider, SourceProtocol>>()
        compose.setContent { MusicApp(MusicState(reducedMotion = true), initialPage = "settings",
            onConnectSource = { provider, protocol, url, _ ->
                assertEquals("https://personal.example/music", url)
                requests += provider to protocol
            }) }
        compose.onNodeWithText("Playback sources").performScrollTo()
        compose.onNodeWithText("Playback sources").performClick()
        compose.onNodeWithText("HTTPS server URL").performTextInput("https://personal.example/music")
        compose.onNodeWithText("Check and save").performScrollTo().performClick()
        compose.onNodeWithText("Audio addon").performScrollTo().performClick()
        compose.onNodeWithText("Check and save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MusicProvider.APPLE_MUSIC to SourceProtocol.GATEWAY, MusicProvider.LIBRARY to SourceProtocol.ADDON), requests)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Autoplay radio").performScrollTo().assertIsDisplayed()
    }
}
