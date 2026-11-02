package labs.rtensor.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bundled, offline JS client. Remote pages never receive the Android bridge. */
class YouTubeRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val session = AccountSession(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableAccount = MutableStateFlow(AccountState())
    val account = mutableAccount.asStateFlow()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val httpCalls = ConcurrentHashMap<String, Call>()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(24, TimeUnit.SECONDS).build()
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    @Volatile private var generation = 0L
    @Volatile private var browserUserAgent = "RTensorLabs-Music/0.2"

    suspend fun call(method: String, args: JSONObject = JSONObject()): JSONObject {
        withContext(Dispatchers.Main.immediate) { ensureView() }
        try { withTimeout(15_000) { ready.await() } }
        catch (_: TimeoutCancellationException) { throw SourceException("ENGINE_START", "The YouTube engine could not start. Update Android System WebView and reopen Music.") }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>(); pending[id] = result
        try {
            withContext(Dispatchers.Main.immediate) { webView?.evaluateJavascript("musicCall(${JSONObject.quote(id)},${JSONObject.quote(method)},$args)", null) }
            val data = withTimeout(90_000) { result.await() }
            data.optJSONObject("error")?.let {
                if (it.optString("code") == "AUTH_REQUIRED") {
                    session.clear(); reset()
                    mutableAccount.value = AccountState(error = it.optString("message"))
                }
                throw SourceException(it.optString("code"), it.optString("message"))
            }
            return data
        } catch (_: TimeoutCancellationException) { throw SourceException("TIMEOUT", "YouTube took too long to respond. Check your connection and retry.") }
        finally { pending.remove(id) }
    }
    suspend fun connect() {
        mutableAccount.value = AccountState(connecting = true)
        try { call("sign-in") } catch (e: Exception) { mutableAccount.value = AccountState(error = (e as? SourceException)?.message ?: "Could not start YouTube sign-in.") }
    }
    suspend fun disconnect() {
        try { withTimeout(8_000) { call("sign-out") } } catch (_: Exception) { /* Local logout always succeeds. */ }
        finally { session.clear(); mutableAccount.value = AccountState(); reset() }
    }
    suspend fun cancelSignIn() { session.clear(); mutableAccount.value = AccountState(); reset() }
    private suspend fun reset() = withContext(Dispatchers.Main.immediate) {
        generation++
        httpCalls.values.forEach(Call::cancel); httpCalls.clear()
        pending.values.forEach { it.completeExceptionally(SourceException("CANCELLED", "The account connection changed. Retry your request.")) }; pending.clear()
        webView?.removeJavascriptInterface("MusicNative"); webView?.destroy(); webView = null
        ready = CompletableDeferred()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView() {
        if (webView != null) return
        try {
            webView = WebView(app).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                browserUserAgent = settings.userAgentString
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(Bridge(generation), "MusicNative")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                        val name = when (request.url.toString()) { BASE + "index.html" -> "index.html"; BASE + "engine.js" -> "engine.js"; else -> null }
                        return if (name == null) WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        else WebResourceResponse(if (name.endsWith(".js")) "application/javascript" else "text/html", "UTF-8", app.assets.open("engine/$name"))
                    }
                    override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) { handler.cancel() }
                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        generation++
                        httpCalls.values.forEach(Call::cancel); httpCalls.clear()
                        pending.values.forEach { it.completeExceptionally(SourceException("ENGINE_STOPPED", "The YouTube engine stopped. Reopen Music and retry.")) }
                        webView?.destroy(); webView = null; ready = CompletableDeferred()
                        return true
                    }
                }
                loadUrl(BASE + "index.html")
            }
        } catch (_: Exception) { throw SourceException("WEBVIEW_MISSING", "Install or update Android System WebView to connect to YouTube.") }
    }
    private inner class Bridge(private val epoch: Long) {
        @JavascriptInterface fun locale(): String = JSONObject().put("language", java.util.Locale.getDefault().language)
            .put("country", java.util.Locale.getDefault().country).put("userAgent", browserUserAgent).put("androidVersion", android.os.Build.VERSION.RELEASE).toString()
        @JavascriptInterface fun loadCredentials(): String = if (epoch == generation) session.load().orEmpty() else ""
        @JavascriptInterface fun saveCredentials(value: String) { if (epoch == generation) session.save(value) }
        @JavascriptInterface fun clearCredentials() { if (epoch == generation) session.clear() }
        @JavascriptInterface fun result(id: String, json: String) {
            if (epoch != generation || json.length > MAX_RESPONSE) return
            runCatching { JSONObject(json) }.onSuccess { pending[id]?.complete(it) }
        }
        @JavascriptInterface fun event(type: String, json: String) {
            if (epoch != generation) return
            val data = runCatching { JSONObject(json) }.getOrNull() ?: return
            when (type) {
                "ready" -> ready.complete(Unit)
                "connected" -> mutableAccount.value = AccountState(connected = true)
                "signed-out" -> mutableAccount.value = AccountState()
                "auth-error" -> mutableAccount.value = AccountState(error = data.optString("message", "Reconnect your YouTube account."))
                "device-code" -> {
                    val code = data.optString("code"); val url = data.optString("url")
                    mutableAccount.value = if (code.matches(Regex("[A-Za-z0-9-]{4,30}")) && isVerificationUrl(url)) AccountState(connecting = true, code = code, verificationUrl = url)
                    else AccountState(error = "Google returned an unsupported sign-in address.")
                }
            }
        }
        @JavascriptInterface fun cancelHttp(id: String) { if (epoch == generation) httpCalls.remove(id)?.cancel() }
        @JavascriptInterface fun http(id: String, url: String, options: String) {
            if (epoch != generation) return
            scope.launch(Dispatchers.IO) {
                val response = try {
                    require(isProviderUrl(url)); require(options.length <= 1_048_576)
                    val o = JSONObject(options); val method = o.optString("method", "GET").uppercase()
                    require(method in setOf("GET", "POST", "HEAD"))
                    val headers = o.optJSONObject("headers") ?: JSONObject()
                    val body = if (method == "POST") o.optString("body").toRequestBody(headers.optString("content-type", "application/json").toMediaTypeOrNull()) else null
                    val builder = Request.Builder().url(url).method(method, body).header("User-Agent", browserUserAgent)
                    headers.keys().forEach { key -> if (key.lowercase() !in setOf("host", "connection", "content-length", "cookie")) builder.header(key, headers.getString(key)) }
                    val request = http.newCall(builder.build()); httpCalls[id] = request
                    request.execute().use { r ->
                        val bytes = ByteArrayOutputStream()
                        r.body?.byteStream()?.use { input -> val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= MAX_RESPONSE); bytes.write(buffer, 0, n) }
                        }
                        JSONObject().put("status", r.code).put("headers", JSONObject().put("content-type", r.header("content-type", "application/json")))
                            .put("body", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    }
                } catch (_: Exception) { JSONObject().put("error", "NETWORK") } finally { httpCalls.remove(id) }
                withContext(Dispatchers.Main.immediate) { if (epoch == generation) webView?.evaluateJavascript("musicHttpResult(${JSONObject.quote(id)},$response)", null) }
            }
        }
    }
    companion object {
        private const val BASE = "https://appassets.androidplatform.net/assets/engine/"
        private const val MAX_RESPONSE = 8 * 1024 * 1024
        @Volatile private var instance: YouTubeRuntime? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: YouTubeRuntime(context).also { instance = it } }
        fun isProviderUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.host in setOf("www.youtube.com", "youtube.com", "music.youtube.com", "youtubei.googleapis.com", "www.googleapis.com", "oauth2.googleapis.com")
        }.getOrDefault(false)
        fun isVerificationUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.host in setOf("www.google.com", "google.com", "accounts.google.com", "www.youtube.com", "youtube.com")
        }.getOrDefault(false)
    }
}
