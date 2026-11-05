package labs.rtensor.music.spotify

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import labs.rtensor.music.ui.MusicTheme
import org.json.JSONObject

/** Spotify's own sign-in page with one Connect action; only a verified Spotify profile completes it. */
class SpotifyLoginActivity : ComponentActivity() {
    private var engine: SpotifyWebRuntime? = null
    private var browser by mutableStateOf<WebView?>(null)
    private var pageUrl by mutableStateOf("")
    private var message by mutableStateOf(INSTRUCTIONS)
    private var busy by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var attemptedAutoConnect = false
    private var confirmationJob: Job? = null
    /** The last problem shown here. Leaving without connecting reports it in Settings. */
    private var problem: SpotifySignInProblem? = null
    private val observer = object : SpotifyLoginObserver {
        override fun pageChanged(url: String, finished: Boolean) {
            pageUrl = url; loading = !finished
            if (!finished) attemptedAutoConnect = false
            if (finished && !busy && !attemptedAutoConnect && SpotifySignInNavigation.player(url) && engine?.canRequest == true && engine?.hasSessionHint() == true) {
                attemptedAutoConnect = true; confirmAccount()
            }
        }
        override fun failed(problem: SpotifySignInProblem) {
            confirmationJob?.cancel(); busy = false; loading = false
            show(problem)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openLogin(fresh = savedInstanceState == null)
        setContent {
            MusicTheme("system") {
                SpotifyLoginScreen(pageUrl, message, busy, loading,
                    onCancel = ::cancelLogin, onConnect = ::connectOrContinue,
                    onSignIn = { restartLogin() }, onReload = { restartLogin() }) {
                    browser?.let { view -> key(view) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                            (view.parent as? ViewGroup)?.removeView(view)
                            view
                        })
                    } }
                }
                BackHandler {
                    if (!busy && browser?.canGoBack() == true) browser?.goBack()
                    else cancelLogin()
                }
            }
        }
    }
    private fun show(next: SpotifySignInProblem) { problem = next; message = next.page }
    private fun openLogin(fresh: Boolean) {
        try {
            val runtime = SpotifyWebRuntime.get(applicationContext); engine = runtime
            // A restored activity without a page means Android ended the sign-in process while
            // the listener was away (reading an emailed code, for example).
            val restarted = !fresh && !runtime.hasBrowser
            browser = runtime.showLogin(observer, fresh, this)
            if (restarted) message = RESTARTED
        } catch (_: Exception) {
            loading = false
            show(SpotifySignInProblem.NO_WEBVIEW)
        }
    }
    private fun restartLogin() {
        confirmationJob?.cancel(); busy = false; attemptedAutoConnect = false
        problem = null; message = INSTRUCTIONS; loading = true
        openLogin(fresh = true)
    }
    private fun connectOrContinue() {
        val runtime = engine ?: return
        if (!SpotifySignInNavigation.player(pageUrl)) {
            // Leaving Spotify's page before it has signed in would throw away what was typed.
            if (!runtime.hasSessionHint()) { message = FINISH_FIRST; return }
            runtime.openPlayer()
        }
        confirmAccount()
    }
    private fun confirmAccount() {
        val runtime = engine ?: return
        if (busy) return
        busy = true; message = "Checking your Spotify account…"
        confirmationJob = lifecycleScope.launch {
            try {
                withTimeout(30_000) { runtime.call("profile", JSONObject()) }
                ensureActive()
                setResult(RESULT_OK); finish()
            } catch (_: TimeoutCancellationException) {
                busy = false; attemptedAutoConnect = false
                show(SpotifySignInProblem.SLOW)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                show(if ((e as? SpotifyCatalogueException)?.code == "AUTH_REQUIRED") SpotifySignInProblem.NOT_SIGNED_IN
                    else SpotifySignInProblem.NOT_VERIFIED)
            }
        }
    }
    private fun cancelLogin() {
        confirmationJob?.cancel()
        setResult(RESULT_CANCELED, Intent().apply { problem?.let { putExtra(PROBLEM, it.summary) } })
        finish()
    }
    override fun onDestroy() {
        confirmationJob?.cancel()
        browser?.let { (it.parent as? ViewGroup)?.removeView(it) }
        engine?.hideLogin(observer); browser = null
        super.onDestroy()
    }
    companion object {
        /** Result extra: a short, fixed reason when sign-in ends without a connection. */
        const val PROBLEM = "labs.rtensor.music.spotify.PROBLEM"
        internal const val INSTRUCTIONS = "Sign in on Spotify's page below. When Spotify opens, your account connects automatically. If it doesn't, tap Connect."
        internal const val FINISH_FIRST = "Sign in on Spotify's page below first. Its own Continue and Log in buttons are in the page; tap Connect once Spotify has signed you in."
        internal const val RESTARTED = "Android closed the sign-in page while Music was in the background. Sign in again; if Spotify already signed you in, it connects automatically."
    }
}

@Composable
internal fun SpotifyLoginScreen(pageUrl: String, message: String, busy: Boolean, loading: Boolean,
    onCancel: () -> Unit, onConnect: () -> Unit, onSignIn: () -> Unit, onReload: () -> Unit,
    browser: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConnect, enabled = !busy && pageUrl.isNotBlank()) {
                    Text("Connect")
                }
            }
            Text(runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull() ?: "Spotify sign-in",
                fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(message, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = onSignIn, enabled = !busy) { Text("Sign in") }
                TextButton(onClick = onReload, enabled = !busy) { Text("Reload") }
            }
            if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) { browser() }
        }
    }
}
