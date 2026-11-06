package labs.rtensor.music

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Track
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Check positions across real Compose measurement, horizontal scrolling and metadata updates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val longTitle = "A much longer song title that wraps onto another line"
    private val longArtist = "First artist with a long name & Second collaborating artist"
    private fun entry(index: Int) = FeedEntry("choice:$index", if (index == 8) longTitle else "Short $index",
        if (index == 8) longArtist else "", "", "song", "", "video$index")
    private val horizontal = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    @Test fun scrollingBetweenShortAndWrappedCardsDoesNotMoveTheFollowingRow() = stableSongRows(1f)

    @Test
    @Config(qualifiers = "w320dp-h960dp-mdpi")
    fun songRowsStayStableOnNarrowScreensWithLargeText() = stableSongRows(1.5f)

    private fun stableSongRows(fontScale: Float) {
        val first = FeedSection("First row", (0..9).map(::entry))
        val following = FeedSection("Next row", listOf(entry(20)))
        val state = mutableStateOf(MusicState(explore = listOf(first, following), reducedMotion = true))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { MusicApp(state.value, initialPage = "explore") }
        }
        compose.onNodeWithContentDescription("Discover").performClick()
        val originalTop = top("Next row")
        // Ten songs are shown in three four-row columns; the last column has two blanks.
        for (index in listOf(2, 0, 2)) {
            compose.onAllNodes(horizontal).onFirst().performScrollToIndex(index)
            assertEquals("Long text and a shorter final column must not resize the shelf", originalTop, top("Next row"), .5f)
            if (index == 2) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(longTitle).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Shelf metadata stays on one line", layouts.isNotEmpty() && layouts.all { it.lineCount == 1 })
            }
        }
        // Artist details can arrive later for the same song identities.
        compose.runOnIdle {
            state.value = state.value.copy(explore = listOf(first.copy(items = first.items.map { it.copy(title = longTitle, subtitle = longArtist) }), following))
        }
        assertEquals("Late metadata must not push the next section down", originalTop, top("Next row"), .5f)
    }

    @Test fun lastPlayedTitleAndArtistWrappingDoesNotMoveItsPlayButtonOrTheNextSection() {
        val song = Track("local:hero", "Short song", "", "content://audio/hero", durationMs = 180_000)
        // The section after Last played is the mix built from what you play.
        val mix = Track("fixture:mix", "Mix song", "Mix artist", "https://music.youtube.com/watch?v=ccccccccccc")
        val state = mutableStateOf(MusicState(history = listOf(song), forYou = listOf(mix, mix.copy(id = "fixture:fresh", title = "Fresh song")), reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        val playTop = top("Play")
        val recentTop = top("Play mix")
        for (track in listOf(song.copy(title = longTitle, artist = longArtist), song, song.copy(artist = longArtist))) {
            compose.runOnIdle { state.value = state.value.copy(history = listOf(track)) }
            assertEquals("Home's Play control must stay put as metadata wraps", playTop, top("Play"), .5f)
            assertEquals("The section below Last played must stay put", recentTop, top("Play mix"), .5f)
        }
    }

    @Test fun compactBarScrollsOverflowingMetadataWithoutWrappingOrMovingItsControls() {
        compose.mainClock.autoAdvance = false
        val song = Track("fixture:marquee", longTitle, longArtist, "https://music.youtube.com/watch?v=aaaaaaaaaaa")
        val state = mutableStateOf(MusicState(current = song, queue = listOf(song), queueIndex = 0, reducedMotion = false))
        compose.setContent { MusicApp(state.value) }
        compose.waitForIdle()
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
        val artist = compose.onNodeWithText(longArtist, useUnmergedTree = true)
        for (field in listOf(title, artist)) {
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
        }
        val bounds = title.fetchSemanticsNode().boundsInRoot
        val controlBounds = compose.onNodeWithContentDescription("Next recording").getUnclippedBoundsInRoot()
        val before = pixels(bounds, "compact-metadata-start")
        // Advance actual Compose frames so the built-in marquee's draw offset updates.
        repeat(200) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val after = pixels(bounds, "compact-metadata-scrolled")
        assertFalse("The visible title must actually scroll, not just be ellipsized", before.contentEquals(after))
        assertEquals(controlBounds, compose.onNodeWithContentDescription("Next recording").getUnclippedBoundsInRoot())
        assertEquals(bounds, title.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(current = song.copy(title = "Short", artist = "")) }
        compose.waitForIdle()
        assertEquals(controlBounds, compose.onNodeWithContentDescription("Next recording").getUnclippedBoundsInRoot())
    }

    private fun pixels(bounds: Rect, name: String): IntArray {
        var pixels = intArrayOf()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/home-layout/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val x = bounds.left.toInt().coerceAtLeast(0); val y = bounds.top.toInt().coerceAtLeast(0)
            val width = bounds.width.toInt().coerceAtMost(view.width - x); val height = bounds.height.toInt().coerceAtMost(view.height - y)
            pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, x, y, width, height)
            bitmap.recycle()
        }
        return pixels
    }
}
