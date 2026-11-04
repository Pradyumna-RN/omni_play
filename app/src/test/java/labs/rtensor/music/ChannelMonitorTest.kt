package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import labs.rtensor.dsp.*
import labs.rtensor.music.ui.ChannelMonitor
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.ui.MusicTheme
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.data.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelMonitorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stereo = listOf(SpeakerPosition.FL, SpeakerPosition.FR)
    private val surround = stereo + listOf(SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.BL, SpeakerPosition.BR)
    private fun frame(positions: List<SpeakerPosition>) = AudioLevelFrame(
        positions.mapIndexed { i, p -> ChannelLevel(p, -12f - i * 4, -6f - i * 3) },
        stereo.map { ChannelLevel(it, -14f, -8f) }, 48000, 48000, System.nanoTime())
    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val file = File("build/reports/screenshots/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun sourceChannelsAreExactAndClosingOrPausingStopsMetering() {
        val playing = mutableStateOf(true);val requests = mutableListOf<Boolean>()
        val levels = MutableStateFlow<AudioLevelFrame?>(null)
        compose.setContent { MusicTheme("light") { Surface { Column(Modifier.padding(16.dp)) {
            ChannelMonitor(DownmixReport(48000, surround), playing.value, "track", levels, requests::add)
        } } } }
        assertTrue(requests.isEmpty())
        compose.onNodeWithContentDescription("Expand channel monitor").performClick()
        compose.runOnIdle { levels.value = frame(surround) }
        surround.forEach { compose.onNodeWithTag("input-channel-${it.name}").assertExists() }
        for (p in listOf(SpeakerPosition.SL, SpeakerPosition.SR, SpeakerPosition.TFL, SpeakerPosition.TFR, SpeakerPosition.TBL, SpeakerPosition.TBR))
            compose.onNodeWithTag("input-channel-${p.name}").assertDoesNotExist()
        compose.onNodeWithText("Stereo output").assertIsDisplayed()
        compose.onNodeWithText("−60 to 0 dBFS · before device volume").assertIsDisplayed()
        capture("channel-monitor-light-51")
        compose.runOnIdle { assertTrue(requests.last());playing.value = false }
        compose.onNodeWithText("Paused · 6 input channels").assertIsDisplayed()
        compose.runOnIdle { assertFalse(requests.last()) }
        compose.onNodeWithContentDescription("Collapse channel monitor").performClick()
        compose.onNodeWithText("Stereo output").assertDoesNotExist()
        compose.runOnIdle { assertFalse(requests.last()) }
    }
    @Test fun stereoShowsOnlyLeftRightAndMeterUpdatesDoNotRecomposeThePlayer() {
        val levels = MutableStateFlow<AudioLevelFrame?>(null)
        val track = Track("fixture:monitor", "Afterimage", "Serein", "content://owned/audio")
        val state = MusicState(current = track, playing = true, reducedMotion = true, theme = "dark",
            audioProcessing = AudioProcessingState(available = true, report = DownmixReport(48000, stereo,
                processingEnabled = false, comparisonAvailable = false)))
        var roots = 0;var toggles = 0
        compose.setContent { SideEffect { roots++ };MusicApp(state, initialPage = "now", audioLevels = levels, onToggle = { toggles++ }) }
        compose.onNodeWithContentDescription("Expand channel monitor").performScrollTo().performClick()
        compose.onNodeWithTag("input-channel-FL").performScrollTo().assertExists()
        compose.onNodeWithTag("input-channel-FR").assertExists()
        compose.onNodeWithTag("input-channel-FC").assertDoesNotExist()
        val before = roots
        repeat(5) { n -> compose.runOnIdle { levels.value = frame(stereo).copy(measuredAtNs = System.nanoTime() + n) };compose.waitForIdle() }
        assertEquals(before, roots)
        compose.onNodeWithText("−60 to 0 dBFS · before device volume").performScrollTo()
        capture("channel-monitor-dark-stereo")
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
    @Test fun unknownLayoutUsesNumberedChannelsInsteadOfInventingSpeakerPositions() {
        compose.setContent { MusicTheme("light") { ChannelMonitor(DownmixReport(48000, emptyList(),
            sourceChannels = 6, outputChannels = 6, comparisonAvailable = false), true, "unknown", null, {}) } }
        compose.onNodeWithContentDescription("Expand channel monitor").performClick()
        compose.onNodeWithText("Speaker positions unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Ch 6").assertCountEquals(2)
        compose.onNodeWithTag("input-channel-FL").assertDoesNotExist()
        compose.onNodeWithText("Sink output · 6 channels").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun sevenPointOneFitsACompactScreenWithoutInventedHeightChannels() {
        val positions = surround + listOf(SpeakerPosition.SL, SpeakerPosition.SR)
        val levels = MutableStateFlow<AudioLevelFrame?>(null)
        compose.setContent { MusicTheme("dark") { Surface { Column(Modifier.padding(16.dp)) {
            ChannelMonitor(DownmixReport(48000, positions), true, "71", levels, {})
        } } } }
        compose.onNodeWithContentDescription("Expand channel monitor").performClick()
        compose.runOnIdle { levels.value = frame(positions) }
        positions.forEach { compose.onNodeWithTag("input-channel-${it.name}").assertIsDisplayed() }
        compose.onNodeWithTag("input-channel-TFL").assertDoesNotExist()
        compose.onNodeWithText("−60 to 0 dBFS · before device volume").assertIsDisplayed()
        capture("channel-monitor-dark-71-compact")
    }
    @Test fun rightToLeftInterfaceNeverSwapsPhysicalLeftAndRightSpeakers() {
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MusicTheme("light") { ChannelMonitor(DownmixReport(48000, stereo), true, "rtl", null, {}) }
        } }
        compose.onNodeWithContentDescription("Expand channel monitor").performClick()
        val left = compose.onNodeWithTag("input-channel-FL").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("input-channel-FR").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < right.left)
    }
}
