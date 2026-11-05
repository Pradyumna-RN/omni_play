package labs.rtensor.music.spotify

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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
        override fun failed(message: String) { error = message }
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
}
