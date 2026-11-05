package labs.rtensor.music.spotify

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

/** Same Continue/Connect flow as YouTube; only a verified Spotify profile completes it. */
class SpotifyLoginActivity : ComponentActivity() {
    private var engine: SpotifyWebRuntime? = null
    private var browser by mutableStateOf<WebView?>(null)
    private var pageUrl by mutableStateOf("")
    private var message by mutableStateOf(INSTRUCTIONS)
    private var busy by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var attemptedAutoConnect = false
    private var confirmationJob: Job? = null
    private val observer = object : SpotifyLoginObserver {
        override fun pageChanged(url: String, finished: Boolean) {
            pageUrl = url; loading = !finished
            if (!finished) attemptedAutoConnect = false
            if (finished && !busy && !attemptedAutoConnect && SpotifySignInNavigation.player(url) && engine?.canRequest == true && engine?.hasSessionHint() == true) {
                attemptedAutoConnect = true; confirmAccount()
            }
        }
        override fun failed(message: String) {
            confirmationJob?.cancel(); busy = false; loading = false
            this@SpotifyLoginActivity.message = message
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
    private fun openLogin(fresh: Boolean) {
        try {
            val runtime = SpotifyWebRuntime.get(applicationContext); engine = runtime
            browser = runtime.showLogin(observer, fresh)
        } catch (_: Exception) {
            loading = false
            message = "Spotify sign-in needs Android System WebView. Update it, then tap Reload."
        }
    }
    private fun restartLogin() {
        confirmationJob?.cancel(); busy = false; attemptedAutoConnect = false
        message = INSTRUCTIONS; loading = true
        openLogin(fresh = true)
    }
    private fun connectOrContinue() {
        val runtime = engine ?: return
        if (!SpotifySignInNavigation.player(pageUrl)) runtime.openPlayer()
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
                message = "Spotify is still opening your account. Wait for the page, then tap Connect."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                message = if ((e as? SpotifyCatalogueException)?.code == "AUTH_REQUIRED")
                    "Finish signing in to Spotify, then tap Connect. Tap Sign in to reopen the login page."
                    else "Could not verify the Spotify account yet. Wait for Spotify to open, then tap Connect or Reload."
            }
        }
    }
    private fun cancelLogin() {
        confirmationJob?.cancel(); setResult(RESULT_CANCELED); finish()
    }
    override fun onDestroy() {
        confirmationJob?.cancel()
        browser?.let { (it.parent as? ViewGroup)?.removeView(it) }
        engine?.hideLogin(observer); browser = null
        super.onDestroy()
    }
    private companion object {
        const val INSTRUCTIONS = "Sign in to Spotify. When Spotify opens, your account connects automatically. You can also tap Continue, then Connect."
    }
}

@Composable
internal fun SpotifyLoginScreen(pageUrl: String, message: String, busy: Boolean, loading: Boolean,
    onCancel: () -> Unit, onConnect: () -> Unit, onSignIn: () -> Unit, onReload: () -> Unit,
    browser: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConnect, enabled = !busy && pageUrl.isNotBlank()) {
                    Text(if (SpotifySignInNavigation.player(pageUrl)) "Connect" else "Continue")
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
