package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import labs.rtensor.music.data.*
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Navigation, back handling, overlays and error actions of the Compose shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationAndOverlayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("aaaaaaaaaaa", "Afterimage", "Serein", "https://music.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 200_000)
    private val other = Track("bbbbbbbbbbb", "Second light", "Serein", "https://music.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 180_000)

    /** Waits for the last tap to settle first, as a person's gesture would. */
    private fun systemBack() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }

    @Test fun systemBackFromATabMakesHomeTheRootForLaterPages() {
        compose.setContent { MusicApp(MusicState(reducedMotion = true)) }
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNodeWithText("Explore.").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        // Settings opened from Home must return to Home, not to the tab left a moment ago.
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Settings.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        // System back from Settings resolves to the same page as the header's back.
        compose.onNodeWithContentDescription("Open settings").performClick()
        systemBack()
        compose.onNodeWithText("Explore.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test fun retryOnAPlaybackErrorOverSearchResultsRetriesPlaybackNotTheSearch() {
        var searches = 0; var retries = 0
        compose.setContent {
            MusicApp(MusicState(query = "Serein", searched = true, results = listOf(track, other), current = track, queue = listOf(track), queueIndex = 0,
                error = "YouTube rejected the audio stream (HTTP 403).", reducedMotion = true),
                onSearch = { searches++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, searches) }
    }

    @Test fun retryAfterAFailedSearchRunsTheSearchAgain() {
        var searches = 0; var retries = 0
        compose.setContent {
            MusicApp(MusicState(query = "Serein", searched = true, error = "Search could not reach YouTube.", reducedMotion = true),
                onSearch = { searches++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, searches); assertEquals(0, retries) }
    }

    @Test fun retryOnAPlaybackErrorInALoadedCollectionRetriesPlaybackNotTheCollection() {
        var reloads = 0; var retries = 0
        val entry = FeedEntry("album", "An album", "Serein", "", "album", "album-browse", "")
        compose.setContent {
            MusicApp(MusicState(collectionEntry = entry, collection = listOf(track, other), current = track, queue = listOf(track, other), queueIndex = 0,
                error = "YouTube rejected the audio stream (HTTP 403).", reducedMotion = true),
                initialPage = "collection", onRetryCollection = { reloads++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, reloads) }
    }

    @Test fun retryOnAFailedCollectionReloadsIt() {
        var reloads = 0; var retries = 0
        val entry = FeedEntry("album", "An album", "Serein", "", "album", "album-browse", "")
        compose.setContent {
            MusicApp(MusicState(collectionEntry = entry, error = "Could not open this album.", reducedMotion = true),
                initialPage = "collection", onRetryCollection = { reloads++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, reloads); assertEquals(0, retries) }
    }

    @Test fun switchingTabsKeepsAPlaybackErrorWhenThereIsNoSearchToClear() {
        val queries = mutableListOf<String>()
        val error = "YouTube rejected the audio stream (HTTP 403)."
        val state = mutableStateOf(MusicState(current = track, queue = listOf(track), queueIndex = 0, error = error, reducedMotion = true))
        compose.setContent {
            MusicApp(state.value, onQuery = { q ->
                queries += q
                // Mirrors MusicViewModel.setQuery: a blank query also clears the shared error.
                state.value = if (q.isBlank()) state.value.copy(query = "", searched = false, error = null) else state.value.copy(query = q)
            })
        }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.runOnIdle { assertTrue("No search was open, so nothing must be cleared: $queries", queries.isEmpty()) }
        // An open search is still cleared when you change tabs.
        compose.onNodeWithContentDescription("Search music").performTextInput("Serein")
        compose.onNodeWithContentDescription("Home").performClick()
        compose.runOnIdle { assertEquals("", queries.last()) }
    }

    @Test fun savedSongsSharingAVideoIdButNotALibraryKeyBothShow() {
        // A Spotify-matched copy keeps the YouTube id; the library tells them apart by libraryKey.
        val matched = track.copy(title = "Afterimage (Spotify)", spotifyId = "0123456789abcdefABCDEF")
        compose.setContent { MusicApp(MusicState(saved = listOf(track, matched), reducedMotion = true), initialPage = "library") }
        compose.onNodeWithText("2 SAVED").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        compose.onNodeWithText("Afterimage (Spotify)").assertIsDisplayed()
    }

    @Test fun settingsDialogsSurviveActivityRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { MusicApp(MusicState(history = listOf(track), reducedMotion = true), initialPage = "settings") }
        compose.onNodeWithText("Clear listening history").performScrollTo().performClick()
        compose.onNodeWithText("Clear listening history?").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Clear listening history?").assertIsDisplayed()
        compose.onNodeWithText("Keep history").performClick()
        compose.onNodeWithText("Clear listening history?").assertDoesNotExist()
    }

    @Test fun exploreErrorOffersAVisibleRetry() {
        var refreshes = 0
        compose.setContent {
            MusicApp(MusicState(exploreError = "YouTube Music did not answer.", reducedMotion = true), initialPage = "explore",
                onRefreshExplore = { refreshes++ })
        }
        compose.onNodeWithText("YouTube Music did not answer.").assertIsDisplayed()
        val before = compose.runOnIdle { refreshes }
        compose.onNodeWithText("RETRY").performClick()
        compose.runOnIdle { assertEquals(before + 1, refreshes) }
    }

    @Test fun libraryTabsExposeTabRoleAndSelection() {
        compose.setContent { MusicApp(MusicState(reducedMotion = true), initialPage = "library") }
        val saved = compose.onNodeWithContentDescription("Saved tab")
        val history = compose.onNodeWithContentDescription("History tab")
        history.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        saved.assertIsSelected(); history.assertIsNotSelected()
        history.performClick()
        compose.onNodeWithContentDescription("History tab").assertIsSelected()
        compose.onNodeWithContentDescription("Saved tab").assertIsNotSelected()
    }

    @Test fun homeMonogramHasAFullTouchTarget() {
        compose.setContent { MusicApp(MusicState(reducedMotion = true)) }
        compose.onNodeWithContentDescription("Music, go home").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun fullPlayerTransportWorksWhileBufferingButPlayWaitsWhileFindingAudio() {
        var toggles = 0; var nexts = 0; var previous = 0
        val state = mutableStateOf(MusicState(current = track, queue = listOf(track, other), queueIndex = 0, playing = true, buffering = true,
            durationMs = 200_000, reducedMotion = true))
        compose.setContent { MusicApp(state.value, initialPage = "now", onToggle = { toggles++ }, onNext = { nexts++ }, onPrevious = { previous++ }) }
        compose.onNode(hasClickAction() and hasText("Loading")).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Next").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Previous").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1), listOf(toggles, nexts, previous)) }
        // While the audio is still being found there is nothing to pause yet, in either player.
        compose.runOnIdle { state.value = state.value.copy(buffering = false, resolving = true) }
        compose.onNode(hasClickAction() and hasText("Loading")).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next").assertIsEnabled()
    }

    @Test fun homeHeroDoesNotOfferATapThatDoesNothingWhileFindingAudio() {
        compose.setContent { MusicApp(MusicState(history = listOf(track), current = track, queue = listOf(track), queueIndex = 0, resolving = true, reducedMotion = true)) }
        compose.onNode(hasClickAction() and hasText("Loading")).assertIsNotEnabled()
    }

    @Test fun sourcesEditorFormSurvivesRecreationWithoutKeepingTheToken() {
        val restore = StateRestorationTester(compose)
        restore.setContent { MusicApp(MusicState(reducedMotion = true), initialPage = "settings") }
        compose.onNodeWithText("Playback sources").performScrollTo().performClick()
        compose.onNodeWithText("Add a source").performScrollTo().performClick()
        compose.onNodeWithText("HTTPS server URL").performScrollTo().performTextInput("https://personal.example")
        compose.onNodeWithText("Server access token (optional)").performScrollTo().performTextInput("secret-token")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("https://personal.example").assertExists()
        compose.onNodeWithText("Check and save").assertExists()
        // The token field is empty again: secrets never go into saved instance state.
        compose.onNode(hasSetTextAction() and hasText("Server access token (optional)")).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun oneSavedServerIsSingular() {
        val source = labs.rtensor.music.sources.SourceSummary("s1", "Home server", "TIDAL", "HiFi API")
        compose.setContent { MusicApp(MusicState(tidalAccount = AccountState(connected = true), playbackSources = listOf(source), reducedMotion = true), initialPage = "settings") }
        compose.onNodeWithText("TIDAL signed in · 1 saved server").performScrollTo().assertIsDisplayed()
    }
}
