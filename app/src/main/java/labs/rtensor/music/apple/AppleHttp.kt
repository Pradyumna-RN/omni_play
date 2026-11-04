package labs.rtensor.music.apple

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Credentials are attached only to the two fixed first-party account APIs. */
internal class AppleHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()) {
    companion object {
        const val API = "https://amp-api.music.apple.com/v1"
        const val PLAYBACK = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
        const val LICENSE = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
        const val AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
    fun request(url: String, credentials: AppleCredentials? = null, body: String? = null,
        range: String? = null): Request {
        val account = AppleOrigins.isApi(url) || url == PLAYBACK || AppleOrigins.isLicense(url)
        require(account || AppleOrigins.isMedia(url)) { "Unsupported Apple address." }
        require(credentials == null || account) { "Account credentials cannot be sent to media servers." }
        require(range == null || (credentials == null && range.matches(Regex("bytes=[0-9]+-[0-9]+"))))
        return Request.Builder().url(url).header("User-Agent", AGENT).header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .apply {
                if (credentials != null) {
                    header("Authorization", "Bearer ${credentials.developerToken}")
                    header("Media-User-Token", credentials.musicUserToken)
                    if (AppleOrigins.isApi(url)) header("Music-User-Token", credentials.musicUserToken)
                }
                if (range != null) header("Range", range)
                if (body == null) get() else post(body.toRequestBody("application/json".toMediaType()))
            }.build()
    }
    fun execute(request: Request, max: Int = 1024 * 1024): ByteArray {
        return client.newCall(request).execute().use { read(it, max) }
    }
    private fun read(response: Response, max: Int): ByteArray {
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401, 403 -> "Apple rejected access. Check the subscription or sign in again."
                else -> "Apple request failed (HTTP ${response.code})."
            })
            val output = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > max) throw IOException("Apple response exceeds its size limit.")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
    }
    private suspend fun fetch(request: Request, read: (Response) -> ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Apple request could not complete.")) }
            override fun onResponse(call: Call, response: Response) {
                try { val bytes = response.use(read); if (continuation.isActive) continuation.resume(bytes) }
                catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    suspend fun storefront(credentials: AppleCredentials): String = withContext(Dispatchers.IO) {
        val json = JSONObject(String(fetch(request("$API/me/storefront", credentials)) { read(it, 128 * 1024) }, Charsets.UTF_8))
        val value = json.getJSONArray("data").getJSONObject(0).getString("id")
        require(value.matches(Regex("[a-z]{2}"))) { "Apple did not provide an account region." }
        value
    }
    fun json(url: String, credentials: AppleCredentials, body: JSONObject? = null): JSONObject =
        JSONObject(String(execute(request(url, credentials, body?.toString())), Charsets.UTF_8))
    fun media(url: String, range: String? = null, max: Int = 1024 * 1024) = execute(request(url, range = range), max)
    suspend fun jsonAsync(url: String, credentials: AppleCredentials, body: JSONObject? = null): JSONObject =
        JSONObject(String(fetch(request(url, credentials, body?.toString())) { read(it, 1024 * 1024) }, Charsets.UTF_8))
    suspend fun mediaAsync(url: String): ByteArray = fetch(request(url)) { read(it, 1024 * 1024) }
    fun initialization(segment: AppleSegment): ByteArray {
        return client.newCall(initializationRequest(segment)).execute().use { readInitialization(segment, it) }
    }
    suspend fun initializationAsync(segment: AppleSegment): ByteArray = fetch(initializationRequest(segment)) { readInitialization(segment, it) }
    private fun initializationRequest(segment: AppleSegment): Request {
        val start = segment.rangeStart ?: 0L
        val length = segment.rangeLength ?: (1024L * 1024)
        require(length in 8..(1024L * 1024))
        return request(segment.url, range = "bytes=$start-${start + length - 1}")
    }
    private fun readInitialization(segment: AppleSegment, response: Response): ByteArray {
            val start = segment.rangeStart ?: 0L
            val length = segment.rangeLength ?: (1024L * 1024)
            require(response.code == 206 || (response.code == 200 && start == 0L)) { "Apple initialization range was rejected." }
            if (response.code == 206) require(response.header("Content-Range").orEmpty().startsWith("bytes $start-"))
            val input = response.body?.byteStream() ?: error("Apple returned no initialization data.")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < length) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - output.size()).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (segment.rangeLength != null) require(output.size().toLong() == length) { "Apple initialization was truncated." }
            return output.toByteArray()
    }
}
