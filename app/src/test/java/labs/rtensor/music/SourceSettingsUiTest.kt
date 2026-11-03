package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun settingsOffersAccountServersAndUserInstalledAddonsWithoutChangingDiscovery() {
        val requests = mutableListOf<Pair<MusicProvider, SourceProtocol>>()
        compose.setContent { MusicApp(MusicState(reducedMotion = true), initialPage = "settings",
            onConnectSource = { provider, protocol, url, _ ->
                assertEquals("https://personal.example/music", url)
                requests += provider to protocol
            }) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Playback sources").performScrollTo()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Playback sources").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("HTTPS server URL").performTextInput("https://personal.example/music")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Check and save").performScrollTo().performClick()
        compose.onNodeWithText("Audio addon").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Check and save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MusicProvider.APPLE_MUSIC to SourceProtocol.GATEWAY, MusicProvider.LIBRARY to SourceProtocol.ADDON), requests)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Autoplay radio").performScrollTo().assertIsDisplayed()
    }
}
