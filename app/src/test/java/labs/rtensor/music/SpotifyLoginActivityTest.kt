package labs.rtensor.music

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.spotify.SpotifyLoginActivity
import labs.rtensor.music.spotify.SpotifyRecommendations
import labs.rtensor.music.spotify.SpotifySignInNavigation
import labs.rtensor.music.spotify.SpotifyWebRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real sign-in activity: every way out reports a result, and Connect never discards a login. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpotifyLoginActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<SpotifyLoginActivity>
    private val runtime get() = SpotifyWebRuntime.get(ApplicationProvider.getApplicationContext())
    private val page get() = runtime.view()
    private fun mainFrame(value: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Before fun openSignIn() {
        CookieManager.getInstance().removeAllCookies(null)
        scenario = ActivityScenario.launchActivityForResult(Intent(ApplicationProvider.getApplicationContext(), SpotifyLoginActivity::class.java))
    }
    @After fun closePage() { scenario.close(); compose.runOnUiThread { runtime.close() }; CookieManager.getInstance().removeAllCookies(null) }

    @Test fun leavingAfterAProblemReturnsItsReasonForSettings() {
        compose.runOnUiThread { page.webViewClient.shouldOverrideUrlLoading(page, mainFrame("https://login.example.test/start?code=PRIVATE")) }
        compose.onNodeWithText("login.example.test", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val result = scenario.result
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        val problem = result.resultData?.getStringExtra(SpotifyLoginActivity.PROBLEM)
        assertNotNull("A failed sign-in must not come back to Settings as a silent cancel", problem)
        assertTrue(problem!!, problem.contains("login.example.test") && !problem.contains("PRIVATE"))
        val shown = SpotifyRecommendations.cancelledMessage(problem)
        assertTrue(shown, shown.startsWith("Spotify sign-in didn't finish: sign-in tried to open login.example.test"))
    }

    @Test fun googleRefusingTheInAppPageTellsTheListenerToUseEmailOrPhone() {
        compose.runOnUiThread { page.webViewClient.onPageFinished(page, "https://accounts.google.com/v3/signin/rejected") }
        compose.onNodeWithText("Continue with email or phone number", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val problem = scenario.result.resultData?.getStringExtra(SpotifyLoginActivity.PROBLEM).orEmpty()
        assertTrue(problem, problem.startsWith("Google won't sign in inside another app"))
    }

    @Test fun cancellingStraightAwayIsStillReportedAsACancel() {
        compose.onNodeWithText("Cancel").performClick()
        val result = scenario.result
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertNull(result.resultData?.getStringExtra(SpotifyLoginActivity.PROBLEM))
        assertTrue(SpotifyRecommendations.cancelledMessage(null).contains("cancelled"))
    }

    @Test fun connectBeforeSpotifyHasSignedInKeepsWhatWasTypedOnTheLoginPage() {
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Sign in on Spotify's page below first", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(SpotifySignInNavigation.loginUrl(), shadowOf(page).lastLoadedUrl) }
    }

    @Test fun connectAfterSpotifySignedInOpensSpotifyAndChecksTheAccount() {
        CookieManager.getInstance().setCookie(SpotifySignInNavigation.PLAYER, "sp_dc=session")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Checking your Spotify account…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(SpotifySignInNavigation.PLAYER, shadowOf(page).lastLoadedUrl) }
    }

    @Test fun androidEndingTheSignInProcessReopensSpotifyAndSaysWhatHappened() {
        // Process death: the restored activity finds no page; Spotify's session cookie survived.
        CookieManager.getInstance().setCookie(SpotifySignInNavigation.PLAYER, "sp_dc=session")
        compose.runOnUiThread { runtime.close() }
        scenario.recreate()
        compose.onNodeWithText("Android closed the sign-in page", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(SpotifySignInNavigation.PLAYER, shadowOf(page).lastLoadedUrl) }
    }

    @Test fun rotatingKeepsTheSamePageWithoutClaimingARestart() {
        val before = compose.runOnIdle { page }
        scenario.recreate()
        compose.onNodeWithText("Android closed the sign-in page", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertSame(before, page) }
    }
    @Test fun reloadReplacesTheBlankBrowserAndKeepsItsSession() {
        val before = compose.runOnIdle { page }
        compose.runOnUiThread { CookieManager.getInstance().setCookie(SpotifySignInNavigation.PLAYER, "sp_dc=session") }
        compose.onNodeWithText("Reload").performClick()
        compose.runOnIdle {
            assertNotSame(before, page)
            assertTrue(runtime.hasSessionHint())
            assertEquals(SpotifySignInNavigation.PLAYER, shadowOf(page).lastLoadedUrl)
            assertNotNull(page.parent)
        }
    }

}
