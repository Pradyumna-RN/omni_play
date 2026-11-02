package labs.rtensor.music.auth

import android.content.Context
import labs.rtensor.music.data.AccountSession
import org.json.JSONObject

data class GoogleProfile(val subject: String, val email: String, val name: String) {
    fun json() = JSONObject().put("subject", subject).put("email", email).put("name", name)
    companion object {
        fun parse(data: JSONObject): GoogleProfile {
            val subject = data.getString("subject"); val email = data.getString("email")
            require(subject.length in 1..255 && email.length in 3..320 && !email.contains('\n') && !email.contains('\r') && email.contains('@'))
            return GoogleProfile(subject, email, data.optString("name").take(200))
        }
    }
}

/** Stores the selected profile only; ID/access tokens are never persisted. */
class GoogleProfileStore(context: Context) {
    private val vault = AccountSession(context, prefsName = "google_profile")
    fun load(): GoogleProfile? = vault.load()?.let { runCatching { GoogleProfile.parse(JSONObject(it)) }.getOrNull() }
    fun save(profile: GoogleProfile) = vault.save(profile.json().toString())
    fun clear() = vault.clear()
}
