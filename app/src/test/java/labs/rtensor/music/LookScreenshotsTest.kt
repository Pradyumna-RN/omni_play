package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import labs.rtensor.dsp.AudioLevelFrame
import labs.rtensor.dsp.ChannelLevel
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition.*
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.ui.CoverAccent
import labs.rtensor.music.ui.MusicApp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review screenshots for both looks, written to build/reports/screenshots/look-room-*.png and
 * look-classic-*.png. Covers are synthetic local files, so "From the cover" has real colours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()
    @After fun forget() = CoverAccent.reset()

    private fun cover(name: String, top: Int, bottom: Int, sun: Int): String {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, 300f, top, bottom, Shader.TileMode.CLAMP); drawRect(0f, 0f, 300f, 300f, p)
            p.shader = null; p.color = sun; drawCircle(190f, 120f, 60f, p)
        }
        val file = folder.newFile("$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return file.toURI().toString().also { CoverAccent.prime(it, bitmap) }
    }

    private fun capture(name: String, cropBottom: Int = 0) {
        repeat(20) { Thread.sleep(15); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            var image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            if (cropBottom > 0) image = Bitmap.createBitmap(image, 0, view.height - cropBottom, view.width, cropBottom)
            File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val surround = listOf(FL, FR, FC, LFE, BL, BR)

    private fun fixture(look: LookChoice, theme: String): MusicState {
        val clay = Track("fixture:clay", "Clay Rooms", "Hollow Lane", "https://www.youtube.com/watch?v=aaaaaaaaaaa", cover("clay", 0xFF3A1F12.toInt(), 0xFF8A4A22.toInt(), 0xFFF2C27B.toInt()), durationMs = 224000)
        val dusk = Track("fixture:dusk", "Night Ferry", "Serein", "https://www.youtube.com/watch?v=bbbbbbbbbbb", cover("dusk", 0xFF0B1A4A.toInt(), 0xFF1F3C9E.toInt(), 0xFFFF7A1A.toInt()), durationMs = 201000)
        val fern = Track("fixture:fern", "Greenhouse", "Ida Fennick", "https://www.youtube.com/watch?v=ccccccccccc", cover("fern", 0xFF1E5631.toInt(), 0xFF76C043.toInt(), 0xFFE8F5C8.toInt()), durationMs = 190000)
        val tide = Track("fixture:tide", "Low Tide Radio", "Marisol Avenue", "https://www.youtube.com/watch?v=ddddddddddd", cover("tide", 0xFF06343F.toInt(), 0xFF2BA3B5.toInt(), 0xFFFFE08A.toInt()), durationMs = 210000)
        val lamp = Track("fixture:lamp", "Paper Lanterns", "The Odd Quarter", "https://www.youtube.com/watch?v=eeeeeeeeeee", cover("lamp", 0xFF2A0B33.toInt(), 0xFF7B1FA2.toInt(), 0xFFD81B9A.toInt()), durationMs = 199000)
        fun entry(t: Track) = FeedEntry(t.id, t.title, t.artist, t.artwork, "song", "", t.id.removePrefix("fixture:").padEnd(11, '0'))
        val now = System.currentTimeMillis()
        return MusicState(look = look, theme = theme, reducedMotion = true, current = clay, playing = true, positionMs = 72000, durationMs = 224000,
            queue = listOf(clay, dusk, fern), queueIndex = 0, history = listOf(clay, dusk), saved = listOf(dusk, fern),
            playEvents = listOf(PlayEvent(clay, now - 60_000, 120_000, false), PlayEvent(dusk, now - 3_600_000, 180_000, false)),
            forYou = listOf(fern, tide), feed = listOf(FeedSection("Tonight", listOf(entry(lamp), entry(tide), entry(fern)))),
            explore = listOf(FeedSection("Fresh discoveries", listOf(entry(lamp), entry(tide), entry(fern))), FeedSection("Moods", listOf(entry(dusk), entry(clay)))),
            routeLabel = "YouTube · AAC compressed 48.0 kHz · stereo · unchanged",
            audioProcessing = AudioProcessingState(available = true, report = DownmixReport(48000, surround)))
    }

    private fun shoot(prefix: String, look: LookChoice, theme: String) {
        val state = mutableStateOf(fixture(look, theme))
        compose.setContent { MusicApp(state.value) }
        capture("$prefix-home")
        capture("$prefix-mini-player", cropBottom = 150)
        compose.onNodeWithContentDescription("Explore").performClick(); capture("$prefix-explore")
        compose.onNodeWithContentDescription("Library").performClick(); capture("$prefix-library")
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Accent colour").performScrollTo(); capture("$prefix-settings")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onAllNodes(hasClickAction() and hasText("Clay Rooms", substring = true)).onLast().performClick()
        capture("$prefix-player")
    }

    @Test fun listeningRoomDark() = shoot("look-room-dark", LookChoice.ROOM, "dark")
    @Test fun listeningRoomLight() = shoot("look-room-light", LookChoice.ROOM, "light")
    @Test fun softLight() = shoot("look-soft-light", LookChoice.SOFT, "light")
    @Test fun softDark() = shoot("look-soft-dark", LookChoice.SOFT, "dark")
    @Test fun softEmptyAndPaused() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, theme = "light", reducedMotion = true))
        compose.setContent { MusicApp(state.value) }
        capture("look-soft-light-home-empty")
        compose.runOnIdle { state.value = state.value.copy(theme = "dark") }
        capture("look-soft-dark-home-empty")
        compose.runOnIdle { state.value = fixture(LookChoice.SOFT, "light").copy(playing = false) }
        capture("look-soft-light-mini-player-paused", cropBottom = 150)
    }
    @Test fun classicLight() = shoot("look-classic-light", LookChoice.CLASSIC, "light")
    @Test fun classicDark() = shoot("look-classic-dark", LookChoice.CLASSIC, "dark")

    @Test fun roomsForEachLayout() {
        val base = fixture(LookChoice.ROOM, "dark")
        val state = mutableStateOf(base.copy(audioProcessing = AudioProcessingState(available = true,
            report = DownmixReport(48000, listOf(FL, FR), processingEnabled = false, comparisonAvailable = false))))
        compose.setContent { MusicApp(state.value) }
        capture("look-room-home-stereo")
        compose.runOnIdle { state.value = base }
        capture("look-room-home-5.1")
        compose.runOnIdle { state.value = base.copy(audioProcessing = AudioProcessingState(available = true,
            report = DownmixReport(48000, listOf(FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR)))) }
        capture("look-room-home-atmos")
        compose.runOnIdle { state.value = base.copy(current = null, playing = false, queue = emptyList(), queueIndex = -1) }
        capture("look-room-home-nothing-playing")
        compose.runOnIdle { state.value = base.copy(current = null, playing = false, queue = emptyList(), queueIndex = -1, theme = "light") }
        capture("look-room-light-home-nothing-playing")
    }

    /** Live levels need metering, which runs with Developer options; the lights and the meter follow them. */
    @Test fun liveLevelsLightTheRoomAndTheMeter() {
        val levels = listOf(-8f, -14f, -11f, -30f, -24f, -40f)
        fun frame() = AudioLevelFrame(surround.zip(levels).map { (p, db) -> ChannelLevel(p, db, db + 4) },
            listOf(ChannelLevel(FL, -10f, -6f), ChannelLevel(FR, -12f, -8f)), 48000, 48000, System.nanoTime())
        val flow = MutableStateFlow<AudioLevelFrame?>(frame())
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(fixture(LookChoice.ROOM, "dark").copy(developerOptions = true, reducedMotion = false))
        compose.setContent { MusicApp(state.value, initialPage = "explore", audioLevels = flow) }
        fun shot(name: String) {
            repeat(10) { flow.value = frame(); Thread.sleep(10); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        shot("look-room-dark-home-live-levels")
        compose.onNodeWithContentDescription("Open the player", substring = true).performClick()
        shot("look-room-dark-player-live-meter")
    }
}
