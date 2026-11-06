package labs.rtensor.music.spotify

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class SpotifySignInTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private class Observer : SpotifyLoginObserver {
        var url = ""; var error: String? = null
        override fun pageChanged(url: String, finished: Boolean) { this.url = url }
        override fun failed(problem: SpotifySignInProblem) { error = problem.page }
    }
    private fun request(value: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    @Test fun loginStartsOnSpotifyAccountsWithAFixedReturnToSpotify() {
        val uri = Uri.parse(SpotifySignInNavigation.loginUrl())
        assertEquals("https", uri.scheme); assertEquals("accounts.spotify.com", uri.host)
        assertEquals("/en/login", uri.path); assertEquals("https://open.spotify.com/", uri.getQueryParameter("continue"))
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val view = runtime.showLogin(observer, fresh = true)
        try { assertEquals(SpotifySignInNavigation.loginUrl(), shadowOf(view).lastLoadedUrl) }
        finally { runtime.hideLogin(observer); runtime.close() }
    }
    @Test fun trustedIdentityProvidersAreAllowedOnlyDuringInteractiveLoginAndCannotReceiveTheCatalogueBridge() {
        for (url in listOf("https://accounts.google.com/o/oauth2/auth", "https://accounts.google.co.in/accounts/SetSID", "https://www.google.co.in/accounts/SetSID",
            "https://appleid.apple.com/auth/authorize", "https://www.facebook.com/dialog/oauth")) {
            assertTrue(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url), true))
            assertFalse(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url)))
            assertFalse(url, SpotifyWebRuntime.playerOrigin(url))
        }
        for (url in listOf("https://accounts.google.com.attacker.test/", "https://appleid.apple.com.attacker.test/", "https://user:password@accounts.google.com/",
            "http://accounts.google.com/", "https://accounts.google.com:444/", "https://www.google.co.in/search", "intent://login", "file:///tmp/page")) {
            assertFalse(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url), true))
        }
    }
    @Test fun serviceClosingDoesNotDestroyAnActiveLoginAndHiddenMetadataCannotFollowItsGoogleRedirect() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val view = runtime.showLogin(observer, fresh = true)
        try {
            runtime.closeIfIdle(); assertSame(view, runtime.view())
            assertFalse(view.webViewClient.shouldOverrideUrlLoading(view, request("https://accounts.google.com/o/oauth2/auth")))
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("https://attacker.test/login")))
            assertNotNull(observer.error)
            runtime.hideLogin(observer)
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("https://accounts.google.com/o/oauth2/auth")))
        } finally { runtime.hideLogin(observer); runtime.close() }
    }
    @Test fun navigatingDuringCatalogueStartupDoesNotStrandOrFailTheWaitingVerification() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        runtime.showLogin(observer, fresh = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val pending = scope.launch {
            try { runtime.call("profile", org.json.JSONObject()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            runtime.openPlayer(); shadowOf(Looper.getMainLooper()).idle()
            runtime.openPlayer(); shadowOf(Looper.getMainLooper()).idle()
            assertNull(failure); assertTrue(pending.isActive)
        } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle(); runtime.hideLogin(observer); runtime.close() }
    }

    @Test fun googleFacebookAndAppleHandOffPagesCanFinishAnInteractiveSignIn() {
        // Google sets a fresh sign-in on YouTube before returning to Spotify; Facebook can answer
        // from its regional web host. Blocking these hops stranded sign-in halfway.
        for (url in listOf("https://accounts.youtube.com/accounts/SetSID?ssdc=1", "https://web.facebook.com/login.php",
            "https://mbasic.facebook.com/login.php", "https://idmsa.apple.com/appleauth/auth/signin")) {
            assertTrue(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url), true))
            assertFalse(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url)))
            assertFalse(url, SpotifyWebRuntime.playerOrigin(url))
        }
        for (url in listOf("https://accounts.youtube.com.attacker.test/accounts/SetSID", "https://web.facebook.com.attacker.test/",
            "http://accounts.youtube.com/accounts/SetSID", "https://www.youtube.com/watch?v=x", "https://attacker.facebook.com.test/")) {
            assertFalse(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url), true))
        }
    }
    @Test fun aBlockedPageIsNamedByItsHostOnlyAndThatReasonIsKept() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val view = runtime.showLogin(observer, fresh = true)
        try {
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("https://login.example.test/start?code=PRIVATE_CODE&email=private@example.test")))
            val error = checkNotNull(observer.error)
            assertTrue(error, error.contains("login.example.test"))
            assertFalse(error, error.contains("PRIVATE_CODE") || error.contains("private@example.test"))
        } finally { runtime.hideLogin(observer); runtime.close() }
    }
    @Test fun googleRefusingTheInAppPageExplainsTheEmailOrPhoneRoute() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val view = runtime.showLogin(observer, fresh = true)
        try {
            view.webViewClient.onPageFinished(view, "https://accounts.google.com/v3/signin/identifier?continue=x")
            assertNull("An ordinary Google page is not a failure", observer.error)
            view.webViewClient.onPageFinished(view, "https://accounts.google.com/v3/signin/rejected?continue=x")
            assertEquals(SpotifySignInProblem.GOOGLE_REFUSED.page, observer.error)
            observer.error = null
            view.webViewClient.onPageFinished(view, "https://accounts.google.co.in/signin/oauth/error?authError=ZGlzYWxsb3dlZF91c2VyYWdlbnQ")
            assertEquals(SpotifySignInProblem.GOOGLE_REFUSED.page, observer.error)
            assertTrue(SpotifySignInProblem.GOOGLE_REFUSED.page.contains("email or phone number"))
        } finally { runtime.hideLogin(observer); runtime.close() }
    }
    @Test fun afterAndroidEndsTheSignInProcessASurvivingSessionReopensSpotifyInsteadOfTheLoginForm() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val cookies = CookieManager.getInstance()
        try {
            runtime.close(); cookies.removeAllCookies(null)
            cookies.setCookie(SpotifySignInNavigation.PLAYER, "sp_dc=session")
            // A restored activity in a new process: no page exists yet, Spotify kept its session.
            assertEquals(SpotifySignInNavigation.PLAYER, shadowOf(runtime.showLogin(observer, fresh = false)).lastLoadedUrl)
            runtime.hideLogin(observer); runtime.close()
            // A new sign-in always starts on Spotify's login page.
            assertEquals(SpotifySignInNavigation.loginUrl(), shadowOf(runtime.showLogin(observer, fresh = true)).lastLoadedUrl)
            runtime.hideLogin(observer); runtime.close(); cookies.removeAllCookies(null)
            assertEquals(SpotifySignInNavigation.loginUrl(), shadowOf(runtime.showLogin(observer, fresh = false)).lastLoadedUrl)
        } finally { runtime.hideLogin(observer); runtime.close(); cookies.removeAllCookies(null) }
    }
    @Test fun theSignInPageUsesTheActivityWindowOnlyWhileItIsShown() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        try {
            val view = runtime.showLogin(observer, fresh = true, window = activity)
            val host = view.context as MutableContextWrapper
            assertSame(activity, host.baseContext)
            runtime.hideLogin(observer)
            assertNotSame("A hidden catalogue page must not keep the activity", activity, host.baseContext)
            assertTrue(host.baseContext is Application)
        } finally { runtime.hideLogin(observer); runtime.close() }
    }
    @Test fun staleLoginOwnersCannotDetachTheNewOwnersView() {
        val runtime = SpotifyWebRuntime.get(context); val old = Observer(); val next = Observer()
        val view = runtime.showLogin(old, fresh = true)
        val parent = android.widget.FrameLayout(context); parent.addView(view)
        try {
            assertSame(view, runtime.showLogin(next, fresh = false))
            runtime.hideLogin(old)
            assertSame(parent, view.parent)
            runtime.hideLogin(next)
            assertNull(view.parent)
        } finally { runtime.hideLogin(next); runtime.close() }
    }
    @Test fun reloadRecreatesABlankViewWithoutClearingTheSessionCookie() {
        val runtime = SpotifyWebRuntime.get(context); val observer = Observer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val cookie = CookieManager.getInstance()
        try {
            val before = runtime.showLogin(observer, fresh = true, window = activity)
            cookie.setCookie(SpotifySignInNavigation.PLAYER, "sp_dc=session")
            val after = runtime.reloadLogin(observer, activity)
            assertNotSame(before, after)
            val currentUrl = observer.url
            before.webViewClient.onPageStarted(before, "https://accounts.spotify.com/stale", null)
            assertEquals(currentUrl, observer.url)
            assertTrue(runtime.hasSessionHint())
            assertEquals(SpotifySignInNavigation.PLAYER, shadowOf(after).lastLoadedUrl)
        } finally { runtime.hideLogin(observer); runtime.close(); cookie.removeAllCookies(null) }
    }
    @Test fun signInSubframesMayUseBlankOrSpotifyBlobDocumentsButNotLocalFiles() {
        for (url in listOf("about:blank", "about:srcdoc", "blob:https://accounts.spotify.com/session", "https://challenge.spotify.com/")) assertTrue(url, SpotifySignInNavigation.allowedSubframe(Uri.parse(url)))
        for (url in listOf("file:///private/data", "content://private/data", "javascript:alert(1)", "blob:https://attacker.test/payload")) assertFalse(url, SpotifySignInNavigation.allowedSubframe(Uri.parse(url)))
    }

}
