package labs.rtensor.music

import android.annotation.SuppressLint
import android.content.Intent
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import labs.rtensor.music.auth.DeviceYouTubeSignIn
import labs.rtensor.music.auth.GoogleSignInPages
import labs.rtensor.music.auth.SignInWebData
import labs.rtensor.music.auth.WebSignInProblem
import labs.rtensor.music.data.WebSession
import labs.rtensor.music.data.YouTubeRuntime
import labs.rtensor.music.ui.SavedMusicTheme
import org.json.JSONTokener
import org.json.JSONObject
import kotlin.coroutines.resume

/** First-party Google/YouTube pages; no Android JavaScript bridge is attached. */
class YouTubeLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var pageUrl by mutableStateOf("")
    private var message by mutableStateOf(INSTRUCTIONS)
    private var busy by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var attemptedAutoConnect = false
    private var confirmationJob: Job? = null
    /** The last problem shown here. Leaving without connecting reports it in Settings. */
    private var problem: WebSignInProblem? = null
    internal val page: WebView? get() = browser

    private fun show(next: WebSignInProblem) { problem = next; message = next.page }
    private fun cancelLogin() {
        confirmationJob?.cancel()
        setResult(RESULT_CANCELED, Intent().apply { problem?.let { putExtra(PROBLEM, it.summary) } })
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Recreated (Android ended Music while the listener was in another app, or a setting such
        // as the system language changed): the page is gone, but cookies Google already set are not.
        val restored = savedInstanceState != null
        if (restored) message = RESTARTED
        setContent {
            SavedMusicTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = ::cancelLogin) { Text("Cancel") }
                            Row {
                                TextButton(onClick = ::reload, enabled = !busy) { Text("Reload") }
                                TextButton(onClick = ::connectOrContinue, enabled = !busy && pageUrl.isNotBlank()) { Text("Connect") }
                            }
                        }
                        Text(if (pageUrl.isBlank()) "YouTube sign-in" else (runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: "YouTube sign-in"), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(message, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
                        if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context -> createBrowser(context, restored) })
                    }
                }
                BackHandler {
                    if (!busy && browser?.canGoBack() == true) browser?.goBack()
                    else cancelLogin()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(context: android.content.Context, restored: Boolean) = WebView(context).apply {
        browser = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false); settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Google's own pages may use frames (passkeys, 2SV prompts); only top-level navigation is confined.
                if (!request.isForMainFrame) return request.url.scheme != "https"
                if (WebSession.isSignInNavigation(request.url.toString())) return false
                show(WebSignInProblem.blocked(WebSignInProblem.hostOf(request.url)))
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view === browser) { pageUrl = url.orEmpty(); loading = true }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view !== browser) return
                pageUrl = url; loading = false
                if (GoogleSignInPages.refused(url)) { show(WebSignInProblem.GOOGLE_REFUSED); return }
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                if (!attemptedAutoConnect && !busy && WebSession.canConfirm(url) && cookie.split(';').any { it.trim().startsWith("SAPISID=") }) {
                    attemptedAutoConnect = true; confirmAccount()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && view === browser) { loading = false; show(WebSignInProblem.pageFailed(SITE)) }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (!request.isForMainFrame || view !== browser) return
                loading = false
                show(if (GoogleSignInPages.refused(request.url.toString())) WebSignInProblem.GOOGLE_REFUSED else WebSignInProblem.pageFailed(SITE))
            }
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) {
                handler.cancel(); loading = false; show(WebSignInProblem.insecure(SITE))
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // Unhandled, a crashed page renderer ends Music's whole process, and with it playback.
                if (view === browser) browser = null
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                show(WebSignInProblem.stopped(SITE)); cancelLogin()
                return true
            }
        }
        if (restored) {
            // Never reuse the one-time device ticket; it was spent by the first page.
            loadUrl(signInUrl(intent.getStringExtra(LOGIN_HINT)))
        } else {
            // A fresh, app-owned login. No external browser cookies are read.
            SignInWebData.erase {
                val ticket = intent.getStringExtra(DEVICE_LOGIN_URL)?.takeIf(DeviceYouTubeSignIn::isLoginUrl)
                intent.removeExtra(DEVICE_LOGIN_URL)
                if (browser === this) loadUrl(ticket ?: signInUrl(intent.getStringExtra(LOGIN_HINT)))
            }
        }
    }

    private fun reload() {
        val view = browser ?: return
        problem = null; message = INSTRUCTIONS; attemptedAutoConnect = false
        if (view.url.isNullOrBlank()) view.loadUrl(signInUrl(intent.getStringExtra(LOGIN_HINT))) else view.reload()
    }

    private fun connectOrContinue() {
        val view = browser ?: return
        if (WebSession.canConfirm(view.url.orEmpty())) return confirmAccount()
        // Leaving Google's form before it has signed in would throw away what was typed.
        if (!GoogleSignInPages.signedIn(CookieManager.getInstance()::getCookie)) { message = FINISH_FIRST; return }
        attemptedAutoConnect = false
        message = "Opening YouTube Music…"
        view.loadUrl("https://music.youtube.com/")
    }

    private fun confirmAccount() {
        val view = browser ?: return
        if (!WebSession.canConfirm(view.url.orEmpty())) return
        busy = true; message = "Checking your YouTube account…"
        confirmationJob = lifecycleScope.launch {
            try {
                val config = withTimeout(12_000) {
                    var captured = captureConfig(view)
                    while (captured.optString("visitorData").isBlank()) {
                        delay(250)
                        captured = captureConfig(view)
                    }
                    captured
                }
                check(WebSession.canConfirm(view.url.orEmpty())) { "Return to YouTube Music and retry." }
                val candidate = WebSession.parse(config.put("kind", "youtube-web")
                    .put("cookie", CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty())
                    .put("userAgent", view.settings.userAgentString))
                YouTubeRuntime.get(applicationContext).acceptWebSession(candidate)
                clearBrowserCookies()
                setResult(RESULT_OK); finish()
            } catch (_: TimeoutCancellationException) {
                busy = false; attemptedAutoConnect = false
                show(SLOW)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                show((e as? labs.rtensor.music.data.SourceException)?.message?.let { WebSignInProblem(it, it) } ?: NOT_VERIFIED)
                busy = false
            }
        }
    }
    private suspend fun captureConfig(view: WebView): JSONObject {
        val raw = withTimeout(8000) { suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript("""JSON.stringify((()=>{const c=window.ytcfg?.data_||window.yt?.config_||{};const p=c.WEB_PLAYER_CONTEXT_CONFIGS?.WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_PLAYER||{};const flags=new URLSearchParams(p.serializedExperimentFlags||'');const f=flags.has('html5_generate_content_po_token')?flags.getAll('html5_generate_content_po_token').at(-1):c.EXPERIMENT_FLAGS?.html5_generate_content_po_token;let script='';try{const source=c.PLAYER_JS_URL||p.jsUrl||'';if(source){const u=new URL(source,'https://www.youtube.com');if(u.protocol==='https:'&&u.hostname==='www.youtube.com'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&u.pathname.startsWith('/s/player/'))script=u.href}}catch{};return {visitorData:c.VISITOR_DATA||c.INNERTUBE_CONTEXT?.client?.visitorData||'',dataSyncId:c.DATASYNC_ID||'',delegatedSessionId:c.DELEGATED_SESSION_ID||'',authUser:Number(c.SESSION_INDEX||0),clientVersion:c.INNERTUBE_CLIENT_VERSION||c.INNERTUBE_CONTEXT?.client?.clientVersion||'',playerScript:script,musicContentBoundGvs:f===true||f==='true'?true:f===false||f==='false'?false:null}})())""") { value -> if (continuation.isActive) continuation.resume(value) }
        } }
        return JSONObject(JSONTokener(raw).nextValue() as String)
    }
    private fun clearBrowserCookies() = SignInWebData.erase()
    override fun onDestroy() {
        browser?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }; browser = null
        if (isFinishing) clearBrowserCookies()
        super.onDestroy()
    }
    companion object {
        /** Result extra: a short, fixed reason when sign-in ends without a connection. */
        const val PROBLEM = "labs.rtensor.music.youtube.PROBLEM"
        private const val SITE = "Google"
        internal const val INSTRUCTIONS = "Sign in to YouTube. When YouTube Music opens, your account connects automatically."
        internal const val FINISH_FIRST = "Finish signing in on Google's page first; its own Next buttons are in the page. Tap Connect once Google has signed you in."
        internal const val RESTARTED = "Music reopened the sign-in page. If Google already signed you in, it continues to YouTube Music; otherwise sign in again."
        private val SLOW = WebSignInProblem("YouTube is still opening your account. Wait for the page, then tap Connect.",
            "YouTube took too long to open your account")
        private val NOT_VERIFIED = WebSignInProblem("Could not confirm your YouTube session. Finish sign-in and tap Connect.",
            "Music couldn't confirm your YouTube session")
        const val DEVICE_LOGIN_URL = "device_youtube_login_url"
        const val LOGIN_HINT = "google_account_hint"
        fun signInUrl(email: String?): String = android.net.Uri.parse("https://accounts.google.com/ServiceLogin").buildUpon()
            .appendQueryParameter("continue", "https://music.youtube.com")
            .apply { email?.takeIf { it.length <= 320 && it.contains('@') && !it.contains('\r') && !it.contains('\n') }?.let { appendQueryParameter("login_hint", it) } }
            .build().toString()
    }

}
