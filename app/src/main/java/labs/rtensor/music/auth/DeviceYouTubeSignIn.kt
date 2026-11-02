package labs.rtensor.music.auth

import android.accounts.Account
import android.accounts.OperationCanceledException
import kotlinx.coroutines.CancellationException
import android.accounts.AccountManager
import android.app.Activity
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ask Google's account provider for a one-use web sign-in URL, with its consent UI. */
class DeviceYouTubeSignIn {
    suspend fun loginUrl(activity: Activity, email: String): String = suspendCancellableCoroutine { continuation ->
        val future = AccountManager.get(activity).getAuthToken(
            Account(email, "com.google"), TOKEN_TYPE, null, activity,
            { result ->
                try {
                    val url = result.result.getString(AccountManager.KEY_AUTHTOKEN)
                    require(isLoginUrl(url)) { "Google requires browser sign-in for this account." }
                    if (continuation.isActive) continuation.resume(checkNotNull(url))
                } catch (error: OperationCanceledException) {
                    if (continuation.isActive) continuation.cancel(CancellationException("Google sign-in cancelled", error))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, null,
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
    companion object {
        private const val TOKEN_TYPE = "weblogin:service=youtube&continue=https://music.youtube.com/"
        fun isLoginUrl(value: String?): Boolean = runCatching {
            require(!value.isNullOrBlank() && value.length <= 16000)
            val uri = Uri.parse(value)
            uri.scheme == "https" && uri.host == "accounts.google.com" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
                uri.path == "/MergeSession" && uri.getQueryParameter("continue")?.let {
                    val next = Uri.parse(it)
                    next.scheme == "https" && next.host == "music.youtube.com" && next.userInfo == null && next.port in listOf(-1,443)
                } == true
        }.getOrDefault(false)
    }
}
