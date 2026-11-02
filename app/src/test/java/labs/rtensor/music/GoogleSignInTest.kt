package labs.rtensor.music

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.auth.GoogleProfile
import labs.rtensor.music.auth.GoogleTokenVerifier
import labs.rtensor.music.data.AccountSession
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleSignInTest {
    companion object {
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun claims() = JSONObject().put("iss", "https://accounts.google.com").put("aud", "web-client").put("azp", "android-client")
        .put("nonce", "one-use-nonce").put("sub", "fixture-subject").put("email", "fixture@example.test").put("email_verified", true)
        .put("name", "Fixture listener").put("iat", 1000).put("exp", 1600)
    private fun token(payload: JSONObject = claims(), header: JSONObject = JSONObject().put("alg", "RS256").put("kid", "fixture-key")): String {
        val body = encode(header.toString().toByteArray()) + "." + encode(payload.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(body.toByteArray()) }
        return "$body.${encode(signer.sign())}"
    }
    private fun keys(): JSONObject {
        val key = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "fixture-key").put("kty", "RSA").put("alg", "RS256").put("use", "sig")
            .put("n", encode(key.modulus.toByteArray())).put("e", encode(key.publicExponent.toByteArray()))))
    }
    private fun verify(value: String, jwks: JSONObject = keys()) = GoogleTokenVerifier.verify(value, "one-use-nonce", "web-client", "android-client", jwks, now = 1200)

    @Test fun signedBoundIdentityIsAcceptedWithoutPersistingAToken() {
        val profile = verify(token())
        assertEquals(GoogleProfile("fixture-subject", "fixture@example.test", "Fixture listener"), profile)
        assertEquals(setOf("subject", "email", "name"), profile.json().keys().asSequence().toSet())
    }
    @Test fun modifiedClaimsCannotReuseAGenuineSignature() {
        val pieces = token().split('.')
        val forged = pieces[0] + "." + encode(claims().put("email", "attacker@example.test").toString().toByteArray()) + "." + pieces[2]
        assertTrue(runCatching { verify(forged) }.isFailure)
    }
    @Test fun expiredWrongAudienceNonceIssuerAndCallerAreRejected() {
        val invalid = listOf(claims().put("exp", 1100), claims().put("iat", 2000), claims().put("aud", "another-app"), claims().put("nonce", "another-login"),
            claims().put("iss", "https://attacker.test"), claims().put("azp", "another-android-app"), claims().put("email_verified", false))
        invalid.forEach { assertTrue(it.toString(), runCatching { verify(token(it)) }.isFailure) }
    }
    @Test fun unsupportedAlgorithmsAndUnknownSigningKeysAreRejected() {
        assertTrue(runCatching { verify(token(header = JSONObject().put("alg", "none").put("kid", "fixture-key"))) }.isFailure)
        assertTrue(runCatching { verify(token(), JSONObject().put("keys", JSONArray())) }.isFailure)
    }
    @Test fun googleProfileAndYoutubeCredentialsHaveSeparateEncryptedStorage() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val youtube = AccountSession(context) { key }
        val google = AccountSession(context, prefsName = "google_profile_test") { key }
        val profile = verify(token())
        youtube.clear(); google.clear()
        youtube.save("{\"cookie\":\"fixture-cookie\"}"); google.save(profile.json().toString())
        val raw = context.getSharedPreferences("google_profile_test", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(raw.contains(profile.email)); youtube.clear()
        assertEquals(profile, GoogleProfile.parse(JSONObject(google.load()!!))); google.clear()
    }
    @Test fun loginHintCannotChangeTheGoogleHostOrYoutubeContinueDestination() {
        val email = "fixture+tag@example.test&continue=https://attacker.test"
        val uri = android.net.Uri.parse(YouTubeLoginActivity.signInUrl(email))
        assertEquals("accounts.google.com", uri.host); assertEquals("https://music.youtube.com", uri.getQueryParameter("continue"))
        assertEquals(email, uri.getQueryParameter("login_hint"))
        assertNull(android.net.Uri.parse(YouTubeLoginActivity.signInUrl("bad\r\n@example.test")).getQueryParameter("login_hint"))
    }
}
