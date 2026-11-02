package labs.rtensor.music

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import labs.rtensor.music.data.WebSession
import labs.rtensor.music.data.YouTubeRuntime
import labs.rtensor.music.ui.MusicTheme
import org.json.JSONTokener
import org.json.JSONObject
import kotlin.coroutines.resume

/** First-party Google/YouTube pages; no Android JavaScript bridge is attached. */
class YouTubeLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var pageUrl by mutableStateOf("")
    private var message by mutableStateOf("Sign in to YouTube. When YouTube Music opens, your account connects automatically.")
    private var busy by mutableStateOf(false)
    private var attemptedAutoConnect = false
    private var confirmationJob: Job? = null

    private fun cancelLogin() { confirmationJob?.cancel(); setResult(RESULT_CANCELED); finish() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicTheme("system") {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = ::cancelLogin) { Text("Cancel") }
                            TextButton(onClick = ::confirmAccount, enabled = !busy && WebSession.canConfirm(pageUrl)) { Text("Connect") }
                        }
                        Text(if (pageUrl.isBlank()) "YouTube sign-in" else (runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: "YouTube sign-in"), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(message, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            WebView(context).apply {
                                browser = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false; settings.allowContentAccess = false
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !WebSession.isSignInNavigation(request.url.toString())
                                    override fun onPageFinished(view: WebView, url: String) {
                                        pageUrl = url
                                        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                        if (!attemptedAutoConnect && !busy && WebSession.canConfirm(url) && cookie.split(';').any { it.trim().startsWith("SAPISID=") }) {
                                            attemptedAutoConnect = true; confirmAccount()
                                        }
                                    }
                                    override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) {
                                        handler.cancel(); message = "The sign-in page failed its security check. Check the device clock and network."
                                    }
                                }
                                // A fresh, app-owned login. No external browser cookies are read.
                                CookieManager.getInstance().removeAllCookies {
                                    loadUrl(signInUrl(intent.getStringExtra(LOGIN_HINT)))
                                }
                            }
                        })
                    }
                }
                BackHandler {
                    if (!busy && browser?.canGoBack() == true) browser?.goBack()
                    else cancelLogin()
                }
            }
        }
    }

    private fun confirmAccount() {
        val view = browser ?: return
        if (!WebSession.canConfirm(view.url.orEmpty())) return
        busy = true; message = "Checking your YouTube account…"
        confirmationJob = lifecycleScope.launch {
            try {
                val raw = withTimeout(8000) { suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript("""JSON.stringify((()=>{const c=window.ytcfg?.data_||window.yt?.config_||{};const p=c.WEB_PLAYER_CONTEXT_CONFIGS?.WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_PLAYER||{};const flags=new URLSearchParams(p.serializedExperimentFlags||'');const f=flags.has('html5_generate_content_po_token')?flags.getAll('html5_generate_content_po_token').at(-1):c.EXPERIMENT_FLAGS?.html5_generate_content_po_token;let script='';try{const source=c.PLAYER_JS_URL||p.jsUrl||'';if(source){const u=new URL(source,'https://www.youtube.com');if(u.protocol==='https:'&&u.hostname==='www.youtube.com'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&u.pathname.startsWith('/s/player/'))script=u.href}}catch{};return {visitorData:c.VISITOR_DATA||c.INNERTUBE_CONTEXT?.client?.visitorData||'',dataSyncId:c.DATASYNC_ID||'',delegatedSessionId:c.DELEGATED_SESSION_ID||'',authUser:Number(c.SESSION_INDEX||0),clientVersion:c.INNERTUBE_CLIENT_VERSION||c.INNERTUBE_CONTEXT?.client?.clientVersion||'',playerScript:script,musicContentBoundGvs:f===true||f==='true'?true:f===false||f==='false'?false:null}})())""") { value -> if (continuation.isActive) continuation.resume(value) }
                } }
                check(WebSession.canConfirm(view.url.orEmpty())) { "Return to YouTube Music and retry." }
                val config = JSONObject(JSONTokener(raw).nextValue() as String)
                val candidate = WebSession.parse(config.put("kind", "youtube-web")
                    .put("cookie", CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty())
                    .put("userAgent", view.settings.userAgentString))
                YouTubeRuntime.get(applicationContext).acceptWebSession(candidate)
                clearBrowserCookies()
                setResult(RESULT_OK); finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = (e as? labs.rtensor.music.data.SourceException)?.message ?: "Could not confirm your YouTube session. Finish sign-in and retry."
                busy = false
            }
        }
    }
    private fun clearBrowserCookies() {
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
    }
    override fun onDestroy() {
        browser?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }; browser = null
        if (isFinishing) clearBrowserCookies()
        super.onDestroy()
    }
    companion object {
        const val LOGIN_HINT = "google_account_hint"
        fun signInUrl(email: String?): String = android.net.Uri.parse("https://accounts.google.com/ServiceLogin").buildUpon()
            .appendQueryParameter("continue", "https://music.youtube.com")
            .apply { email?.takeIf { it.length <= 320 && it.contains('@') && !it.contains('\r') && !it.contains('\n') }?.let { appendQueryParameter("login_hint", it) } }
            .build().toString()
    }

}
