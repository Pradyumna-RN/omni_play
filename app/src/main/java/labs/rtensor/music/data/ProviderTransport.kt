package labs.rtensor.music.data

import android.util.Base64
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** No redirects or shared cookie jar. Account cookies only reach YouTube itself. */
internal class ProviderTransport(private val proofOnly: Boolean = false) {
    private val calls = ConcurrentHashMap<String, Call>()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).callTimeout(24, TimeUnit.SECONDS).build()

    fun cancel(id: String) { calls.remove(id)?.cancel() }
    fun cancelAll() { calls.values.forEach(Call::cancel); calls.clear() }

    internal fun request(url: String, options: String, userAgent: String, session: WebSession? = null): Request {
        require(options.length <= 1_048_576)
        val o = JSONObject(options)
        val method = o.optString("method", "GET").uppercase()
        val headers = o.optJSONObject("headers") ?: JSONObject()
        val media = isMediaUrl(url)
        require(if (proofOnly) isProofUrl(url) else isYouTubeUrl(url) || media)
        require(method in setOf("GET", "POST", "HEAD"))
        if (media) require(method == "GET" && headers.optString("range") == "bytes=0-1")
        val body = if (method == "POST") o.optString("body").toRequestBody(headers.optString("content-type", "application/json").toMediaTypeOrNull()) else null
        val request = Request.Builder().url(url).method(method, body).header("User-Agent", userAgent)
        headers.keys().forEach { key ->
            val lower = key.lowercase()
            if (lower !in setOf("host", "connection", "content-length", "cookie", "user-agent") &&
                (!(proofOnly || media) || lower !in setOf("authorization", "x-goog-authuser", "x-goog-pageid", "x-goog-visitor-id"))) request.header(key, headers.getString(key))
        }
        if (!proofOnly && !media && session != null) request.header("Cookie", session.cookie)
        return request.build()
    }

    fun execute(id: String, url: String, options: String, userAgent: String, session: WebSession? = null): JSONObject = try {
        val media = isMediaUrl(url)
        val call = client.newCall(request(url, options, userAgent, session)); calls[id] = call
        call.execute().use { response ->
            val bytes = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(if (media) 2 else 8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(bytes.size() + n <= MAX_RESPONSE)
                    bytes.write(buffer, 0, n)
                    if (media) break // Never buffer a whole recording for a range probe.
                }
            }
            val responseHeaders = JSONObject()
            listOf("content-type", "content-range").forEach { name -> response.header(name)?.let { responseHeaders.put(name, it) } }
            JSONObject().put("status", response.code).put("headers", responseHeaders)
                .put("body", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        }
    } catch (_: Exception) { JSONObject().put("error", "NETWORK") }
    finally { calls.remove(id) }

    companion object {
        private const val MAX_RESPONSE = 8 * 1024 * 1024
        private fun uri(value: String): URI? = runCatching { URI(value).takeIf { it.scheme == "https" && it.userInfo == null && it.port in listOf(-1, 443) } }.getOrNull()
        fun isYouTubeUrl(value: String): Boolean = uri(value)?.host in setOf("www.youtube.com", "youtube.com", "music.youtube.com", "m.youtube.com")
        fun isMediaUrl(value: String): Boolean = uri(value)?.let { it.host?.endsWith(".googlevideo.com") == true && it.path == "/videoplayback" } ?: false
        fun isProofUrl(value: String): Boolean = uri(value)?.let {
            (it.host == "jnn-pa.googleapis.com" && it.path == "/\$rpc/google.internal.waa.v1.Waa/GenerateIT") ||
                (it.host in setOf("www.google.com", "www.gstatic.com") && (it.path.startsWith("/js/bg/") || it.path.startsWith("/botguard/")))
        } ?: false
    }
}
