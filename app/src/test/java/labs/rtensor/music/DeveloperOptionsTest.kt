package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.AudioProcessingState
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
class DeveloperOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun player() = MusicState(current = Track("fixture:dev", "Afterimage", "Serein", "content://owned/audio"),
        playing = true, reducedMotion = true, audioProcessing = AudioProcessingState(available = true,
            report = DownmixReport(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC,
                SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR))))

    @Test fun ordinaryPlayerHidesMonitorWithoutHidingPlaybackComparison() {
        val requests = mutableListOf<Boolean>()
        compose.setContent { MusicApp(player(), initialPage = "now", onMetering = requests::add) }
        compose.onNodeWithText("Channel monitor").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand channel monitor").assertDoesNotExist()
        compose.onNodeWithContentDescription("Processed audio").performScrollTo().assertExists()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun turningDeveloperOptionsOffRemovesOpenMonitorAndStopsObservation() {
        val state = mutableStateOf(player());val requests = mutableListOf<Boolean>()
        compose.setContent { MusicApp(state.value, initialPage = "now", onMetering = requests::add) }
        compose.onNodeWithText("Channel monitor").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(developerOptions = true) }
        compose.onNodeWithContentDescription("Expand channel monitor").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(requests.last()) }
        compose.runOnIdle { state.value = state.value.copy(developerOptions = false) }
        compose.onNodeWithText("Channel monitor").assertDoesNotExist()
        compose.runOnIdle { assertFalse(requests.last()) }
    }

    @Test fun settingsSwitchDefaultsOffAndPersistsAcrossStoreRecreation() {
        val store = LibraryStore(compose.activity)
        assertFalse(store.developerOptions)
        val state = mutableStateOf(MusicState(reducedMotion = true, developerOptions = store.developerOptions))
        val changes = mutableListOf<Boolean>()
        compose.setContent { MusicApp(state.value, initialPage = "settings", onDeveloperOptions = {
            store.developerOptions = it; changes += it; state.value = state.value.copy(developerOptions = it)
        }) }
        compose.settingsTab("About")
        compose.onNodeWithContentDescription("Developer options").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(LibraryStore(compose.activity).developerOptions) }
        compose.onNodeWithContentDescription("Developer options").performClick().assertIsOff()
        compose.runOnIdle { assertFalse(LibraryStore(compose.activity).developerOptions);assertEquals(listOf(true, false), changes) }
    }
}
