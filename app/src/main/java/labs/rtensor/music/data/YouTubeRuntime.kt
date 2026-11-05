package labs.rtensor.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PlaybackProblem(val videoId: String, val message: String, val code: String, val diagnostic: String)

/** Bundled, offline JS client. Remote pages never receive the Android bridge. */
class YouTubeRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val session = AccountSession(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableAccount = MutableStateFlow(AccountState())
    val account = mutableAccount.asStateFlow()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val transport = ProviderTransport()
    private val proof = ProofRuntime(app)
    private val accountChange = Mutex()
    private val mutableProblem = MutableStateFlow<PlaybackProblem?>(null)
    val problem = mutableProblem.asStateFlow()
    // Buffered, not conflated: metadata for several recordings can arrive back to back.
    private val mutableMetadata = MutableSharedFlow<YouTubeMetadata?>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val metadata = mutableMetadata.asSharedFlow()
    @Volatile private var activeSession: WebSession? = null
    private var sessionLoaded = false
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    @Volatile private var generation = 0L
    @Volatile private var browserUserAgent = "RTensorLabs-Music/0.3"

    suspend fun call(method: String, args: JSONObject = JSONObject()): JSONObject {
        if (method == "resolve" && !args.optBoolean("background")) mutableProblem.value = null
        withContext(Dispatchers.Main.immediate) { ensureView() }
        try { withTimeout(15_000) { ready.await() } }
        catch (_: TimeoutCancellationException) { throw SourceException("ENGINE_START", "The YouTube engine could not start. Update Android System WebView and reopen Music.") }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>(); pending[id] = result
        try {
            withContext(Dispatchers.Main.immediate) { webView?.evaluateJavascript("musicCall(${JSONObject.quote(id)},${JSONObject.quote(method)},$args)", null) }
            val data = withTimeout(when (method) { "resolve" -> 150_000; "verify-session" -> 25_000; else -> 90_000 }) { result.await() }
            data.optJSONObject("error")?.let {
                val error = SourceException(it.optString("code"), it.optString("message"), it.optString("diagnostic"))
                if (method == "resolve" && !args.optBoolean("background")) mutableProblem.value = PlaybackProblem(args.optString("videoId"), error.message.orEmpty(), error.code, error.diagnostic)
                throw error
            }
            if (method == "resolve" && !args.optBoolean("background")) mutableProblem.value = null
            return data
        } catch (_: TimeoutCancellationException) {
            val error = SourceException("TIMEOUT", "YouTube took too long to respond. Check your connection and retry.")
            if (method == "resolve" && !args.optBoolean("background")) mutableProblem.value = PlaybackProblem(args.optString("videoId"), error.message.orEmpty(), error.code, "resolve: TIMEOUT")
            throw error
        }
        finally { pending.remove(id) }
    }
    fun reportPlaybackHttpFailure(videoId: String, status: Int, paths: Collection<String> = emptyList()) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}")) || status !in 100..599) return
        mutableProblem.value = PlaybackProblem(videoId,
            "YouTube rejected the audio request (HTTP $status). Tap Retry to request a fresh stream.",
            "MEDIA_HTTP_$status", "Media3 audio request: HTTP $status" + paths.filter { it in labs.rtensor.music.playback.PlaybackPaths.ALL }.takeIf { it.isNotEmpty() }?.joinToString(prefix = "; paths: ").orEmpty())
    }

    fun beginSignIn() { mutableAccount.value = mutableAccount.value.copy(connecting = true, error = null) }
    fun cancelSignIn() { mutableAccount.value = mutableAccount.value.copy(connecting = false) }
    suspend fun acceptWebSession(candidate: WebSession) = accountChange.withLock {
        loadSession()
        val previous = activeSession
        val previousAccount = mutableAccount.value.copy(connecting = false)
        reset()
        try {
            activeSession = candidate; sessionLoaded = true; session.save(candidate.json().toString())
            mutableAccount.value = AccountState(connecting = true)
            val profile = call("verify-session")
            mutableAccount.value = AccountState(connected = true, name = profile.getString("name"), revision = generation + 1)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                reset(); activeSession = previous
                if (previous != null) session.save(previous.json().toString()) else session.clear()
                mutableAccount.value = previousAccount.copy(error = (error as? SourceException)?.message ?: "Could not verify your YouTube account. Retry sign-in.")
            }
            throw error
        }
    }
    /** True when a YouTube session is active or stored, so signing out has something to remove. */
    @Synchronized fun hasSession(): Boolean = if (sessionLoaded) activeSession != null else session.isStored()
    suspend fun disconnect() = accountChange.withLock {
        reset(); activeSession = null; sessionLoaded = true; session.clear()
        mutableAccount.value = AccountState(); mutableProblem.value = null
    }
    @Synchronized private fun loadSession(): WebSession? {
        if (!sessionLoaded) {
            val stored = session.load()
            activeSession = stored?.let { runCatching { WebSession.parse(JSONObject(it)) }.getOrNull() }
            // Legacy TV OAuth credentials must never be reused as a web session.
            if (stored != null && activeSession == null) {
                session.clear(); mutableAccount.value = AccountState(error = "Sign in again with YouTube to replace the old TV connection.")
            }
            sessionLoaded = true
        }
        return activeSession
    }
    private suspend fun reset() = withContext(Dispatchers.Main.immediate) {
        generation++
        transport.cancelAll(); proof.reset()
        pending.values.forEach { it.completeExceptionally(SourceException("CANCELLED", "The account connection changed. Retry your request.")) }; pending.clear()
        webView?.removeJavascriptInterface("MusicNative"); webView?.destroy(); webView = null
        ready.completeExceptionally(SourceException("CANCELLED", "The account connection changed. Retry your request."))
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
                        transport.cancelAll(); proof.reset()
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
        @JavascriptInterface fun loadSession(): String = if (epoch == generation) this@YouTubeRuntime.loadSession()?.json()?.toString().orEmpty() else ""
        @JavascriptInterface fun result(id: String, json: String) {
            if (epoch != generation || json.length > MAX_RESPONSE) return
            runCatching { JSONObject(json) }.onSuccess { pending[id]?.complete(it) }
        }
        @JavascriptInterface fun event(type: String, json: String) {
            if (epoch != generation) return
            val data = runCatching { JSONObject(json) }.getOrNull() ?: return
            when (type) {
                "metadata" -> runCatching { YouTubeMetadata.parse(data) }.onSuccess { mutableMetadata.tryEmit(it) }
                "ready" -> ready.complete(Unit)
                "connected" -> mutableAccount.value = AccountState(connected = true, name = data.optString("name").take(120), revision = epoch + 1)
                "signed-out" -> mutableAccount.value = AccountState()
                "auth-error" -> mutableAccount.value = AccountState(error = data.optString("message", "Reconnect your YouTube account."))

            }
        }
        @JavascriptInterface fun cancelHttp(id: String) { if (epoch == generation) transport.cancel(id) }
        @JavascriptInterface fun http(id: String, url: String, options: String) {
            if (epoch != generation) return
            val credentials = this@YouTubeRuntime.loadSession()
            if (epoch != generation) return
            scope.launch(Dispatchers.IO) {
                if (epoch != generation) return@launch
                val response = transport.execute(id, url, options, browserUserAgent, credentials)
                withContext(Dispatchers.Main.immediate) { if (epoch == generation) webView?.evaluateJavascript("musicHttpResult(${JSONObject.quote(id)},$response)", null) }
            }
        }
        @JavascriptInterface fun proof(id: String, args: String) {
            if (epoch != generation || args.length > 1_048_576) return
            scope.launch {
                if (epoch != generation) return@launch
                val result = try { proof.mint(JSONObject(args)) }
                catch (_: Exception) { JSONObject().put("error", "YouTube runtime verification did not complete.") }
                if (epoch == generation) webView?.evaluateJavascript("musicProofResult(${JSONObject.quote(id)},$result)", null)
            }
        }
    }

    companion object {
        private const val BASE = "https://appassets.androidplatform.net/assets/engine/"
        private const val MAX_RESPONSE = 8 * 1024 * 1024
        @Volatile private var instance: YouTubeRuntime? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: YouTubeRuntime(context).also { instance = it } }
        fun isProviderUrl(value: String): Boolean = ProviderTransport.isYouTubeUrl(value)
    }
}
