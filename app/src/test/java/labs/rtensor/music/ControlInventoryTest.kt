package labs.rtensor.music

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.auth.GoogleProfile
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.data.Track
import labs.rtensor.music.recommendations.RecommendationEngine
import labs.rtensor.music.sources.SourceSummary
import labs.rtensor.music.ui.MusicApp
import labs.rtensor.music.updates.AppRelease
import labs.rtensor.music.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Exercises every clickable control MusicApp shows in representative states.
 *
 * The inventory tests reset MusicApp before each click (a fresh `key`), click exactly one
 * control, and record what happened: which callbacks fired with which arguments, whether
 * the visible semantics changed (screen, dialog, selection, clipboard), or nothing at all.
 * A full table is written to build/reports/control-inventory/<state>.txt.
 *
 * The targeted tests below assert the specific promised outcome of each control. Tests for
 * known defects assert the correct behaviour, so they fail until the defect is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlInventoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val track = Track("yt:aaaaaaaaaaa", "Afterimage", "Serein", "https://music.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 224000)
    private val other = Track("yt:bbbbbbbbbbb", "Soft Signals", "Hollow Coves", "https://music.youtube.com/watch?v=bbbbbbbbbbb", durationMs = 194000)
    private val third = Track("yt:ccccccccccc", "Low Tide", "Novo Amor", "https://music.youtube.com/watch?v=ccccccccccc", durationMs = 201000)
    private val now = System.currentTimeMillis()
    private val events = listOf(PlayEvent(track, now - 60_000, 120_000, false), PlayEvent(other, now - 3_600_000, 90_000, true))
    private val album = FeedEntry("MPREb_fixture", "Late Windows", "Serein", "", "album", "MPREb_fixture", "")
    private val song = FeedEntry(third.id, third.title, third.artist, "", "song", "", "ccccccccccc")
    private val artist = FeedEntry("UC_fixture", "Serein", "Artist", "", "artist", "UC_fixture", "")
    private val profile = GoogleProfile("fixture-subject", "listener@example.test", "Fixture listener")

    /** Every callback MusicApp exposes, recorded with its arguments. */
    private val calls = mutableListOf<String>()

    @Composable
    private fun App(state: MusicState, page: String, update: ((MusicState) -> MusicState) -> Unit = {}) {
        fun rec(name: String, vararg args: Any?) { calls += name + args.joinToString(", ", "(", ")") { describe(it) } }
        MusicApp(
            state = state,
            onQuery = { rec("onQuery", it); update { s -> s.copy(query = it, searched = s.searched && it.isNotBlank()) } },
            onSearch = { rec("onSearch") },
            onPlay = { t, q -> rec("onPlay", t, q) }, onToggle = { rec("onToggle") },
            onPrevious = { rec("onPrevious") }, onNext = { rec("onNext") }, onSkipTo = { rec("onSkipTo", it) },
            onSeek = { rec("onSeek", it) }, onSave = { rec("onSave", it) },
            onTheme = { rec("onTheme", it) },
            onAccent = { rec("onAccent", it.wire) },
            onLook = { rec("onLook", it.wire) },
            onOpenLocal = { rec("onOpenLocal") }, onChooseMasters = { rec("onChooseMasters") }, onClearMasters = { rec("onClearMasters") },
            onConnectSource = { p, pr, u, t -> rec("onConnectSource", p, pr, u, t) }, onRemoveSource = { rec("onRemoveSource", it) },
            onConnectTidal = { rec("onConnectTidal") }, onDisconnectTidal = { rec("onDisconnectTidal") }, onCancelTidal = { rec("onCancelTidal") },
            onConnectApple = { rec("onConnectApple") }, onNativeApple = { rec("onNativeApple") },
            onDisconnectApple = { rec("onDisconnectApple") }, onCancelApple = { rec("onCancelApple") },
            onClearHistory = { rec("onClearHistory") }, onRetry = { rec("onRetry") }, onDismissError = { rec("onDismissError") },
            initialPage = page,
            onRefreshFeed = { rec("onRefreshFeed") }, onMoreFeed = { rec("onMoreFeed") }, onBrowse = { rec("onBrowse", it.id) },
            onRetryCollection = { rec("onRetryCollection") },
            onConnect = { rec("onConnect") }, onWebSignIn = { rec("onWebSignIn") }, onDisconnect = { rec("onDisconnect") },
            onCancelConnect = { rec("onCancelConnect") }, onRefreshExplore = { rec("onRefreshExplore") },
            onGoogleSignIn = { rec("onGoogleSignIn") }, onGoogleSignOut = { rec("onGoogleSignOut") }, onCancelGoogle = { rec("onCancelGoogle") },
            onCheckUpdates = { rec("onCheckUpdates") }, onDownloadUpdate = { rec("onDownloadUpdate") }, onInstallUpdate = { rec("onInstallUpdate") },
            onAutoplay = { rec("onAutoplay", it) }, onTrackHistory = { rec("onTrackHistory", it) },
            onRemovePlay = { rec("onRemovePlay", it.track) }, onRefreshForYou = { rec("onRefreshForYou") },
            onRetryRadio = { rec("onRetryRadio") }, onProcessing = { rec("onProcessing", it) },
            onMetering = { }, onDeveloperOptions = { rec("onDeveloperOptions", it) },
            onRecommendationEngine = { rec("onRecommendationEngine", it) },
            onConnectSpotify = { rec("onConnectSpotify") }, onDisconnectSpotify = { rec("onDisconnectSpotify") },
            onMoreCollection = { rec("onMoreCollection") },
        )
    }

    private fun describe(value: Any?): String = when (value) {
        is Track -> value.id
        is List<*> -> value.joinToString(",", "[", "]") { describe(it) }
        else -> value.toString()
    }

    // ---------------------------------------------------------------------------------
    // Inventory harness
    // ---------------------------------------------------------------------------------

    private data class Outcome(val index: Int, val label: String, val role: String, val enabled: Boolean,
        val calls: List<String>, val changed: Boolean, val verdict: String)

    private fun label(node: SemanticsNode): String {
        val c = node.config
        val cd = c.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val text = c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val edit = c.getOrNull(SemanticsProperties.EditableText)?.text
        return (cd + text + listOfNotNull(edit)).filter { it.isNotBlank() }.distinct().joinToString(" / ")
    }

    private fun collect(node: SemanticsNode, out: MutableList<String>) {
        val c = node.config
        val bits = listOfNotNull(
            c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            c.getOrNull(SemanticsProperties.Selected)?.let { "sel=$it" },
            c.getOrNull(SemanticsProperties.ToggleableState)?.let { "toggle=$it" },
            c.getOrNull(SemanticsProperties.Focused)?.let { "focus=$it" },
        )
        if (bits.isNotEmpty()) {
            val b = node.boundsInRoot
            out += bits.joinToString("|") + "@${(b.left / 8).toInt()},${(b.top / 8).toInt()}"
        }
        node.children.forEach { collect(it, out) }
    }

    /** Everything a user could perceive: visible text and states on every window, and the clipboard. */
    private fun signature(): List<String> {
        val out = mutableListOf<String>()
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).forEach { collect(it, out) }
        val clip = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        out += "clip=" + clip.primaryClip?.getItemAt(0)?.text
        return out
    }

    private fun inventory(name: String, initial: MusicState, page: String, prepare: () -> Unit = {}): List<Outcome> {
        val state = mutableStateOf(initial)
        val epoch = mutableIntStateOf(0)
        compose.setContent { key(epoch.intValue) { App(state.value, page) } }
        compose.waitForIdle(); prepare(); compose.waitForIdle()
        val total = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val rows = mutableListOf<Outcome>()
        for (i in 0 until total) {
            compose.runOnIdle {
                (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
                state.value = initial; epoch.intValue++
            }
            compose.waitForIdle(); prepare(); compose.waitForIdle()
            val nodes = compose.onAllNodes(hasClickAction())
            val found = nodes.fetchSemanticsNodes()
            if (i >= found.size) break
            val node = found[i]
            val enabled = !node.config.contains(SemanticsProperties.Disabled)
            val role = node.config.getOrNull(SemanticsProperties.Role)?.toString().orEmpty()
            val text = label(node)
            runCatching { nodes[i].performScrollTo() }
            compose.waitForIdle()
            if (runCatching { nodes[i].assertIsDisplayed() }.isFailure) {
                // Composed but outside the window, e.g. the Library page that is not shown. A touch cannot reach it.
                rows += Outcome(i, text, role, enabled, emptyList(), false, "skipped: off-screen"); continue
            }
            calls.clear()
            val before = signature()
            runCatching { nodes[i].performClick() }
            compose.waitForIdle()
            val after = signature()
            val fired = calls.toList()
            val changed = before != after
            val selected = node.config.getOrNull(SemanticsProperties.Selected) == true
            val verdict = when {
                text.isBlank() -> "UNLABELLED"
                !enabled && (fired.isNotEmpty() || changed) -> "DISABLED-BUT-FIRED"
                !enabled -> "disabled (no effect, as shown)"
                fired.isNotEmpty() -> "ok: callback"
                changed -> "ok: UI changed"
                selected || role == Role.Tab.toString() -> "ok: already selected"
                // Library's Saved/History switch has no Selected semantics (see D3), so re-tapping the shown tab is expected to do nothing.
                text.contains(" tab") -> "ok: re-tap of the shown tab (no Selected semantics, D3)"
                else -> "NO-EFFECT"
            }
            rows += Outcome(i, text, role, enabled, fired, changed, verdict)
        }
        val report = buildString {
            appendLine("# $name (initial page: $page) — ${rows.size} clickable controls")
            rows.forEach { r ->
                appendLine("${r.index}. [${r.label}] role=${r.role.ifBlank { "-" }} enabled=${r.enabled} -> " +
                    "${if (r.calls.isEmpty()) "no callback" else r.calls.joinToString("; ")}${if (r.changed) " + UI changed" else ""} => ${r.verdict}")
            }
        }
        println(report)
        File("build/reports/control-inventory").apply { mkdirs() }.resolve("$name.txt").writeText(report)
        return rows
    }

    /** Fails on unlabelled clickables, enabled clickables that do nothing, and disabled ones that still act. */
    private fun assertEveryControlWorks(rows: List<Outcome>) {
        val bad = rows.filter { it.verdict == "UNLABELLED" || it.verdict == "NO-EFFECT" || it.verdict == "DISABLED-BUT-FIRED" }
        assertTrue("Controls with problems:\n" + bad.joinToString("\n") { "${it.index}. [${it.label}] ${it.verdict} ${it.calls}" }, bad.isEmpty())
    }

    private fun base(theme: String = "light") = MusicState(reducedMotion = true, theme = theme)
    private val playing get() = base().copy(current = track, playing = true, positionMs = 72_000, durationMs = 224_000,
        queue = listOf(track, other, third), queueIndex = 0, history = listOf(track, other), playEvents = events, saved = listOf(other))

    @Test fun inventoryFirstLaunchHome() = assertEveryControlWorks(inventory("01-first-launch-home", base(), "home"))

    @Test fun inventoryHomePlayingWithRecommendations() = assertEveryControlWorks(inventory("02-home-playing",
        playing.copy(forYou = listOf(third, other), feedHasMore = true, feedError = "Feed unavailable"), "home"))

    @Test fun inventoryHomeLoadingTrack() = assertEveryControlWorks(inventory("03-home-loading",
        playing.copy(playing = false, resolving = true), "home"))

    @Test fun inventorySearchResults() = assertEveryControlWorks(inventory("04-search-results",
        base().copy(query = "Serein", searched = true, results = listOf(track, other), saved = listOf(track)), "home"))

    @Test fun inventoryLibrarySaved() = assertEveryControlWorks(inventory("05-library-saved",
        base().copy(saved = listOf(track, other), playEvents = events, history = listOf(track, other)), "library"))

    @Test fun inventoryLibraryHistory() = assertEveryControlWorks(inventory("06-library-history",
        base().copy(saved = listOf(track, other), playEvents = events, history = listOf(track, other)), "library") {
        compose.onNodeWithContentDescription("History tab").performClick()
    })

    @Test fun inventoryExplore() = assertEveryControlWorks(inventory("07-explore",
        base().copy(explore = listOf(FeedSection("Fresh discoveries", listOf(album, song, artist)))), "explore"))

    @Test fun inventoryCollection() = assertEveryControlWorks(inventory("08-collection",
        playing.copy(collectionEntry = album, collection = listOf(track, other), collectionHasMore = true), "collection"))

    @Test fun inventoryNowPlayingWithQueue() = assertEveryControlWorks(inventory("09-now-playing",
        playing.copy(radioError = "Recommendations failed."), "now"))

    @Test fun inventoryNowBuffering() = assertEveryControlWorks(inventory("10-now-buffering",
        playing.copy(buffering = true), "now"))

    @Test fun inventorySettingsSignedOut() = assertEveryControlWorks(inventory("11-settings-signed-out", base(), "settings"))

    @Test fun inventorySettingsSignedInEverything() = assertEveryControlWorks(inventory("12-settings-signed-in",
        base().copy(googleProfile = profile, account = AccountState(connected = true, name = "Listener"),
            spotifyAccount = AccountState(connected = true, name = "Spot"), recommendationEngine = RecommendationEngine.SPOTIFY,
            tidalAccount = AccountState(connected = true), appleAccount = labs.rtensor.music.apple.AppleAccountState(connected = true),
            playbackSources = listOf(SourceSummary("s1", "Home server", "Apple Music", "Gateway")),
            update = UpdateState(release = AppRelease("9.9.9", "https://example.test/a.apk", "https://example.test/a.sha", "a.apk", 1), message = "Version 9.9.9 is available."),
            playEvents = events, history = listOf(track, other), mastersSummary = "8 files · 3 Atmos", developerOptions = true),
        "settings"))

    @Test fun inventorySettingsSourcesEditorOpen() = assertEveryControlWorks(inventory("13-settings-sources-editor",
        base().copy(tidalAccount = AccountState(connected = true), playbackSources = listOf(SourceSummary("s1", "Home server", "Apple Music", "Gateway"))),
        "settings") { compose.onNodeWithText("Playback sources").performScrollTo().performClick() })

    @Test fun inventoryErrorBannerOnHome() = assertEveryControlWorks(inventory("14-error-banner",
        playing.copy(error = "YouTube rejected the audio stream (HTTP 403).", errorDiagnostic = "probe 403"), "home"))

    @Test fun inventoryAccountConnecting() = assertEveryControlWorks(inventory("15-settings-connecting",
        base().copy(googleProfile = profile, account = AccountState(connecting = true), spotifyAccount = AccountState(connecting = true),
            update = UpdateState(apkPath = "/x.apk", message = "Verified update. Ready to install.")), "settings"))

    // Soft (the 0.3.31 design) keeps every control working.
    @Test fun inventorySoftHomePlaying() = assertEveryControlWorks(inventory("16-soft-home-playing",
        playing.copy(look = labs.rtensor.music.data.LookChoice.SOFT, forYou = listOf(third, other), feedHasMore = true, feedError = "Feed unavailable"), "home"))

    @Test fun inventorySoftFirstLaunchHome() = assertEveryControlWorks(inventory("17-soft-first-launch-home",
        base().copy(look = labs.rtensor.music.data.LookChoice.SOFT), "home"))

    @Test fun inventorySoftNowPlaying() = assertEveryControlWorks(inventory("18-soft-now-playing",
        playing.copy(look = labs.rtensor.music.data.LookChoice.SOFT, radioError = "Recommendations failed."), "now"))

    @Test fun inventorySoftNowBuffering() = assertEveryControlWorks(inventory("19-soft-now-buffering",
        playing.copy(look = labs.rtensor.music.data.LookChoice.SOFT, buffering = true), "now"))

    @Test fun inventorySoftLibrary() = assertEveryControlWorks(inventory("20-soft-library",
        base().copy(look = labs.rtensor.music.data.LookChoice.SOFT, saved = listOf(track, other), playEvents = events, history = listOf(track, other)), "library"))

    @Test fun inventorySoftExplore() = assertEveryControlWorks(inventory("21-soft-explore",
        base().copy(look = labs.rtensor.music.data.LookChoice.SOFT, explore = listOf(FeedSection("Fresh discoveries", listOf(album, song, artist)))), "explore"))

    @Test fun inventorySoftSettings() = assertEveryControlWorks(inventory("22-soft-settings",
        base(theme = "dark").copy(look = labs.rtensor.music.data.LookChoice.SOFT, googleProfile = profile, account = AccountState(connected = true, name = "Listener"),
            update = UpdateState(release = AppRelease("9.9.9", "https://example.test/a.apk", "https://example.test/a.sha", "a.apk", 1), message = "Version 9.9.9 is available."),
            playEvents = events, history = listOf(track, other), developerOptions = true), "settings"))

    // ---------------------------------------------------------------------------------
    // Targeted promises: each control fires the right callback with the right arguments
    // ---------------------------------------------------------------------------------

    private var hosted = false
    private val hostState = mutableStateOf(MusicState())
    private val hostPage = mutableStateOf("home")
    private val hostEpoch = mutableIntStateOf(0)

    /** Renders (or re-renders from scratch) MusicApp; onQuery feeds back into the state like the ViewModel. */
    private fun host(initial: MusicState, page: String = "home"): MutableState<MusicState> {
        if (!hosted) {
            hosted = true; hostState.value = initial; hostPage.value = page
            compose.setContent { key(hostEpoch.intValue) { App(hostState.value, hostPage.value) { f -> hostState.value = f(hostState.value) } } }
        } else compose.runOnIdle { hostState.value = initial; hostPage.value = page; hostEpoch.intValue++ }
        compose.waitForIdle(); calls.clear()
        return hostState
    }

    /** The mini player is the bottom-most clickable that shows the current title. */
    private fun openMiniPlayer(title: String = "Afterimage") {
        val nodes = compose.onAllNodes(hasClickAction() and hasText(title, substring = true))
        val index = nodes.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.top }.index
        nodes[index].performClick(); compose.waitForIdle()
    }

    private fun systemBack() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun headerBack() { compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle() }
    private fun tab(name: String) { compose.onNode(hasContentDescription(name) and hasClickAction()).performClick(); compose.waitForIdle() }

    /** Which screen is on top, read from its title or header caption. */
    private fun screen(): String {
        val titles = mapOf("Room to listen" to "home", "Back to your music" to "home", "For your ears" to "home",
            "Explore" to "explore", "Library" to "library", "Settings" to "settings", "Now playing" to "now", "Collection" to "collection")
        val shown = titles.filterKeys { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }.values.toSet()
        assertEquals("Exactly one screen should be shown, saw $shown", 1, shown.size)
        return shown.single()
    }

    @Test fun headerAndNavigationControlsGoWhereTheyPromise() {
        host(base())
        assertEquals("home", screen())
        tab("Explore"); assertEquals("explore", screen())
        tab("Library"); assertEquals("library", screen())
        tab("Home"); assertEquals("home", screen())
        compose.onNodeWithContentDescription("Open settings").performClick(); assertEquals("settings", screen())
        headerBack(); assertEquals("home", screen())
        tab("Library"); compose.onNodeWithContentDescription("Music, go home").performClick(); assertEquals("home", screen())
    }

    @Test fun systemBackMatchesHeaderBackFromEverySecondaryScreen() {
        for (origin in listOf("Explore", "Library", "Home")) {
            for (via in listOf("settings", "now")) {
                for (useSystem in listOf(false, true)) {
                    host(playing)
                    tab(origin)
                    if (via == "settings") compose.onNodeWithContentDescription("Open settings").performClick()
                    else openMiniPlayer()
                    compose.waitForIdle()
                    assertEquals(via, screen())
                    if (useSystem) systemBack() else headerBack()
                    assertEquals("$origin → $via → ${if (useSystem) "system" else "header"} back", origin.lowercase(), screen())
                }
            }
        }
    }

    /**
     * D1. System Back on Explore/Library jumps to Home but leaves the remembered tab
     * ("root") on Explore/Library, so the next header or system Back from Settings
     * returns to Explore/Library instead of Home, where the user actually was.
     */
    @Test fun systemBackFromExploreThenSettingsReturnsToHome() {
        host(base())
        tab("Explore"); assertEquals("explore", screen())
        systemBack(); assertEquals("home", screen())
        compose.onNodeWithContentDescription("Open settings").performClick(); assertEquals("settings", screen())
        headerBack()
        assertEquals("Back from Settings must return to the screen it was opened from", "home", screen())
    }

    @Test fun systemBackFromLibraryThenSettingsSystemBackReturnsToHome() {
        host(base())
        tab("Library")
        systemBack(); assertEquals("home", screen())
        compose.onNodeWithContentDescription("Open settings").performClick()
        systemBack()
        assertEquals("home", screen())
    }

    @Test fun systemBackClearsAnActiveSearchBeforeLeavingTheTab() {
        val state = host(base().copy(query = "Serein", searched = true, results = listOf(track)))
        compose.onNodeWithText("1 RESULTS").assertIsDisplayed()
        systemBack()
        compose.runOnIdle { assertEquals(listOf("onQuery()"), calls); assertEquals("", state.value.query) }
        assertEquals("home", screen())
        // Nothing left to go back to on Home: Back must be left to the system (finish the activity).
        compose.runOnIdle { assertTrue(!compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun miniPlayerControlsFireTheirCallbacks() {
        host(playing)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next recording").performClick()
        compose.runOnIdle { assertEquals(listOf("onToggle()", "onNext()"), calls) }
        openMiniPlayer()
        assertEquals("now", screen())
    }

    @Test fun miniPlayerNextIsDisabledAtTheEndOfTheQueue() {
        host(playing.copy(queueIndex = 2, current = third))
        compose.onNodeWithContentDescription("Next recording").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun nowScreenTransportQueueAndSaveFireTheRightCallbacks() {
        host(playing.copy(radioError = "Recommendations failed."), "now")
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        // The full-width cover pushes the queue below the fold, so scroll the list to it first.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Low Tide"))
        compose.onNodeWithText("Low Tide").performScrollTo().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Retry recommendations"))
        compose.onNodeWithText("Retry recommendations").performClick()
        compose.runOnIdle {
            assertEquals(listOf("onPrevious()", "onToggle()", "onNext()", "onSave(${track.id})", "onSkipTo(2)", "onRetryRadio()"), calls)
        }
    }

    @Test fun nowScreenSeekLineSeeksToTheTappedFraction() {
        host(playing, "now")
        compose.onNodeWithContentDescription("Playback position").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(listOf("onSeek(112000)"), calls) }
    }

    /**
     * D2. While a song buffers the full player disables Pause, Previous and Next, although the
     * mini player keeps Pause and Next working in the same state. A stalled song can then only
     * be stopped or skipped from the mini player or the notification.
     */
    @Test fun fullPlayerTransportStaysUsableWhileBufferingLikeTheMiniPlayer() {
        host(playing.copy(buffering = true))
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        compose.onNodeWithContentDescription("Next recording").assertIsEnabled()
        openMiniPlayer()
        assertEquals("now", screen())
        compose.onNode(hasClickAction() and hasContentDescription("Loading")).assertIsEnabled()
        compose.onNodeWithContentDescription("Next").assertIsEnabled()
        compose.onNodeWithContentDescription("Previous").assertIsEnabled()
    }

    @Test fun searchResultControlsPlaySaveAndClear() {
        val state = host(base().copy(query = "Serein", searched = true, results = listOf(track, other), saved = listOf(track)))
        compose.onNodeWithContentDescription("Unsave Afterimage").performClick()
        compose.onNodeWithContentDescription("Save Soft Signals").performClick()
        compose.onNodeWithText("Soft Signals").performClick()
        compose.runOnIdle { assertEquals(listOf("onSave(${track.id})", "onSave(${other.id})", "onPlay(${other.id}, [${other.id}])"), calls) }
        assertEquals("now", screen())
        headerBack()
        compose.onNodeWithText("2 RESULTS").assertIsDisplayed()
        calls.clear()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { assertEquals(listOf("onQuery()"), calls); assertEquals("", state.value.query) }
        assertEquals("home", screen())
    }

    @Test fun searchSubmitButtonIsDisabledForABlankQueryAndSubmitsOtherwise() {
        host(base())
        compose.onNodeWithContentDescription("Search YouTube").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Search music").performTextInput("Serein")
        compose.onNodeWithContentDescription("Search YouTube").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(calls.last() == "onSearch()") }
    }

    @Test fun homeFindMusicFocusesTheSearchField() {
        host(base())
        compose.onNodeWithText("Find music").performClick()
        compose.onNodeWithContentDescription("Search music").assertIsFocused()
    }

    @Test fun homeHeroAndRecommendationControls() {
        host(playing.copy(current = other, queue = listOf(other), queueIndex = 0, forYou = listOf(third, other), feedHasMore = true, feedError = "Feed unavailable"))
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf("onRefreshFeed()", "onRefreshForYou()"), calls) }
        calls.clear()
        compose.onNodeWithText("Play mix").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("onPlay(${third.id}, [${third.id},${other.id}])"), calls) }
        assertEquals("now", screen())
    }

    @Test fun libraryControlsPlayRemoveAndSwitchTabs() {
        host(base().copy(saved = listOf(track, other), playEvents = events, history = listOf(track, other)), "library")
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle { assertEquals(listOf("onPlay(${track.id}, [${track.id},${other.id}])"), calls) }
        headerBack(); assertEquals("library", screen())
        calls.clear()
        compose.onNodeWithContentDescription("History tab").performClick()
        compose.onNodeWithContentDescription("Remove Soft Signals from history").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("onRemovePlay(${other.id})"), calls) }
        compose.onNodeWithContentDescription("Saved tab").performClick()
        compose.onNodeWithText("2 SAVED").assertIsDisplayed()
    }

    /** D3 (accessibility). The Saved/History switch exposes neither a Tab role nor which one is selected. */
    @Test fun libraryTabsExposeTheirSelectedState() {
        host(base(), "library")
        compose.onNodeWithContentDescription("Saved tab").assertIsSelected()
        compose.onNodeWithContentDescription("History tab").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("History tab").assertIsSelected()
    }

    @Test fun exploreEntriesOpenCollectionsPlaySongsAndSearchArtists() {
        host(base().copy(explore = listOf(FeedSection("Fresh discoveries", listOf(album, song, artist)))), "explore")
        compose.onNodeWithText("Late Windows").performClick()
        compose.runOnIdle { assertEquals(listOf("onBrowse(${album.id})"), calls) }
        assertEquals("collection", screen())
        headerBack(); assertEquals("explore", screen())
        calls.clear()
        compose.onNodeWithText("Low Tide").performClick()
        compose.runOnIdle { assertEquals(listOf("onPlay(${third.id}, [${third.id}])"), calls) }
        headerBack(); assertEquals("explore", screen())
        calls.clear()
        compose.onNode(hasText("Serein") and hasText("Artist")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("onQuery(Serein)", "onSearch()"), calls) }
    }

    @Test fun exploreLoadsItselfWhenEmpty() {
        host(base())
        tab("Explore")
        compose.runOnIdle { assertTrue(calls.toString(), "onRefreshExplore()" in calls) }
    }

    @Test fun collectionControls() {
        host(playing.copy(collectionEntry = album, collection = listOf(track, other), collectionHasMore = true), "collection")
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle { assertEquals(listOf("onPlay(${track.id}, [${track.id},${other.id}])"), calls) }
        headerBack(); assertEquals("collection", screen())
        calls.clear()
        compose.onNodeWithText("Load more songs").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Save Afterimage").performClick()
        compose.runOnIdle { assertEquals(listOf("onMoreCollection()", "onSave(${track.id})"), calls) }
    }

    @Test fun errorBannerActionsDependOnTheScreen() {
        host(playing.copy(error = "Failed", errorDiagnostic = "probe 403"))
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Copy details").performClick()
        compose.runOnIdle {
            assertEquals(listOf("onRetry()", "onDismissError()"), calls)
            val clip = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clip.primaryClip!!.getItemAt(0).text.contains("probe 403"))
        }
    }

    @Test fun errorBannerRetryOnSearchRepeatsTheSearchAndOnCollectionReloadsIt() {
        host(base().copy(query = "x", searched = true, error = "Search failed"))
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf("onSearch()"), calls) }
        host(base().copy(collectionEntry = album, error = "Failed"), "collection")
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf("onRetryCollection()"), calls) }
    }

    @Test fun settingsTogglesThemeEngineAndUpdatesFireWithTheRightValues() {
        host(base().copy(autoplay = true, trackHistory = true, playEvents = events,
            update = UpdateState(release = AppRelease("9.9.9", "u", "c", "a.apk", 1))), "settings")
        compose.onNodeWithContentDescription("Spotify recommendations").performScrollTo().performClick()
        compose.onNodeWithContentDescription("YouTube Music recommendations").performClick()
        compose.onNodeWithContentDescription("Autoplay radio").performScrollTo().performClick()
        compose.onNodeWithText("Autoplay radio").performClick()
        compose.onNodeWithContentDescription("Dark theme").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Device theme").performClick()
        compose.onNodeWithContentDescription("Moss accent colour").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Keep listening history").performScrollTo().performClick()
        compose.onNodeWithText("Check updates").performScrollTo().performClick()
        compose.onNodeWithText("Download").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Developer options").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("onConnectSpotify()", "onRecommendationEngine(YOUTUBE)", "onAutoplay(false)", "onAutoplay(false)",
                "onTheme(dark)", "onTheme(system)", "onAccent(moss)", "onTrackHistory(false)", "onCheckUpdates()", "onDownloadUpdate()", "onDeveloperOptions(true)"), calls)
        }
    }

    @Test fun clearHistoryAsksFirstAndKeepCancels() {
        host(base().copy(playEvents = events, history = listOf(track, other)), "settings")
        compose.onNodeWithText("Clear listening history").performScrollTo().performClick()
        compose.onNodeWithText("Keep history").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Clear listening history").performScrollTo().performClick()
        compose.onNodeWithText("Clear history").performClick()
        compose.runOnIdle { assertEquals(listOf("onClearHistory()"), calls) }
        compose.onNodeWithText("Clear history").assertDoesNotExist()
    }

    @Test fun clearHistoryIsNotClickableWhenThereIsNothingToClear() {
        host(base(), "settings")
        compose.onNode(hasText("Clear listening history") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasAnyDescendant(hasText("Clear listening history")) and hasClickAction()).assertDoesNotExist()
    }

    @Test fun accountButtonsMapToTheRightSignInActions() {
        host(base(), "settings")
        compose.onNodeWithText("Sign in with Google").performScrollTo().performClick()
        compose.onNodeWithText("Sign in using browser").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("onGoogleSignIn()", "onWebSignIn()"), calls) }
        host(base().copy(account = AccountState(connected = true, name = "L")), "settings")
        compose.onNodeWithText("Change account").performScrollTo().performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onNodeWithText("Link Google account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("onConnect()", "onDisconnect()", "onGoogleSignIn()"), calls) }
        host(base().copy(googleProfile = profile, account = AccountState(connecting = true)), "settings")
        compose.onNodeWithText("Cancel sign-in").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("onCancelConnect()"), calls) }
    }

    @Test fun providerAccountButtonsInTheSourcesEditor() {
        host(base().copy(tidalAccount = AccountState(connected = true), appleAccount = labs.rtensor.music.apple.AppleAccountState(connected = true),
            spotifyAccount = AccountState(connected = true, name = "S"), playbackSources = listOf(SourceSummary("s1", "Home server", "Apple Music", "Gateway"))), "settings")
        compose.onNodeWithText("Disconnect Spotify").performScrollTo().performClick()
        compose.onNodeWithText("Playback sources").performScrollTo().performClick()
        compose.onNodeWithText("Sign in · AAC stereo").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect Apple Music").performScrollTo().performClick()
        compose.onNodeWithText("Reconnect TIDAL").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect TIDAL").performScrollTo().performClick()
        compose.onNodeWithText("Remove Home server").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("onDisconnectSpotify()", "onConnectApple()", "onDisconnectApple()", "onConnectTidal()", "onDisconnectTidal()", "onRemoveSource(s1)"), calls)
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Sign in · AAC stereo").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------------
    // State retention across recreation
    // ---------------------------------------------------------------------------------

    @Test fun currentScreenSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { App(base(), "home") }
        compose.onNode(hasContentDescription("Library") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Open settings").performClick()
        restore.emulateSavedInstanceStateRestore()
        assertEquals("settings", screen())
        headerBack()
        assertEquals("library", screen())
    }

    @Test fun libraryHistoryTabSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { App(base().copy(saved = listOf(track), playEvents = events), "library") }
        compose.onNodeWithContentDescription("History tab").performClick()
        compose.onNodeWithContentDescription("Remove Afterimage from history").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Remove Afterimage from history").assertIsDisplayed()
    }

    /** D4 (low). The expanded Playback sources editor and a half-typed server URL are lost on rotation. */
    @Test fun openSourcesEditorAndTypedUrlSurviveRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { App(base(), "settings") }
        compose.onNodeWithText("Playback sources").performScrollTo().performClick()
        compose.onNodeWithText("Add a source").performScrollTo().performClick()
        compose.onNodeWithText("HTTPS server URL").performScrollTo().performTextInput("https://personal.example")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("https://personal.example").assertExists()
    }
}
