package labs.rtensor.music.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import labs.rtensor.music.data.Track
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No provider cookies, borrowed app credentials or DRM keys are bundled here.
 * Servers/addons are explicitly configured by the listener. API authorization
 * goes only to that server, never to the returned media URL. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceServerClient(private val connection: SourceConnection, private val client: OkHttpClient = defaultClient()) {
    suspend fun probe(): String {
        val path = when (connection.protocol) {
            SourceProtocol.GATEWAY -> "v1/capabilities"
            SourceProtocol.ADDON -> "manifest.json"
            SourceProtocol.TIDAL_HIFI -> ""
        }
        val result = json(request(path))
        require(result is JSONObject) { "The server did not return a supported response." }
        if (connection.protocol == SourceProtocol.TIDAL_HIFI) require(result.optString("version").isNotBlank()) { "This is not a supported HiFi API server." }
        if (connection.protocol == SourceProtocol.ADDON) require(result.optString("name").isNotBlank() || result.optString("id").isNotBlank()) { "This is not a supported audio addon." }
        if (connection.protocol == SourceProtocol.GATEWAY) {
            require(result.optInt("version") == 1 && result.optBoolean("fullTracks")) { "This server does not offer full-track playback." }
            val providers = result.optJSONArray("providers") ?: JSONArray()
            require(connection.provider == MusicProvider.LIBRARY || (0 until providers.length()).any {
                MusicProvider.parse(providers.optString(it)) == connection.provider
            }) { "This account server does not offer ${connection.provider.label}." }
        }
        if (connection.protocol == SourceProtocol.ADDON && result.has("resources")) {
            val resources = result.optJSONArray("resources") ?: JSONArray()
            require((0 until resources.length()).any { resources.optString(it) in setOf("search", "stream") }) { "This addon does not offer audio search or playback." }
        }
        return result.optString("name").takeIf { it.isNotBlank() } ?: connection.provider.label
    }

    suspend fun resolve(track: Track): List<ProviderStream> = when (connection.protocol) {
        SourceProtocol.GATEWAY -> {
            val payload = JSONObject().put("track", track.json()).put("provider", connection.provider.wire)
                .put("priority", JSONArray(listOf("atmos", "lossless")))
            val response = json(request("v1/resolve").post(payload.toString().toRequestBody("application/json".toMediaType()))) as? JSONObject
            val a = response?.optJSONArray("streams") ?: JSONArray()
            (0 until minOf(a.length(), 12)).mapNotNull { decodeStream(a.optJSONObject(it), track) }
        }
        SourceProtocol.ADDON -> resolveAddon(track)
        SourceProtocol.TIDAL_HIFI -> resolveTidal(track)
    }

    private suspend fun resolveAddon(track: Track): List<ProviderStream> {
        val response = json(request("search", mapOf("q" to "${track.artist} ${track.title}", "quality" to "hi_res", "atmos" to "auto")))
        val a = when (response) { is JSONArray -> response; is JSONObject -> response.optJSONArray("tracks") ?: response.optJSONArray("results"); else -> null } ?: return emptyList()
        val selected = (0 until minOf(a.length(), 30)).mapNotNull { a.optJSONObject(it) }.filter { item ->
            val candidate = decodeStream(item, track, requirePlayable = false)
            candidate?.matches(track) == true
        }.take(3)
        return selected.mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Identifiers are path segments, never server-supplied URLs.
            val url = connection.url.toHttpUrl().newBuilder().addPathSegment("stream").addPathSegment(id)
                .addQueryParameter("quality", "hi_res").addQueryParameter("atmos", "auto").build()
            val result = json(authorize(Request.Builder().url(url))) as? JSONObject ?: return@mapNotNull null
            val stream = result.optJSONObject("stream") ?: result
            if (mediaUrl(stream).isBlank()) return@mapNotNull null
            val merged = JSONObject(item.toString()).apply {
                stream.keys().forEach { key -> put(key, stream.get(key)) }
            }
            decodeStream(merged, track)
        }
    }

    private suspend fun resolveTidal(track: Track): List<ProviderStream> {
        val response = json(request("search/", mapOf("s" to "${track.artist} ${track.title}"))) as? JSONObject ?: return emptyList()
        val root = response.optJSONObject("data") ?: response
        val a = root.optJSONObject("tracks")?.optJSONArray("items") ?: root.optJSONArray("items") ?: JSONArray()
        val result = mutableListOf<ProviderStream>()
        for (i in 0 until minOf(a.length(), 20)) {
            val item = a.optJSONObject(i) ?: continue
            val candidate = decodeStream(item, track, requirePlayable = false) ?: continue
            if (!candidate.matches(track)) continue
            val body = json(request("track/", mapOf("id" to candidate.id, "quality" to "HI_RES_LOSSLESS"))) as? JSONObject ?: continue
            val data = body.optJSONObject("data") ?: body
            if (data.optString("assetPresentation", "FULL") != "FULL") continue
            TidalManifests.decode(item, data, connection.url).forEach { candidate ->
                decodeStream(candidate.metadata, track)?.copy(streamKeys = candidate.streamKeys)?.let(result::add)
            }
            if (result.size >= 12) break
        }
        return result
    }

    private fun decodeStream(o: JSONObject?, track: Track, requirePlayable: Boolean = true): ProviderStream? =
        SourceStreamDecoder.decode(o, track, connection.provider, requirePlayable)

    private fun mediaUrl(o: JSONObject) = listOf("url", "streamUrl", "streamURL", "directUrl", "mediaUrl", "downloadUrl")
        .firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()

    private fun request(path: String, params: Map<String, String> = emptyMap()): Request.Builder {
        val b = connection.url.toHttpUrl().newBuilder()
        if (path.isNotEmpty()) b.addPathSegments(path)
        params.forEach { (key, value) -> b.addQueryParameter(key, value) }
        return authorize(Request.Builder().url(b.build()))
    }
    private fun authorize(b: Request.Builder): Request.Builder = b.header("Accept", "application/json")
        .header("User-Agent", "RTensorLabs-Music-Source/1").apply { if (connection.token.isNotBlank()) header("Authorization", "Bearer ${connection.token}") }

    private suspend fun json(builder: Request.Builder): Any = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("The music server could not be reached.")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val parsed = response.use { r ->
                        if (!r.isSuccessful) throw IOException(if (r.code in setOf(401, 403)) "The music server needs account access." else "The music server returned HTTP ${r.code}.")
                        val body = r.body ?: throw IOException("The music server returned an empty response.")
                        if (body.contentLength() > 1_000_000) throw IOException("The server response is too large.")
                        val text = body.charStream().use { reader ->
                            val result = StringBuilder(); val block = CharArray(8192)
                            while (true) { val n = reader.read(block); if (n < 0) break; if (result.length + n > 1_000_000) throw IOException("The server response is too large."); result.append(block, 0, n) }
                            result.toString()
                        }
                        if (text.trimStart().startsWith('[')) JSONArray(text) else JSONObject(text)
                    }
                    if (continuation.isActive) continuation.resume(parsed)
                } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IOException("The music server did not return a supported full-track response.")) }
            }
        })
    }
    companion object {
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
