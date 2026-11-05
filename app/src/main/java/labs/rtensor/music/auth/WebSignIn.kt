package labs.rtensor.music.auth

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * What stopped a sign-in page: [page] is shown on the page, [summary] goes back to Settings when
 * the listener leaves without connecting. Fixed text only; a sign-in URL can carry codes or an
 * email address, so at most a host name is ever included.
 */
internal class WebSignInProblem(val page: String, val summary: String) {
    companion object {
        fun pageFailed(site: String) = WebSignInProblem("$site's page could not load. Check your connection, then tap Reload.",
            "$site's page could not load")
        fun insecure(site: String) = WebSignInProblem("$site's page failed its security check. Check the device clock and network, then tap Reload.",
            "$site's page failed its security check")
        fun stopped(site: String) = WebSignInProblem("$site's page stopped. Open sign-in again from Settings.",
            "the sign-in page stopped")
        fun blocked(host: String) = WebSignInProblem("Sign-in tried to open $host, which can't open inside Music. " +
            "Continue on the sign-in page; if you're already signed in, tap Connect.",
            "sign-in tried to open $host, which can't open inside Music")
        val GOOGLE_REFUSED = WebSignInProblem("Google won't finish signing in inside another app's page. " +
            "Cancel, then use Sign in with Google in Settings to pick an account on this phone.",
            "Google won't sign in inside another app's page. Use Sign in with Google to pick an account on this phone")

        /** Only a host is ever shown. */
        fun hostOf(uri: Uri): String = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9.-]{1,120}")) } ?: "another page"

        /** The line Settings shows after a sign-in that ended without a connection. */
        fun endedMessage(service: String, problem: String?): String {
            val reason = problem?.trim()?.take(240)?.trimEnd('.')?.takeIf { it.isNotBlank() }
            return if (reason == null) "$service sign-in was cancelled before it finished."
                else "$service sign-in didn't finish: $reason."
        }
    }
}

/** Google's pages that refuse an embedded sign-in ("This browser or app may not be secure", disallowed_useragent). */
internal object GoogleSignInPages {
    private val accounts = Regex("accounts\\.google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})")
    fun refused(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty()
        uri.scheme == "https" && accounts.matches(uri.host?.lowercase(Locale.ROOT).orEmpty()) &&
            (path.contains("/signin/rejected") || path.startsWith("/signin/oauth/error") || path.startsWith("/o/oauth2/error"))
    }.getOrDefault(false)

    /** Google has set its session cookies: leaving its sign-in form for YouTube Music no longer loses anything. */
    fun signedIn(cookies: (String) -> String?): Boolean =
        listOf("https://accounts.google.com", "https://music.youtube.com").any { site ->
            cookies(site).orEmpty().split(';').any { part ->
                val name = part.substringBefore('=').trim()
                name in SESSION_COOKIES && part.substringAfter('=', "").isNotBlank()
            }
        }
    private val SESSION_COOKIES = setOf("SID", "SAPISID", "__Secure-1PSID", "__Secure-3PSID")
}

/**
 * Erases a sign-in page's cookies and web storage and writes the erasure to disk before [done].
 * WebView writes its cookie store lazily; a sign-in process that Android ends before then would
 * bring a removed session back the next time a page opens.
 */
internal object SignInWebData {
    fun erase(removeAll: (ValueCallback<Boolean>) -> Unit = { CookieManager.getInstance().removeAllCookies(it) },
        flush: () -> Unit = { CookieManager.getInstance().flush() },
        deleteStorage: () -> Unit = { WebStorage.getInstance().deleteAllData() },
        done: () -> Unit = {}) {
        deleteStorage()
        removeAll { flush(); done() }
    }
    suspend fun eraseAndWait(removeAll: (ValueCallback<Boolean>) -> Unit = { CookieManager.getInstance().removeAllCookies(it) },
        flush: () -> Unit = { CookieManager.getInstance().flush() },
        deleteStorage: () -> Unit = { WebStorage.getInstance().deleteAllData() }) =
        suspendCancellableCoroutine { continuation ->
            erase(removeAll, flush, deleteStorage) { if (continuation.isActive) continuation.resume(Unit) }
        }
}
