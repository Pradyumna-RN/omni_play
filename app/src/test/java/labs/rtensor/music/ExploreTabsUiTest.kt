package labs.rtensor.music

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.*
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
class ExploreTabsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun forYouIsTheDefaultWhenTheFeedArrivesAfterStartup() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true))
        compose.setContent { MusicApp(state.value) }
        compose.runOnIdle { state.value = state.value.copy(feed = listOf(FeedSection("Late recommendations", listOf(song(1))))) }
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithContentDescription("For you").assertIsSelected()
        compose.onNodeWithText("Late recommendations").assertIsDisplayed()
    }

    @Test fun aLateRecommendationResponseDoesNotOverrideTheChosenTab() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            explore = listOf(FeedSection("New music", listOf(album)))))
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        compose.onNodeWithContentDescription("For you").assertIsSelected()
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.runOnIdle { state.value = state.value.copy(feed = listOf(FeedSection("Late recommendations", listOf(song(1))))) }
        compose.onNodeWithContentDescription("Discover").assertIsSelected()
        compose.onNodeWithText(album.title).assertIsDisplayed()
        compose.onNodeWithText("Late recommendations").assertDoesNotExist()
    }
    private fun song(i: Int) = FeedEntry("yt:${"%011d".format(i)}", "Song $i", "Artist $i", "", "song", "", "%011d".format(i))
    private val verticalMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun list() = compose.onAllNodes(verticalMatcher).onFirst()
    private fun inList(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(verticalMatcher))
    private val playlist = FeedEntry("PLrecommendation", "Provider playlist", "YouTube Music", "", "playlist", "VLPLrecommendation", "")
    private val album = FeedEntry("MPREb_new", "New release from provider", "Artist", "", "album", "MPREb_new", "")
    private val charts = FeedEntry("FEmusic_charts", "Charts", "", "", "browse", "FEmusic_charts", "")

    @Test fun providerShelvesMoveOutOfHomeAndAppearUnderTheRightExploreTabs() {
        val state = MusicState(look = LookChoice.SOFT, reducedMotion = true, feed = listOf(FeedSection("Recommendations", listOf(playlist))),
            explore = listOf(FeedSection("Browse", listOf(charts)), FeedSection("New albums & singles", listOf(album))))
        compose.setContent { MusicApp(state) }
        compose.onNodeWithText(playlist.title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithContentDescription("For you").assertIsSelected()
        compose.onNodeWithText(playlist.title).assertIsDisplayed()
        capture("explore-tabs-for-you")
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.onNodeWithText(album.title).assertIsDisplayed()
        capture("explore-tabs-discover")
        compose.onNodeWithText(playlist.title).assertDoesNotExist()
        compose.onNodeWithText("Charts").assertDoesNotExist()
        compose.onNodeWithContentDescription("Browse").performClick()
        compose.onNodeWithText("Charts").assertIsDisplayed()
        capture("explore-tabs-browse")
        compose.onNodeWithText(album.title).assertDoesNotExist()
    }
    @Test fun theTabsKeepIndependentPositionsAndPlaybackReturnsToTheChosenTab() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = (0..16).map { FeedSection("For you $it", listOf(song(it))) },
            explore = (0..16).map { FeedSection("Discover $it", listOf(song(100 + it))) }, current = song(999).asTrack()))
        compose.setContent { MusicApp(state.value, initialPage = "explore", onPlay = { track, _ -> state.value = state.value.copy(current = track, playing = true) }) }
        list().performScrollToKey("provider-shelf:6:For you 6")
        val before = inList("Song 6").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Discover").performClick()
        list().performScrollToKey("provider-shelf:4:Discover 4")
        compose.onNodeWithContentDescription("For you").performClick()
        inList("Song 6").assertIsDisplayed()
        assertEquals(before.top.value, inList("Song 6").getUnclippedBoundsInRoot().top.value, 1f)
        inList("Song 6").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("For you").assertIsSelected()
        inList("Song 6").assertIsDisplayed()
        assertEquals(before.top.value, inList("Song 6").getUnclippedBoundsInRoot().top.value, 1f)
    }
    @Test fun expandingAForYouShelfReadsTheRecommendationFeedInsteadOfDiscover() {
        val state = MusicState(look = LookChoice.SOFT, reducedMotion = true, feed = listOf(FeedSection("Recommended shelf", listOf(song(1)))),
            explore = listOf(FeedSection("Separate discovery", listOf(album))))
        compose.setContent { MusicApp(state, initialPage = "explore") }
        compose.onNodeWithText("Recommended shelf").performClick()
        inList("Song 1").assertIsDisplayed()
        compose.onNodeWithText(album.title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("For you").assertIsSelected()
    }
    @Test fun inactiveTabsDoNotConsumeTheRecommendationContinuation() {
        var more = 0
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = listOf(FeedSection("Recommendations", listOf(song(1)))), feedHasMore = true,
            explore = listOf(FeedSection("New music", listOf(album)))))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicApp(state.value, initialPage = "explore", onMoreFeed = { more++; state.value = state.value.copy(feedBusy = true, feedAppending = true) }) }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(0, more) }
        compose.onNodeWithContentDescription("For you").performClick()
        compose.mainClock.autoAdvance = true
        list().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { more == 1 }
    }
    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/screenshots/$name.png"); output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

}
