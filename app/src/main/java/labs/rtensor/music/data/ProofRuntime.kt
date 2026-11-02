package labs.rtensor.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Google's challenge runs separately from the encrypted account/session bridge. */
internal class ProofRuntime(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = ProviderTransport(proofOnly = true)
    private var view: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    @Volatile private var generation = 0L
    @Volatile private var userAgent = ""

    suspend fun mint(args: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        require(args.optString("videoId").matches(Regex("[A-Za-z0-9_-]{11}")))
        require(args.optString("playerBinding").length in 1..4096 && args.optString("streamBinding").length in 1..4096)
        require(args.optJSONObject("challenge")?.optString("program").orEmpty().length in 1..786432)
        ensureView()
        withTimeout(10_000) { ready.await() }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>(); pending[id] = result
        try {
            view?.evaluateJavascript("proofCall(${JSONObject.quote(id)},$args)", null)
            withTimeout(55_000) { result.await() }
        } finally { pending.remove(id) }
    }

    fun reset() {
        generation++; transport.cancelAll()
        pending.values.forEach { it.cancel() }; pending.clear()
        view?.removeJavascriptInterface("ProofNative"); view?.destroy(); view = null
        ready.cancel()
        ready = CompletableDeferred()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView() {
        if (view != null) return
        val epoch = generation
        view = WebView(app).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgent = settings.userAgentString
            addJavascriptInterface(object {
                @JavascriptInterface fun ready() { if (epoch == generation) ready.complete(Unit) }
                @JavascriptInterface fun result(id: String, json: String) {
                    if (epoch == generation && json.length < 32_768) runCatching { JSONObject(json) }.onSuccess { pending[id]?.complete(it) }
                }
                @JavascriptInterface fun cancelHttp(id: String) { if (epoch == generation) transport.cancel(id) }
                @JavascriptInterface fun http(id: String, url: String, options: String) {
                    if (epoch != generation) return
                    scope.launch(Dispatchers.IO) {
                        if (epoch != generation) return@launch
                        val response = transport.execute(id, url, options, userAgent)
                        withContext(Dispatchers.Main.immediate) { if (epoch == generation) view?.evaluateJavascript("proofHttpResult(${JSONObject.quote(id)},$response)", null) }
                    }
                }
            }, "ProofNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                    if (request.url.toString() == BASE + "proof.js") WebResourceResponse("application/javascript", "UTF-8", app.assets.open("engine/proof.js"))
                    else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) { handler.cancel() }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean { reset(); return true }
            }
            loadDataWithBaseURL(BASE + "index.html", """<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src 'none'"><script src="proof.js"></script>""", "text/html", "UTF-8", null)
        }
    }
    companion object { private const val BASE = "https://appassets.androidplatform.net/assets/engine/" }
}
