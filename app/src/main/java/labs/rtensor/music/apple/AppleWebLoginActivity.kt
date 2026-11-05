package labs.rtensor.music.apple

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import labs.rtensor.music.auth.SignInWebData
import labs.rtensor.music.auth.WebSignInProblem
import labs.rtensor.music.ui.SavedMusicTheme
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Isolated first-party sign-in. No JavaScript bridge, exported callback or external token service. */
internal class AppleWebLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private val popups = mutableListOf<WebView>()
    @Volatile private var observedDeveloper: String? = null
    private var message by mutableStateOf("Sign in on Apple’s page. Your subscription authorizes AAC stereo playback.")
    private var busy by mutableStateOf(false)
    private var confirmation: Job? = null
    private var polling: Job? = null
    /** The last problem shown here. Leaving without connecting reports it in Settings. */
    private var problem: WebSignInProblem? = null
    internal val page: WebView? get() = browser
    private fun show(next: WebSignInProblem) { problem = next; message = next.page }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            SavedMusicTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = ::cancel) { Text("Cancel") }
                    TextButton(onClick = { confirm(true) }, enabled = !busy) { Text("Connect") }
                }
                Text(message, modifier = Modifier.padding(16.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { WebView(it).apply {
                    browser = this; configure(this)
                    CookieManager.getInstance().removeAllCookies { loadUrl("https://music.apple.com/us/browse") }
                } })
            } } }
            BackHandler { if (popups.isNotEmpty()) closePopup(popups.last()) else if (browser?.canGoBack() == true) browser?.goBack() else cancel() }
        }
        polling = lifecycleScope.launch { while (isActive) { delay(1500); confirm(false) } }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            // Apple's web player is otherwise replaced by an app-install interstitial.
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return request.url.scheme != "https"
                if (AppleOrigins.canNavigate(request.url.toString())) return false
                // A blocked page used to do nothing at all; say where it tried to go (host only).
                show(WebSignInProblem.blocked(WebSignInProblem.hostOf(request.url)))
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (AppleOrigins.isApi(request.url.toString())) {
                    val token = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value?.removePrefix("Bearer ")
                    if (token != null && AppleOrigins.jwtIsCurrent(token)) observedDeveloper = token
                }
                return null
            }
            override fun onPageFinished(view: WebView, url: String) { if (view === browser && AppleOrigins.canConfirm(url)) confirm(false) }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) {
                handler.cancel(); show(WebSignInProblem.insecure(SITE))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && view === browser) show(WebSignInProblem.pageFailed(SITE))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                // Unhandled, a crashed renderer ends this process and returns a bare cancel.
                if (popups.contains(view)) { closePopup(view); return true }
                if (view === browser) browser = null
                (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy()
                show(WebSignInProblem.stopped(SITE)); cancel()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(parent: WebView, dialog: Boolean, userGesture: Boolean, result: Message): Boolean {
                if (!userGesture || popups.size >= 2) return false
                val popup = WebView(this@AppleWebLoginActivity); configure(popup); popups += popup
                val holder = android.app.Dialog(this@AppleWebLoginActivity).apply {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setContentView(popup); setOnDismissListener { closePopup(popup) }; show()
                    window?.setLayout(-1, -1)
                }
                popup.tag = holder
                (result.obj as WebView.WebViewTransport).webView = popup; result.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView) { closePopup(window) }
        }
    }
    private fun closePopup(view: WebView) {
        if (!popups.remove(view)) return
        (view.tag as? android.app.Dialog)?.dismiss(); view.stopLoading(); view.destroy()
    }
    private fun confirm(manual: Boolean) {
        val view = browser ?: return
        if (busy || confirmation?.isActive == true) return
        if (!AppleOrigins.canConfirm(view.url.orEmpty())) {
            if (manual) { view.loadUrl("https://music.apple.com/us/browse"); message = "Finish Apple sign-in, then tap Connect." }; return
        }
        val user = AppleOrigins.cookieToken(CookieManager.getInstance().getCookie("https://music.apple.com").orEmpty())
        confirmation = lifecycleScope.launch {
            try {
                val captured = withTimeout(5000) { suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript("""(function(){try{var m=window.MusicKit&&MusicKit.getInstance();return JSON.stringify({developerToken:m&&(m.developerToken||(m.configuration&&m.configuration.developerToken)),musicUserToken:m&&m.musicUserToken});}catch(e){return '{}';}})()""") {
                        if (continuation.isActive) continuation.resume(it)
                    }
                } }
                if (!AppleOrigins.canConfirm(view.url.orEmpty())) return@launch
                val text = JSONTokener(captured).nextValue() as? String ?: "{}"
                val json = JSONObject(text)
                val developer = json.optString("developerToken").takeIf { AppleOrigins.jwtIsCurrent(it) } ?: observedDeveloper
                val token = user ?: json.optString("musicUserToken").takeIf { it.length in 8..16384 && it != "undefined" }
                if (developer == null || token == null) { if (manual) message = "Finish signing in on Apple’s page, then tap Connect."; return@launch }
                busy = true; message = "Verifying your Apple Music account…"
                val credentials = AppleCredentials.parse(JSONObject().put("developerToken", developer).put("musicUserToken", token))
                val region = AppleHttp().storefront(credentials)
                setResult(RESULT_OK, Intent().putExtra(SESSION, AppleCredentials(developer, token, storefront = region).json().toString())
                    .putExtra(TICKET, intent.getLongExtra(TICKET, -1)))
                finish()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (manual || busy) message = "Apple could not verify this account. Check the subscription and try again."; busy = false }
        }
    }
    private fun cancel() {
        confirmation?.cancel()
        setResult(RESULT_CANCELED, Intent().putExtra(TICKET, intent.getLongExtra(TICKET, -1)).apply { problem?.let { putExtra(PROBLEM, it.summary) } })
        finish()
    }
    override fun onDestroy() {
        polling?.cancel(); confirmation?.cancel(); popups.toList().forEach(::closePopup)
        browser?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }; browser = null
        observedDeveloper = null
        // Written to disk before this process can be ended, so Apple's token cookie does not outlive sign-in.
        SignInWebData.erase()
        super.onDestroy()
    }
    companion object {
        const val SESSION = "apple.session"; const val TICKET = "apple.ticket"
        /** Result extra: a short, fixed reason when sign-in ends without a connection. */
        const val PROBLEM = "apple.problem"
        private const val SITE = "Apple"
    }
}
