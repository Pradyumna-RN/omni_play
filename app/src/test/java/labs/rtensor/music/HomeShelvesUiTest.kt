package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.PlayEvent
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

/**
 * Home's recommendation shelves ("Next up for you", "Play it again", "Your mixes") in every look,
 * with review screenshots in build/reports/screenshots/shelves-<look>-<theme>.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h1500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShelvesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int) = "%011d".format(n)
    private fun track(name: String, n: Int) = Track("yt:${id(n)}", name, "Artist $n", "https://music.youtube.com/watch?v=${id(n)}")
    private val picks = (1..10).map { track("Pick $it", it) }
    private val history = (1..8).map { track("Heard $it", 100 + it) }
    private val replays = (1..6).map { track("Replay $it", 200 + it) }
    private val tonight = (1..6).map { FeedEntry("yt:${id(300 + it)}", "Tonight $it", "Night $it", "", "song", "", id(300 + it), artist = "Night $it") }

    private fun fixture(look: LookChoice = LookChoice.ROOM, theme: String = "dark"): MusicState {
        val now = System.currentTimeMillis()
        return MusicState(look = look, theme = theme, reducedMotion = true, history = history, forYou = picks,
            playEvents = replays.mapIndexed { i, t -> PlayEvent(t, now - i * 60_000L, 120_000, false) },
            feed = listOf(FeedSection("Tonight", tonight)), feedHasMore = true)
    }

    /** Playing opens the player; Back returns to Home. */
    private fun back() { compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle() }
    private fun home() = compose.onAllNodes(hasScrollToKeyAction()).onFirst()

    @Test fun eachShelfRendersWithAHeadingInEveryLook() {
        val state = mutableStateOf(fixture())
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        for (look in LookChoice.values()) {
            compose.runOnIdle { state.value = fixture(look) }
            for ((key, title) in listOf("for-you" to "Next up for you", "again" to "Play it again", "mixes" to "Your mixes")) {
                home().performScrollToKey(key)
                compose.onNode(hasText(title) and isHeading()).assertIsDisplayed()
                if (key == "for-you") compose.onNodeWithTag("shelf:next-up").assertIsDisplayed()
                if (key == "again") compose.onNodeWithTag("shelf:again").assertIsDisplayed()
            }
            compose.onNodeWithText("Your mix").assertIsDisplayed()
            compose.onNodeWithText("Tonight").assertExists()
            // The opening discovery shelf stays in place.
            home().performScrollToKey("unheard"); compose.onNodeWithText("New to your ears").assertIsDisplayed()
        }
    }

    @Test fun nextUpIsAPagedGridOfFourRowsThatSnapsAndScrollsForTalkBack() {
        compose.setContent { MusicApp(fixture(LookChoice.CLASSIC, "light"), initialPage = "explore") }
        home().performScrollToKey("for-you")
        val grid = compose.onNodeWithTag("shelf:next-up")
        grid.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        grid.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        val gridLeft = grid.getUnclippedBoundsInRoot().left
        fun box(name: String) = compose.onNodeWithText(name).getUnclippedBoundsInRoot()
        // Four rows to a column: Pick 1-4 stack, Pick 5 starts the next column.
        assertEquals(box("Pick 1").left, box("Pick 4").left)
        assertTrue(box("Pick 4").top > box("Pick 1").top)
        assertEquals(box("Pick 1").top, box("Pick 5").top)
        assertTrue(box("Pick 5").left > box("Pick 1").right)
        // Drag part way across and let go: the grid settles with a column against its start, never between two.
        grid.performTouchInput { down(Offset(right - 20f, centerY)); repeat(11) { moveBy(Offset(-20f, 0f), delayMillis = 40) }; up() }
        compose.mainClock.advanceTimeBy(3000); compose.waitForIdle()
        // After a short swipe the grid settles with a column against its start edge.
        val lefts = (1..10).mapNotNull { n -> compose.onAllNodesWithText("Pick $n").fetchSemanticsNodes().firstOrNull()?.let { box("Pick $n").left.value } }
        assertTrue("a column is snapped to the start: $lefts vs $gridLeft", lefts.any { kotlin.math.abs(it - gridLeft.value) < 1f })
        // TalkBack (and the accessibility scroll actions) can page to the last column.
        grid.performScrollToIndex(8)
        compose.onNodeWithText("Pick 9").assertIsDisplayed()
    }

    @Test fun tappingASongPlaysThatSongAloneAndMixesPlayTheirQueue() {
        val played = mutableListOf<Pair<Track, List<Track>>>()
        compose.setContent { MusicApp(fixture(), initialPage = "explore", onPlay = { t, q -> played += t to q }) }
        home().performScrollToKey("for-you")
        compose.onNodeWithText("Pick 2").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(picks[1] to listOf(picks[1]), played.last()) }
        back()
        compose.onNodeWithText("Play mix").performClick()
        compose.runOnIdle { assertEquals(picks.first() to picks, played.last()) }
        back()
        home().performScrollToKey("again")
        compose.onNodeWithText("Replay 3").performClick()
        compose.runOnIdle { assertEquals(replays[2] to listOf(replays[2]), played.last()) }
        back()
        home().performScrollToKey("mixes")
        compose.onNodeWithText("Tonight").performClick()
        compose.runOnIdle {
            assertEquals("Tonight 1", played.last().first.title)
            assertEquals(tonight.map { it.title }, played.last().second.map { it.title })
        }
        back(); home().performScrollToKey("mixes")
        compose.onNodeWithText("Your mix").performClick()
        compose.runOnIdle { assertEquals(picks.first() to picks, played.last()) }
    }

    @Test fun rowsAndTilesDescribeThemselvesAndAreAtLeast48dp() {
        compose.setContent { MusicApp(fixture(), initialPage = "explore") }
        home().performScrollToKey("for-you")
        val row = compose.onNodeWithContentDescription("Pick 1 by Artist 1")
        row.assertHasClickAction()
        assertTrue(row.getUnclippedBoundsInRoot().let { it.bottom - it.top }.value >= 48f)
        home().performScrollToKey("again")
        compose.onNodeWithContentDescription("Replay 1 by Artist 201").assertHasClickAction()
        home().performScrollToKey("mixes")
        compose.onNodeWithContentDescription("Tonight, 6 songs", substring = true).assertHasClickAction()
    }

    @Test fun playItAgainIsHiddenWhenHistoryIsOff() {
        val state = mutableStateOf(fixture())
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        home().performScrollToKey("again")
        compose.onNodeWithText("Play it again").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(trackHistory = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Play it again").assertDoesNotExist()
        compose.onNodeWithText("Replay 1").assertDoesNotExist()
    }

    @Test fun reachingTheFeedEndLoadsAutomaticallyWithoutDuplicateButtons() {
        var more = 0
        val state = mutableStateOf(fixture())
        compose.setContent { MusicApp(state.value, onMoreFeed = { more++; state.value = state.value.copy(feedBusy = true, feedAppending = true) }) }
        compose.onNodeWithText("More from your feed").assertDoesNotExist()
        compose.onNodeWithText("Your YouTube Music feed").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { more == 1 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, more) }
    }

    private fun capture(name: String) {
        repeat(20) { Thread.sleep(15); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Synthetic local covers, so the screenshots show artwork. */
    private fun withCovers(state: MusicState): MusicState {
        val palette = listOf(0xFF3A1F12, 0xFF0B1A4A, 0xFF1E5631, 0xFF06343F, 0xFF2A0B33, 0xFF7A2E0E, 0xFF223355, 0xFF5B4A12).map { it.toInt() }
        val made = HashMap<String, String>()
        fun art(key: String): String = made.getOrPut(key) {
            val n = made.size
            val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(palette[n % palette.size])
                drawCircle(40f + (n * 17) % 50, 50f + (n * 11) % 40, 26f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2C27B.toInt() xor (n * 0x103050) })
            }
            val file = folder.newFile("$n.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            file.toURI().toString()
        }
        fun t(track: Track) = track.copy(artwork = art(track.id))
        return state.copy(forYou = state.forYou.map(::t), history = state.history.map(::t), playEvents = state.playEvents.map { it.copy(track = t(it.track)) },
            feed = state.feed.map { s -> s.copy(items = s.items.map { it.copy(artwork = art(it.id)) }) })
    }

    @get:Rule val folder = org.junit.rules.TemporaryFolder()

    private fun shoot(look: LookChoice, name: String) {
        val state = mutableStateOf(withCovers(fixture(look, "light")))
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { state.value = state.value.copy(theme = theme) }
            home().performScrollToKey("for-you")
            capture("shelves-$name-$theme")
        }
    }

    @Test fun screenshotsRoom() = shoot(LookChoice.ROOM, "room")
    @Test fun screenshotsSoft() = shoot(LookChoice.SOFT, "soft")
    @Test fun screenshotsClassic() = shoot(LookChoice.CLASSIC, "classic")
}
