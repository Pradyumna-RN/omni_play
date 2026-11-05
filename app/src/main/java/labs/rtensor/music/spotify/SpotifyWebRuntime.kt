package labs.rtensor.music.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import labs.rtensor.music.data.AccountSession
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Lives exclusively in :spotify_metadata. No audio, remote navigation bridge or token API. */
internal interface SpotifyLoginObserver {
    fun pageChanged(url: String, finished: Boolean)
    fun failed(problem: SpotifySignInProblem)
}

internal class SpotifyWebRuntime private constructor(private val context: Context) {
    private val vault = AccountSession(context, "spotify_metadata_session")
    /** The page borrows the sign-in activity while it is on screen, so Spotify's own pickers and
     * dialogs (the phone-number country list) have a window. Hidden, it holds only the app. */
    private val host = MutableContextWrapper(context)
    /** After Android ended this process mid sign-in, reopen Spotify itself if it kept the session. */
    private var resumeLogin = false
    private val waiting = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var channel: WebMessagePort? = null
    private var ready = CompletableDeferred<Unit>()
    private var browser: WebView? = null
    private var loginObserver: SpotifyLoginObserver? = null
    private var document = 0L
    private class PageChanged : IOException("Spotify page changed")
    private val script by lazy { context.assets.open("spotify/catalogue.js").bufferedReader().use { it.readText() } }
    val canRequest get() = channel != null
    val hasBrowser get() = browser != null

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        browser?.let { it.onResume(); return it }
        val view = WebView(host)
        browser = view
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Request the catalogue's desktop layout using the installed Chromium version.
            val version = Regex("Chrome/([0-9.]+)").find(userAgentString)?.groupValues?.get(1) ?: "120.0.0.0"
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = if (request.isForMainFrame) !navigationAllowed(request.url, loginObserver != null) else request.url.scheme != "https"
                if (blocked && request.isForMainFrame) loginObserver?.failed(SpotifySignInProblem.blocked(SpotifySignInNavigation.hostOf(request.url)))
                return blocked
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                document++
                invalidatePage()
                loginObserver?.pageChanged(url.orEmpty(), false)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!playerOrigin(url)) {
                    loginObserver?.pageChanged(url.orEmpty(), true)
                    if (SpotifySignInNavigation.refusedByGoogle(url)) loginObserver?.failed(SpotifySignInProblem.GOOGLE_REFUSED)
                    return
                }
                val expected = document
                view.evaluateJavascript(script) {
                    if (expected != document || !playerOrigin(view.url)) return@evaluateJavascript
                    channel?.close()
                    val ports = view.createWebMessageChannel()
                    channel = ports[0]
                    ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort?, message: WebMessage?) {
                            if (port !== channel) return
                            val raw = message?.data ?: return
                            if (raw.length > 524288) { invalidate("Spotify returned too much catalogue data."); return }
                            val result = runCatching { JSONObject(raw) }.getOrNull() ?: return
                            val promise = waiting.remove(result.optString("id")) ?: return
                            if (result.optBoolean("ok")) promise.complete(result.optJSONObject("result") ?: JSONObject())
                            else promise.completeExceptionally(SpotifyCatalogueException(result.optString("error")))
                        }
                    })
                    view.postWebMessage(WebMessage("rtensor-spotify-catalogue", arrayOf(ports[1])), Uri.parse(PLAYER))
                    ready.complete(Unit)
                    loginObserver?.pageChanged(url.orEmpty(), true)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loginObserver?.failed(SpotifySignInProblem.PAGE_FAILED)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (!request.isForMainFrame) return
                val url = request.url.toString()
                val google = SpotifySignInNavigation.refusedByGoogle(url) ||
                    (errorResponse.statusCode == 403 && SpotifySignInNavigation.googleAccounts(url))
                loginObserver?.failed(if (google) SpotifySignInProblem.GOOGLE_REFUSED else SpotifySignInProblem.PAGE_ERROR)
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) {
                handler.cancel()
                loginObserver?.failed(SpotifySignInProblem.INSECURE)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                invalidate("Spotify catalogue stopped. Reconnect Spotify.")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); browser = null
                loginObserver?.failed(SpotifySignInProblem.PAGE_STOPPED)
                return true
            }
        }
        val cookie = runCatching { vault.load()?.let(::JSONObject)?.optString("cookie") }.getOrNull().orEmpty()
        cookie.split(';').map(String::trim).filter { it.startsWith("sp_dc=") || it.startsWith("sp_key=") }.forEach {
            CookieManager.getInstance().setCookie(PLAYER, "$it; Domain=.spotify.com; Path=/; Secure; HttpOnly")
        }
        val firstPage = when {
            loginObserver == null -> PLAYER
            resumeLogin && hasSessionHint() -> PLAYER
            else -> SpotifySignInNavigation.loginUrl()
        }
        resumeLogin = false
        loginObserver?.pageChanged(firstPage, false)
        view.loadUrl(firstPage)
        return view
    }
    fun showLogin(observer: SpotifyLoginObserver, fresh: Boolean, window: Context? = null): WebView {
        val existing = browser
        loginObserver = observer
        window?.let { host.baseContext = it }
        resumeLogin = !fresh && existing == null
        return view().apply {
            settings.blockNetworkImage = false
            if (fresh && existing != null) openSignIn()
            else observer.pageChanged(url ?: SpotifySignInNavigation.loginUrl(), channel != null)
        }
    }
    fun hideLogin(observer: SpotifyLoginObserver) {
        if (loginObserver !== observer) return
        loginObserver = null
        host.baseContext = context
        browser?.let { it.settings.blockNetworkImage = true; it.onPause() }
    }
    fun hasSessionHint(): Boolean = CookieManager.getInstance().getCookie(PLAYER).orEmpty().split(';')
        .any { it.trim().startsWith("sp_dc=") && it.substringAfter('=').isNotBlank() }
    fun openPlayer() = navigate(PLAYER)
    fun openSignIn() = navigate(SpotifySignInNavigation.loginUrl())
    private fun navigate(url: String) {
        document++
        invalidatePage()
        loginObserver?.pageChanged(url, false)
        view().loadUrl(url)
    }
    fun closeIfIdle() { if (loginObserver == null) close() }
    suspend fun call(operation: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        require(operation in setOf("profile", "home", "collection", "search", "radio"))
        view()
        withTimeout(35_000) {
            while (channel == null) {
                val pending = ready
                try { pending.await() }
                catch (_: PageChanged) { currentCoroutineContext().ensureActive() }
            }
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<JSONObject>()
            waiting[id] = result
            try {
                channel?.postMessage(WebMessage(JSONObject().put("id", id).put("operation", operation).put("args", args).toString()))
                    ?: throw IOException("Spotify catalogue is still starting.")
                result.await().also { if (operation == "profile") checkpoint() }
            } finally { waiting.remove(id) }
        }
    }
    private fun checkpoint() {
        val cookie = CookieManager.getInstance().getCookie(PLAYER).orEmpty().split(';').map(String::trim)
            .filter { it.startsWith("sp_dc=") || it.startsWith("sp_key=") }.joinToString("; ")
        if (cookie.contains("sp_dc=")) vault.save(JSONObject().put("cookie", cookie).toString())
        CookieManager.getInstance().flush()
    }
    fun disconnect() {
        vault.clear()
        close()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }
    fun close() {
        invalidate("Spotify catalogue closed.")
        browser?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        browser = null
    }
    private fun invalidatePage() {
        channel?.close(); channel = null
        val previous = ready
        ready = CompletableDeferred()
        val pending = waiting.values.toList(); waiting.clear()
        // Main.immediate may resume a waiter inside completeExceptionally. Publish
        // the replacement first so that navigation cannot spin on the old promise.
        if (!previous.isCompleted) previous.completeExceptionally(PageChanged())
        pending.forEach { it.completeExceptionally(PageChanged()) }
    }
    private fun invalidate(message: String) {
        document++
        channel?.close(); channel = null
        val previous = ready
        ready = CompletableDeferred()
        val pending = waiting.values.toList(); waiting.clear()
        if (!previous.isCompleted) previous.completeExceptionally(IOException(message))
        pending.forEach { it.completeExceptionally(IOException(message)) }
    }
    companion object {
        private const val PLAYER = SpotifySignInNavigation.PLAYER
        fun playerOrigin(value: String?) = SpotifySignInNavigation.player(value)
        fun navigationAllowed(uri: Uri, identityProviders: Boolean = false) = SpotifySignInNavigation.allowed(uri, identityProviders)
        private var instance: SpotifyWebRuntime? = null
        fun get(context: Context) = instance ?: SpotifyWebRuntime(context.applicationContext).also { instance = it }
    }
}

internal class SpotifyCatalogueException(val code: String) : IOException(when (code) {
    "AUTH_REQUIRED" -> "Sign in to Spotify to load its recommendations."
    "PAGE_CHANGED" -> "Spotify changed its catalogue page. Reconnect Spotify or choose YouTube Music."
    "TIMEOUT" -> "Spotify took too long to respond. Wait for the page, then retry Connect."
    "NO_RADIO" -> "Spotify has no radio for this recording."
    else -> "Spotify recommendations could not load. Retry or choose YouTube Music."
})
