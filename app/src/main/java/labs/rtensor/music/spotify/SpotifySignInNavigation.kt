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
        if (host in setOf("accounts.google.com", "consent.google.com", "myaccount.google.com", "gds.google.com",
                "appleid.apple.com", "account.apple.com", "www.facebook.com", "m.facebook.com", "facebook.com")) return true
        val regional = Regex("(accounts|www)\\.google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})")
        return regional.matches(host) && (host.startsWith("accounts.") || uri.path.orEmpty().startsWith("/accounts/"))
    }
    private fun secure(uri: Uri) = uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null
}
