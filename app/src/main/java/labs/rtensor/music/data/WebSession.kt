package labs.rtensor.music.data

import org.json.JSONObject
import java.net.URI

data class WebSession(val cookie: String, val visitorData: String, val authUser: Int = 0,
    val dataSyncId: String = "", val clientVersion: String = "", val userAgent: String = "", val delegatedSessionId: String = "",
    val playerScript: String = "", val musicContentBoundGvs: Boolean? = null) {
    fun json() = JSONObject().put("kind", "youtube-web").put("cookie", cookie).put("visitorData", visitorData)
        .put("authUser", authUser).put("dataSyncId", dataSyncId).put("clientVersion", clientVersion).put("userAgent", userAgent).put("delegatedSessionId", delegatedSessionId)
        .put("playerScript", playerScript).put("musicContentBoundGvs", musicContentBoundGvs ?: JSONObject.NULL)
    companion object {
        fun parse(o: JSONObject): WebSession {
            require(o.optString("kind") == "youtube-web") { "Sign in again with YouTube." }
            val cookie = o.getString("cookie")
            require(cookie.length <= 24000 && !cookie.contains('\r') && !cookie.contains('\n'))
            require(cookie.split(';').any { it.trim().startsWith("SAPISID=") && it.substringAfter('=').isNotBlank() }) { "Finish signing in to YouTube first." }
            val visitor = o.getString("visitorData")
            require(visitor.isNotBlank() && visitor.length <= 4096)
            val index = o.optInt("authUser", 0)
            require(index in 0..99)
            val delegated = o.optString("delegatedSessionId")
            require(delegated.isEmpty() || delegated.matches(Regex("[0-9]{1,64}")))
            val script = o.optString("playerScript")
            require(script.length <= 4096)
            if (script.isNotEmpty()) {
                val u = URI("https://www.youtube.com").resolve(script)
                require(u.scheme == "https" && u.host == "www.youtube.com" && u.userInfo == null && u.port in listOf(-1, 443) && u.path.startsWith("/s/player/"))
            }
            val bound = if (o.isNull("musicContentBoundGvs")) null else o.get("musicContentBoundGvs").also { require(it is Boolean) } as Boolean
            return WebSession(cookie, visitor, index, o.optString("dataSyncId").take(256), o.optString("clientVersion").take(80), o.optString("userAgent").take(1024), delegated, script, bound)
        }
        fun canConfirm(url: String) = runCatching {
            val u = URI(url); u.scheme == "https" && u.host == "music.youtube.com" && u.userInfo == null && u.port in listOf(-1, 443)
        }.getOrDefault(false)
        fun isSignInNavigation(url: String) = runCatching {
            val u = URI(url)
            u.scheme == "https" && u.userInfo == null && u.port in listOf(-1, 443) && u.host in setOf(
                "accounts.google.com", "accounts.youtube.com", "music.youtube.com", "www.youtube.com", "youtube.com",
                "consent.youtube.com", "consent.google.com", "myaccount.google.com", "gds.google.com")
        }.getOrDefault(false)
    }
}
