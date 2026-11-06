package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
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
class ExploreBrowseUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val charts = FeedEntry("FEmusic_charts", "Charts", "", "", "browse", "FEmusic_charts", "")
    private val moods = charts.copy(id = "FEmusic_moods_and_genres", title = "Moods & genres", browseId = "FEmusic_moods_and_genres")
    private val song = FeedEntry("yt:aaaaaaaaaaa", "Chart song", "Artist", "", "song", "", "aaaaaaaaaaa")
    @Test fun exploreShowsCategoryButtonsAndTheyOpenRealProviderSections() {
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            explore = listOf(FeedSection("Browse", listOf(charts, moods)), FeedSection("New music", listOf(song)))))
        val opened = mutableListOf<FeedEntry>()
        compose.setContent { MusicApp(state.value, initialPage = "explore", onBrowse = { entry ->
            opened += entry
            state.value = state.value.copy(collectionEntry = entry, collectionSections = listOf(FeedSection("Top songs", listOf(song))))
        }, onPlay = { track, _ -> state.value = state.value.copy(current = track, playing = true) }) }
        compose.onNodeWithText("Charts").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(charts), opened) }
        compose.onNodeWithText("Top songs").assertIsDisplayed()
        compose.onNodeWithText("Chart song").performClick()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Top songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Moods & genres").assertIsDisplayed()
    }
    @Test fun genreBackRestoresItsParentInsteadOfSkippingToExplore() {
        val rock = charts.copy(id = "FEmusic_category:ROCK", browseId = "FEmusic_category", title = "Rock", params = "ROCK")
        val choices = (0..11).map { i -> if (i == 7) rock else rock.copy(id = "FEmusic_category:$i", title = "Genre $i", params = "GENRE_$i") }
        val state = mutableStateOf(MusicState(look = LookChoice.SOFT, reducedMotion = true, explore = listOf(FeedSection("Browse", listOf(moods)))))
        val history = CollectionBrowsingHistory()
        compose.setContent { MusicApp(state.value, initialPage = "explore", onBrowseNavigation = { entry, nested ->
            history.entering(state.value, nested, null, 0)
            state.value = state.value.copy(collectionEntry = entry, collectionSections = if (entry.id == moods.id)
                listOf(FeedSection("Pick a genre", choices)) else listOf(FeedSection("Rock tracks", listOf(song))))
        }, onCollectionBack = {
            val frame = history.pop()
            if (frame == null) false else { state.value = frame.restore(state.value); true }
        }) }
        compose.onNodeWithText("Moods & genres").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasText("Genre 0"))).performScrollToIndex(7)
        val position = compose.onNodeWithText("Rock").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Rock").performClick()
        compose.onNodeWithText("Rock tracks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Pick a genre").assertIsDisplayed()
        compose.onNodeWithText("Rock").assertIsDisplayed()
        assertEquals(position.left.value, compose.onNodeWithText("Rock").getUnclippedBoundsInRoot().left.value, 1f)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Moods & genres").assertIsDisplayed()
    }
    @Test fun emptyExploreWithAnExplanationDoesNotPretendThatDiscoveriesAreLoading() {
        compose.setContent { MusicApp(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            exploreError = "YouTube Music returned no Explore items. Pull down to retry."), initialPage = "explore") }
        compose.onNodeWithText("YouTube Music returned no Explore items. Pull down to retry.").assertIsDisplayed()
        compose.onNodeWithText("More music awaits").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
