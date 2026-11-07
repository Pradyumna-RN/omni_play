package labs.rtensor.music.auth

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Verify Google's signed response before using any returned account/profile data. */
internal object GoogleTokenVerifier {
    fun verify(token: String, nonce: String, webClientId: String, androidClientIds: Collection<String>, jwks: JSONObject,
        now: Long = System.currentTimeMillis() / 1000): GoogleProfile {
        require(token.length in 1..32768 && nonce.isNotBlank())
        val parts = token.split('.'); require(parts.size == 3)
        fun bytes(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
        val header = JSONObject(String(bytes(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256")
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map(keys::getJSONObject).firstOrNull { it.optString("kid") == header.getString("kid") } ?: error("Unknown Google signing key")
        require(key.optString("kty") == "RSA" && key.optString("alg") == "RS256" && key.optString("use", "sig") == "sig")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, bytes(key.getString("n"))), BigInteger(1, bytes(key.getString("e")))))
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }
        require(signature.verify(bytes(parts[2])))
        val claims = JSONObject(String(bytes(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") in setOf("accounts.google.com", "https://accounts.google.com"))
        require(claims.optString("aud") == webClientId)
        require(!claims.has("azp") || claims.optString("azp") in androidClientIds + webClientId)
        require(claims.getLong("exp") > now && claims.getLong("iat") <= now + 120)
        require(claims.getString("nonce") == nonce && claims.optBoolean("email_verified", false))
        return GoogleProfile.parse(JSONObject().put("subject", claims.getString("sub")).put("email", claims.getString("email")).put("name", claims.optString("name")))
    }
}
