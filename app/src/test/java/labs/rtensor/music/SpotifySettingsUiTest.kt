package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.recommendations.RecommendationEngine
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
class SpotifySettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun disconnectedSpotifyChoiceStartsLoginWithoutPretendingToSelectIt() {
        var login = 0; val changes = mutableListOf<RecommendationEngine>()
        compose.setContent { MusicApp(MusicState(reducedMotion = true), initialPage = "settings", onConnectSpotify = { login++ }, onRecommendationEngine = changes::add) }
        compose.onNodeWithContentDescription("YouTube Music recommendations").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Spotify recommendations").performScrollTo().assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, login); assertTrue(changes.isEmpty()) }
        compose.onNodeWithContentDescription("YouTube Music recommendations").assertIsSelected()
    }
    @Test fun verifiedAccountCanSwitchBothWaysPersistPreferenceAndDisconnect() {
        val state = mutableStateOf(MusicState(reducedMotion = true, spotifyAccount = AccountState(connected = true, name = "Listener")))
        val store = LibraryStore(compose.activity); var disconnects = 0
        compose.setContent { MusicApp(state.value, initialPage = "settings", onRecommendationEngine = {
            store.recommendationEngine = it; state.value = state.value.copy(recommendationEngine = it)
        }, onDisconnectSpotify = { disconnects++ }) }
        compose.onNodeWithContentDescription("Spotify recommendations").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(RecommendationEngine.SPOTIFY, LibraryStore(compose.activity).recommendationEngine) }
        compose.onNodeWithContentDescription("YouTube Music recommendations").performClick().assertIsSelected()
        compose.onNodeWithText("Disconnect Spotify").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
    }
    @Test fun aSignInThatEndsWithoutAConnectionSaysSoUnderTheChoice() {
        val message = labs.rtensor.music.spotify.SpotifyRecommendations.cancelledMessage("the sign-in page could not load")
        compose.setContent { MusicApp(MusicState(reducedMotion = true, spotifyAccount = AccountState(error = message)), initialPage = "settings") }
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("YouTube Music recommendations").assertIsSelected()
    }
}
