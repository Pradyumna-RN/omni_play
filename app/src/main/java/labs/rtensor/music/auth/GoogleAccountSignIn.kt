package labs.rtensor.music.auth

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import labs.rtensor.music.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Native device account selection. This does not claim to grant a YouTube web session. */
class GoogleAccountSignIn(context: Context) {
    private val manager = CredentialManager.create(context.applicationContext)
    private val webClient = context.getString(R.string.google_web_client_id)
    private val androidClient = context.getString(R.string.google_android_client_id)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()

    suspend fun select(activity: Activity): GoogleProfile {
        val nonce = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val option = GetSignInWithGoogleOption.Builder(webClient).setNonce(nonce).build()
        val result = manager.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val keys = loadKeys()
        return withContext(Dispatchers.Default) { GoogleTokenVerifier.verify(token, nonce, webClient, androidClient, keys) }
    }
    private suspend fun loadKeys(): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url("https://www.googleapis.com/oauth2/v3/certs").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        check(it.isSuccessful)
                        val bytes = it.body?.byteStream()?.use { input ->
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 262144); out.write(buffer, 0, n) }
                            out.toByteArray()
                        } ?: error("No Google keys")
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(data)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    suspend fun clearSelection() { manager.clearCredentialState(ClearCredentialStateRequest()) }
}
