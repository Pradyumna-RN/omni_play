package labs.rtensor.music.spotify

import android.net.Uri
import java.util.Locale

/** Sign-in can visit provider pages; the catalogue bridge remains Spotify-only. */
internal object SpotifySignInNavigation {
    const val PLAYER = "https://open.spotify.com/"
    fun loginUrl(): String = Uri.parse("https://accounts.spotify.com/en/login").buildUpon()
        .appendQueryParameter("continue", PLAYER).build().toString()
    fun player(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url)
        secure(uri) && uri.host?.lowercase(Locale.ROOT) == "open.spotify.com"
    }.getOrDefault(false)
    fun allowed(uri: Uri, identityProviders: Boolean): Boolean {
        if (!secure(uri)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "spotify.com" || host.endsWith(".spotify.com")) return true
        if (!identityProviders) return false
        // Google signs a fresh account in on YouTube and the regional Google domain before it
        // returns to Spotify, and Facebook can answer from its regional web host. Blocking any of
        // these top-level hops strands the sign-in halfway (YouTube sign-in allows the same hops).
        if (host in setOf("accounts.google.com", "consent.google.com", "myaccount.google.com", "gds.google.com", "accounts.youtube.com",
                "appleid.apple.com", "account.apple.com", "idmsa.apple.com",
                "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com", "facebook.com")) return true
        val regional = Regex("(accounts|www)\\.google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})")
        return regional.matches(host) && (host.startsWith("accounts.") || uri.path.orEmpty().startsWith("/accounts/"))
    }

    fun allowedSubframe(uri: Uri): Boolean = when (uri.scheme) {
        "https" -> secure(uri)
        "about" -> uri.toString() in setOf("about:blank", "about:srcdoc")
        "blob" -> runCatching { allowed(Uri.parse(uri.schemeSpecificPart), false) }.getOrDefault(false)
        else -> false
    }

    /**
     * Google answers an embedded sign-in it will not complete with its "rejected" or OAuth error
     * page (for example "This browser or app may not be secure" or disallowed_useragent). The
     * listener can still reach the same Spotify account by email or phone number with a code.
     */
    fun refusedByGoogle(url: String?): Boolean = googleAccounts(url) && Uri.parse(url).path.orEmpty().let { path ->
        path.contains("/signin/rejected") || path.startsWith("/signin/oauth/error") || path.startsWith("/o/oauth2/error")
    }
    fun googleAccounts(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url)
        secure(uri) && Regex("accounts\\.google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})").matches(uri.host?.lowercase(Locale.ROOT).orEmpty())
    }.getOrDefault(false)

    /** Only a host is ever shown: a sign-in URL can carry codes or an email address. */
    fun hostOf(uri: Uri): String = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9.-]{1,120}")) } ?: "another page"

    private fun secure(uri: Uri) = uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null
}

/** What went wrong, for the sign-in page and as a short reason for Settings. Fixed text only. */
internal class SpotifySignInProblem(val page: String, val summary: String) {
    companion object {
        val NO_WEBVIEW = SpotifySignInProblem("Spotify sign-in needs Android System WebView. Update it, then tap Reload.",
            "Android System WebView is missing or out of date")
        val PAGE_FAILED = SpotifySignInProblem("The sign-in page could not load. Check your connection and tap Reload.",
            "the sign-in page could not load")
        val PAGE_ERROR = SpotifySignInProblem("The sign-in page could not load. Tap Reload to try again.",
            "the sign-in page could not load")
        val INSECURE = SpotifySignInProblem("The sign-in page failed its security check. Check the device clock and network, then tap Reload.",
            "the sign-in page failed its security check")
        val PAGE_STOPPED = SpotifySignInProblem("The sign-in page stopped. Tap Reload to reopen it.",
            "the sign-in page stopped")
        val GOOGLE_REFUSED = SpotifySignInProblem("Google won't finish signing in inside another app. Tap Sign in, then choose " +
            "Continue with email or phone number. Spotify sends a code to the email or phone on your account.",
            "Google won't sign in inside another app. Next time, choose Continue with email or phone number on Spotify's page")
        val EMPTY_PAGE = SpotifySignInProblem("Spotify's page stayed blank. Tap Reload to open a fresh browser view. If it stays blank, update Android System WebView or Chrome and check your connection.",
            "Spotify's sign-in page stayed blank")
        val NOT_SIGNED_IN = SpotifySignInProblem("Finish signing in to Spotify, then tap Connect. Tap Sign in to reopen the login page.",
            "Spotify hadn't signed you in yet")
        val SLOW = SpotifySignInProblem("Spotify is still opening your account. Wait for the page, then tap Connect.",
            "Spotify took too long to open your account")
        val NOT_VERIFIED = SpotifySignInProblem("Could not verify the Spotify account yet. Wait for Spotify to open, then tap Connect or Reload.",
            "Music couldn't confirm your Spotify account")
        fun blocked(host: String) = SpotifySignInProblem("Sign-in tried to open $host, which can't finish inside Music. " +
            "Tap Sign in and continue with your email or phone number instead.",
            "sign-in tried to open $host, which can't finish inside Music. Next time, use your email or phone number")
    }
}
