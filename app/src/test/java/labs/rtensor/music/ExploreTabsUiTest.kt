package labs.rtensor.music

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreTabsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun song(i: Int) = FeedEntry("yt:${"%011d".format(i)}", "Song $i", "Artist $i", "", "song", "", "%011d".format(i))
    private val playlist = FeedEntry("PLrecommendation", "Provider playlist", "YouTube Music", "", "playlist", "VLPLrecommendation", "")
    private val album = FeedEntry("MPREb_new", "New release from provider", "Artist", "", "album", "MPREb_new", "")
    private val charts = FeedEntry("FEmusic_charts:", "Charts", "", "", "browse", "FEmusic_charts", "")
    private val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun list() = compose.onAllNodes(vertical).onFirst()

    @Test fun homeHasTheFullFeedAndExploreForYouHasPersonalListening() {
        val state = MusicState(look = LookChoice.SOFT, reducedMotion = true, history = listOf(song(9).asTrack()),
            feed = listOf(FeedSection("Provider recommendations", listOf(playlist))))
        compose.setContent { MusicApp(state) }
        compose.onNodeWithText(playlist.title).assertIsDisplayed()
        compose.onNodeWithText("Song 9").assertDoesNotExist()
        capture("home-full-feed")
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithContentDescription("For you").assertIsSelected()
        compose.onAllNodesWithText("Song 9").onFirst().assertIsDisplayed()
        compose.onNodeWithText(playlist.title).assertDoesNotExist()
        capture("explore-personal-for-you")
    }
    @Test fun aLateFeedResponsePopulatesHomeWithoutSwitchingScreens() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true))
        compose.setContent { MusicApp(state.value) }
        compose.runOnIdle { state.value = state.value.copy(feed = listOf(FeedSection("Late recommendations", listOf(playlist)))) }
        compose.onNodeWithText(playlist.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }
    @Test fun discoverUsesLoadedHomeShelvesWhenTheExploreResponseIsEmpty() {
        val state = MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = listOf(FeedSection("From your provider", listOf(song(1), album))))
        compose.setContent { MusicApp(state, initialPage = "explore") }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.onNodeWithText("From your provider").assertIsDisplayed()
        compose.onNodeWithText("Song 1").assertIsDisplayed()
        capture("discover-empty-response-fallback")
        compose.onNodeWithText("More music awaits").assertDoesNotExist()
    }
    @Test fun aFailedExploreRequestKeepsExistingProviderContentVisible() {
        compose.setContent { MusicApp(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = listOf(FeedSection("Loaded albums", listOf(album))), exploreError = "Explore request failed"), initialPage = "explore") }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.onNodeWithText(album.title).assertIsDisplayed()
        compose.onNodeWithText("Explore request failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
    @Test fun expandingAFallbackShelfUsesTheSameDataAndReturnsToItsTab() {
        compose.setContent { MusicApp(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = listOf(FeedSection("Fallback provider shelf", listOf(song(1))))), initialPage = "explore") }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.onNodeWithText("Fallback provider shelf").performClick()
        compose.onNodeWithText("Song 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Discover").assertIsSelected()
        compose.onNodeWithText("Song 1").assertIsDisplayed()
    }
    @Test fun onlyHomeConsumesTheRecommendationContinuation() {
        var more = 0
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            feed = listOf(FeedSection("Recommendations", listOf(song(1)))), feedHasMore = true))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicApp(state.value, initialPage = "explore", onMoreFeed = { more++; state.value = state.value.copy(feedBusy = true, feedAppending = true) }) }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(0, more) }
        compose.onNodeWithContentDescription("Home").performClick()
        compose.mainClock.autoAdvance = true
        list().performScrollToKey("feed-tail")
        compose.waitUntil(5_000) { more == 1 }
    }
    @Test fun lateProviderDataPreservesTheSelectedExploreTab() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, explore = listOf(FeedSection("New music", listOf(album)))))
        compose.setContent { MusicApp(state.value, initialPage = "explore") }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.runOnIdle { state.value = state.value.copy(feed = listOf(FeedSection("Late playlists", listOf(playlist)))) }
        compose.onNodeWithContentDescription("Discover").assertIsSelected()
        compose.onNodeWithText(playlist.title).assertIsDisplayed()
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
