package labs.rtensor.music.spotify

import android.annotation.SuppressLint
import android.content.Context
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
internal class SpotifyWebRuntime private constructor(private val context: Context) {
    private val vault = AccountSession(context, "spotify_metadata_session")
    private val waiting = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var channel: WebMessagePort? = null
    private var ready = CompletableDeferred<Unit>()
    private var browser: WebView? = null
    private val script by lazy { context.assets.open("spotify/catalogue.js").bufferedReader().use { it.readText() } }
    val canRequest get() = channel != null

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        browser?.let { return it }
        val view = WebView(context)
        browser = view
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = true
            // Request the catalogue's desktop layout using the installed Chromium version.
            val version = Regex("Chrome/([0-9.]+)").find(userAgentString)?.groupValues?.get(1) ?: "120.0.0.0"
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = request.isForMainFrame && !navigationAllowed(request.url)
                if (blocked) android.widget.Toast.makeText(context, "Use Spotify’s email sign-in to connect inside Music.", android.widget.Toast.LENGTH_LONG).show()
                return blocked
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (channel != null) invalidate("Spotify page changed. Retry the request.")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!playerOrigin(url)) return
                view.evaluateJavascript(script) {
                    if (!playerOrigin(view.url)) return@evaluateJavascript
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
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                invalidate("Spotify catalogue stopped. Reconnect Spotify.")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); browser = null
                return true
            }
        }
        val cookie = runCatching { vault.load()?.let(::JSONObject)?.optString("cookie") }.getOrNull().orEmpty()
        cookie.split(';').map(String::trim).filter { it.startsWith("sp_dc=") || it.startsWith("sp_key=") }.forEach {
            CookieManager.getInstance().setCookie(PLAYER, "$it; Domain=.spotify.com; Path=/; Secure; HttpOnly")
        }
        view.loadUrl(PLAYER)
        return view
    }
    fun showLogin(): WebView = view().apply {
        settings.blockNetworkImage = false
        if (!playerOrigin(url) && url?.startsWith("https://accounts.spotify.com/") != true) loadUrl(PLAYER)
    }
    suspend fun call(operation: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        require(operation in setOf("profile", "home", "collection", "search", "radio"))
        view()
        withTimeout(35_000) {
            ready.await()
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
    private fun invalidate(message: String) {
        channel?.close(); channel = null
        waiting.values.forEach { it.completeExceptionally(IOException(message)) }; waiting.clear()
        if (!ready.isCompleted) ready.completeExceptionally(IOException(message))
        ready = CompletableDeferred()
    }
    companion object {
        private const val PLAYER = "https://open.spotify.com/"
        fun playerOrigin(value: String?) = runCatching { Uri.parse(value).let { it.scheme == "https" && it.host == "open.spotify.com" && it.port in listOf(-1, 443) && it.userInfo == null } }.getOrDefault(false)
        fun navigationAllowed(uri: Uri) = uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null &&
            (uri.host == "spotify.com" || uri.host?.endsWith(".spotify.com") == true)
        private var instance: SpotifyWebRuntime? = null
        fun get(context: Context) = instance ?: SpotifyWebRuntime(context.applicationContext).also { instance = it }
    }
}

internal class SpotifyCatalogueException(val code: String) : IOException(when (code) {
    "AUTH_REQUIRED" -> "Sign in to Spotify to load its recommendations."
    "PAGE_CHANGED" -> "Spotify changed its catalogue page. Reconnect Spotify or choose YouTube Music."
    "NO_RADIO" -> "Spotify has no radio for this recording."
    else -> "Spotify recommendations could not load. Retry or choose YouTube Music."
})
