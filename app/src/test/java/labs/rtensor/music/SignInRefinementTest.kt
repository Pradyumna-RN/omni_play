package labs.rtensor.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import labs.rtensor.music.apple.AppleWebLoginActivity
import labs.rtensor.music.auth.SignInWebData
import labs.rtensor.music.auth.WebSignInProblem
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.YouTubeRuntime
import labs.rtensor.music.spotify.SpotifyCatalogueAccess
import labs.rtensor.music.spotify.SpotifyRecommendations
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Every sign-in page reports why it stopped, keeps what was typed, and sign-out really clears. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInRefinementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ticket = "https://accounts.google.com/MergeSession?uberauth=ONE-TIME&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    private fun request(value: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    private val serverError = android.webkit.WebResourceResponse("text/html", "utf-8", 503, "Unavailable", emptyMap(), null)
    private fun youtube(extra: Intent.() -> Unit = {}) = ActivityScenario.launchActivityForResult<YouTubeLoginActivity>(
        Intent(context, YouTubeLoginActivity::class.java).apply(extra))
    private fun page(scenario: ActivityScenario<YouTubeLoginActivity>): WebView { var view: WebView? = null; scenario.onActivity { view = it.page }; return view!! }
    @After fun clean() { CookieManager.getInstance().removeAllCookies(null) }

    @Test fun youtubePageThatCannotLoadSaysSoAndReturnsItsReasonToSettings() {
        val scenario = youtube()
        val view = page(scenario)
        compose.runOnUiThread { view.webViewClient.onReceivedHttpError(view, request("https://accounts.google.com/v3/signin/identifier?Email=private%40example.test"), serverError) }
        compose.onNodeWithText("could not load. Check your connection, then tap Reload", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val result = scenario.result
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        val problem = result.resultData?.getStringExtra(YouTubeLoginActivity.PROBLEM)
        assertNotNull("A failed page must not come back as a silent cancel", problem)
        assertFalse(problem!!, problem.contains("private") || problem.contains("example.test"))
        // Settings shows it on the YouTube account card.
        val runtime = YouTubeRuntime.get(context)
        runtime.beginSignIn(); runtime.signInEnded(problem)
        assertFalse(runtime.account.value.connecting)
        assertEquals("YouTube sign-in didn't finish: Google's page could not load.", runtime.account.value.error)
        runtime.beginSignIn(); runtime.signInEnded(null)
        assertEquals("YouTube sign-in was cancelled before it finished.", runtime.account.value.error)
    }

    @Test fun youtubeBlockedPageIsNamedByHostOnly() {
        val scenario = youtube()
        val view = page(scenario)
        compose.runOnUiThread { assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("https://evil.example.test/x?code=SECRET"))) }
        compose.onNodeWithText("Sign-in tried to open evil.example.test", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val problem = scenario.result.resultData?.getStringExtra(YouTubeLoginActivity.PROBLEM).orEmpty()
        assertTrue(problem, problem.contains("evil.example.test") && !problem.contains("SECRET"))
    }

    @Test fun youtubeConnectBeforeGoogleSignedInKeepsTheLoginForm() {
        val scenario = youtube()
        val view = page(scenario)
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.runOnUiThread { view.loadUrl("https://accounts.google.com/v3/signin/identifier"); view.webViewClient.onPageFinished(view, "https://accounts.google.com/v3/signin/identifier") }
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Finish signing in on Google's page first", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals("https://accounts.google.com/v3/signin/identifier", shadowOf(view).lastLoadedUrl) }
        // Once Google has set its session, Connect continues to YouTube Music.
        CookieManager.getInstance().setCookie("https://accounts.google.com", "SID=session")
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertEquals("https://music.youtube.com/", shadowOf(view).lastLoadedUrl) }
        scenario.close()
    }

    @Test fun googleRefusingTheEmbeddedPagePointsToTheDeviceAccountRoute() {
        val scenario = youtube()
        val view = page(scenario)
        compose.runOnUiThread { view.webViewClient.onPageFinished(view, "https://accounts.google.com/v3/signin/rejected") }
        compose.onNodeWithText("Use Sign in with Google", substring = true, ignoreCase = true).assertIsDisplayed()
        scenario.close()
    }

    @Test fun aCrashedYoutubeRendererEndsSignInWithAReasonInsteadOfEndingMusic() {
        val scenario = youtube()
        val view = page(scenario)
        var handled = false
        compose.runOnUiThread { handled = view.webViewClient.onRenderProcessGone(view, null) }
        assertTrue("Unhandled, Android ends the app process and playback with it", handled)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals("the sign-in page stopped", scenario.result.resultData?.getStringExtra(YouTubeLoginActivity.PROBLEM))
    }

    @Test fun recreatedYoutubeSignInKeepsGoogleCookiesAndNeverReusesTheOneTimeTicket() {
        val scenario = youtube { putExtra(YouTubeLoginActivity.DEVICE_LOGIN_URL, ticket); putExtra(YouTubeLoginActivity.LOGIN_HINT, "listener@example.test") }
        compose.runOnIdle { assertEquals(ticket, shadowOf(page(scenario)).lastLoadedUrl) }
        CookieManager.getInstance().setCookie("https://accounts.google.com", "SID=session")
        scenario.recreate()
        compose.onNodeWithText("Music reopened the sign-in page", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(YouTubeLoginActivity.signInUrl("listener@example.test"), shadowOf(page(scenario)).lastLoadedUrl)
            assertTrue(CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty().contains("SID=session"))
        }
        scenario.close()
    }

    @Test fun signInPagesSurviveThemeAndKeyboardChangesWithoutRestarting() {
        listOf(YouTubeLoginActivity::class.java, AppleWebLoginActivity::class.java).forEach { type ->
            val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, type), PackageManager.GET_META_DATA)
            for (change in listOf(ActivityInfo.CONFIG_UI_MODE, ActivityInfo.CONFIG_KEYBOARD, ActivityInfo.CONFIG_KEYBOARD_HIDDEN, ActivityInfo.CONFIG_ORIENTATION))
                assertTrue("${type.simpleName} would restart sign-in on config change $change", info.configChanges and change != 0)
        }
    }

    @Test fun appleBlockedPageAndCancelReportAReason() {
        val scenario = ActivityScenario.launchActivityForResult<AppleWebLoginActivity>(Intent(context, AppleWebLoginActivity::class.java).putExtra(AppleWebLoginActivity.TICKET, 7L))
        var view: WebView? = null; scenario.onActivity { view = it.page }
        compose.runOnUiThread { assertTrue(view!!.webViewClient.shouldOverrideUrlLoading(view!!, request("https://support.apple.com/HT201487?id=PRIVATE"))) }
        compose.onNodeWithText("Sign-in tried to open support.apple.com", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val result = scenario.result
        assertEquals(7L, result.resultData?.getLongExtra(AppleWebLoginActivity.TICKET, -1))
        val problem = result.resultData?.getStringExtra(AppleWebLoginActivity.PROBLEM).orEmpty()
        assertTrue(problem, problem.contains("support.apple.com") && !problem.contains("PRIVATE"))
    }

    @Test fun erasingSignInDataWritesTheEmptyCookieStoreBeforeReportingDone() {
        val steps = mutableListOf<String>()
        var removal: android.webkit.ValueCallback<Boolean>? = null
        SignInWebData.erase(removeAll = { steps += "remove"; removal = it }, flush = { steps += "flush" },
            deleteStorage = { steps += "storage" }, done = { steps += "done" })
        assertEquals("Nothing may report done while the removal is still pending", listOf("storage", "remove"), steps)
        removal!!.onReceiveValue(true)
        assertEquals(listOf("storage", "remove", "flush", "done"), steps)
    }

    @Test fun spotifySignOutThatCannotReachItsProcessSaysSo() = runBlocking {
        context.getSharedPreferences("spotify_profile", Context.MODE_PRIVATE).edit().putBoolean("connected", true).putString("name", "Listener").commit()
        val api = object : SpotifyCatalogueAccess {
            override suspend fun request(operation: String, args: JSONObject): JSONObject = throw java.io.IOException("process unavailable")
        }
        val spotify = SpotifyRecommendations(context, api)
        assertTrue(spotify.state.value.connected)
        spotify.disconnect()
        assertFalse(spotify.state.value.connected)
        assertEquals(SpotifyRecommendations.SIGN_OUT_UNFINISHED, spotify.state.value.error)
    }

    @Test fun anUnavailableKeyStoreDoesNotSignTheListenerOut() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        var available = true
        val provider: () -> SecretKey = { if (available) key else throw java.security.KeyStoreException("Keystore operation failed") }
        val store = AccountSession(context, "keystore_outage_test", provider)
        store.save("{\"cookie\":\"synthetic\"}")
        available = false
        assertNull(store.load())
        assertTrue("A KeyStore outage must not erase the sealed session", store.isStored())
        available = true
        assertEquals("{\"cookie\":\"synthetic\"}", store.load())
        store.clear()
    }
}
