package labs.rtensor.music.apple

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/** Opaque, subscriber-owned session. Stringification never prints credentials. */
internal class AppleCredentials(val developerToken: String, val musicUserToken: String,
    val name: String = "Apple Music", val storefront: String = "", val native: Boolean = false) {
    fun json() = JSONObject().put("developerToken", developerToken).put("musicUserToken", musicUserToken)
        .put("name", name).put("storefront", storefront).put("native", native)
    override fun toString() = "AppleCredentials(redacted)"
    companion object {
        fun parse(json: JSONObject): AppleCredentials {
            require(json.toString().length <= 30000)
            val developer = json.getString("developerToken")
            val user = json.getString("musicUserToken")
            require(validToken(developer, 16384) && validToken(user, 16384)) { "Apple did not provide a valid session." }
            require(developer.split('.').size == 3) { "Apple did not provide a developer token." }
            return AppleCredentials(developer, user, json.optString("name", "Apple Music").take(320),
                json.optString("storefront").takeIf { it.matches(Regex("[a-z]{2}")) }.orEmpty(), json.optBoolean("native", false))
        }
        private fun validToken(value: String, max: Int) = value.length in 8..max && value.all { it > ' ' && it != '\u007f' }
    }
}

internal object AppleOrigins {
    private val loginHosts = setOf("music.apple.com", "idmsa.apple.com", "id.apple.com", "appleid.apple.com", "account.apple.com", "auth.music.apple.com")
    private fun https(value: String): Uri? = runCatching { Uri.parse(value).takeIf {
        it.scheme == "https" && it.userInfo == null && it.port in listOf(-1, 443) && !it.host.isNullOrBlank()
    } }.getOrNull()
    fun canConfirm(value: String) = https(value)?.host == "music.apple.com"
    fun canNavigate(value: String) = https(value)?.host in loginHosts
    fun isMedia(value: String): Boolean = https(value)?.host?.let { host ->
        host == "audio.itunes.apple.com" || host.endsWith(".itunes.apple.com") || host.endsWith(".music.apple.com")
    } == true
    fun isApi(value: String): Boolean = https(value)?.let { it.host == "amp-api.music.apple.com" && it.path.orEmpty().startsWith("/v1/") } == true
    fun isLicense(value: String) = value == "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
    fun nativeKey(value: String) = value.length in 10..2048 && value.startsWith("skd://itunes.apple.com/") && value.none { it <= ' ' || it == '\u007f' }
    fun cookieToken(cookie: String): String? {
        val raw = cookie.split(';').firstNotNullOfOrNull { part ->
            part.trim().takeIf { it.startsWith("media-user-token=") }?.substringAfter('=')
        } ?: return null
        return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }
            .getOrNull()?.takeIf { it.length in 8..16384 && it.none { c -> c <= ' ' || c == '\u007f' } }
    }
    fun jwtIsCurrent(value: String, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val parts = value.split('.'); require(parts.size == 3 && value.length <= 16384)
        val body = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        body.getLong("exp") > now / 1000 + 30
    }.getOrDefault(false)
}
