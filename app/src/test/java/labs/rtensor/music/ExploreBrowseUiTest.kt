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
    @Test fun emptyExploreWithAnExplanationDoesNotPretendThatDiscoveriesAreLoading() {
        compose.setContent { MusicApp(MusicState(look = LookChoice.SOFT, reducedMotion = true,
            exploreError = "YouTube Music returned no Explore items. Pull down to retry."), initialPage = "explore") }
        compose.onNodeWithContentDescription("Discover").performClick()
        compose.onNodeWithText("YouTube Music returned no Explore items. Pull down to retry.").assertIsDisplayed()
        compose.onNodeWithText("More music awaits").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
