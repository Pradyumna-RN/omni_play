package labs.rtensor.music.sources

import android.util.Base64
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
            val encoded = data.optString("manifest")
            if (encoded.isBlank() || encoded.length > 1_000_000) continue
            val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: continue
            // Only clear manifests. Decryption/circumvention is not a client feature.
            if (decoded.trimStart().startsWith('<')) {
                // Clear DASH is handed to Media3; never harvest content keys or
                // mistake a protected MPD for an ordinary audio file.
                if (decoded.contains("ContentProtection", true) || !decoded.contains("https://")) continue
                val mime = if (decoded.contains("flac", true)) "audio/flac" else continue
                val uri = "data:application/dash+xml;base64," + Base64.encodeToString(decoded.toByteArray(), Base64.NO_WRAP)
                val merged = JSONObject(item.toString()).put("url", uri).put("mime", mime).put("provider", "tidal")
                    .put("manifest", true).put("transportMime", "application/dash+xml")
                    .put("sampleRate", data.optInt("sampleRate", data.optInt("samplingRate"))).put("bitDepth", data.optInt("bitDepth")).put("channels", 2)
                decodeStream(merged, track)?.let(result::add)
                if (result.size >= 2) break
                continue
            }
            val manifest = runCatching { JSONObject(decoded) }.getOrNull() ?: continue
            if (manifest.optString("encryptionType", "NONE") != "NONE") continue
            val mediaUrl = manifest.optJSONArray("urls")?.optString(0).orEmpty()
            val codecs = manifest.optString("codecs").lowercase()
            val mime = when { "flac" in codecs -> "audio/flac"; "alac" in codecs -> "audio/alac"; else -> continue }
            val merged = JSONObject(item.toString()).put("url", mediaUrl).put("mime", mime).put("provider", "tidal")
                .put("sampleRate", data.optInt("sampleRate", data.optInt("samplingRate")))
                .put("bitDepth", data.optInt("bitDepth")).put("channels", 2)
            decodeStream(merged, track)?.let(result::add)
            if (result.size >= 2) break
        }
        return result
    }

    private fun decodeStream(o: JSONObject?, track: Track, requirePlayable: Boolean = true): ProviderStream? {
        o ?: return null
        if (o.optBoolean("preview") || o.optBoolean("encrypted") || (o.has("drm") && !o.isNull("drm"))) return null
        if (o.optJSONObject("headers")?.length()?.let { it > 0 } == true) return null // use signed/proxied URLs, not forwarded credentials
        val metadata = o.optJSONObject("metadata") ?: JSONObject()
        val title = o.optString("title").ifBlank { o.optString("name") }
        val artist = when (val a = o.opt("artist")) { is JSONObject -> a.optString("name"); is String -> a; else ->
            o.optJSONArray("artists")?.let { list -> (0 until list.length()).joinToString(", ") { list.optJSONObject(it)?.optString("name") ?: list.optString(it) } }.orEmpty() }
        val duration = o.optLong("durationMs").takeIf { it > 0 } ?: (o.optDouble("durationSeconds", o.optDouble("duration", 0.0)) * 1000).toLong()
        if (requirePlayable && (duration <= 0 || (track.durationMs > 0 && duration + 4000 < track.durationMs))) return null
        val codec = o.optString("codec").ifBlank { o.optString("format") }.lowercase()
        val mime = o.optString("mime").ifBlank { o.optString("mimeType") }.substringBefore(';').ifBlank {
            when { "flac" in codec -> "audio/flac"; "alac" in codec -> "audio/alac"; "eac3-joc" in codec || "ec-3-joc" in codec -> "audio/eac3-joc"; else -> "" }
        }
        val rawRate = number(o, metadata, listOf("sampleRate", "sample_rate", "samplingRate", "sampling_rate", "clockRate", "clock_rate"))
        val rate = if (rawRate in 1.0..768.0) (rawRate * 1000).toInt() else rawRate.toInt()
        val stream = ProviderStream(o.optString("id").ifBlank { o.optString("trackId") }, MusicProvider.parse(o.optString("provider")) ?: connection.provider,
            mediaUrl(o), title, artist, duration,
            mime, rate, number(o, metadata, listOf("bitDepth", "bit_depth", "bitsPerSample")).toInt(), o.optInt("channels", if (mime in setOf("audio/flac", "audio/alac")) 2 else 0),
            o.optBoolean("atmos") || o.optString("audioQuality") == "DOLBY_ATMOS",
            manifest = o.optBoolean("manifest"), transportMime = o.optString("transportMime").ifBlank {
                when (o.optString("format").lowercase()) { "dash" -> "application/dash+xml"; "hls" -> "application/x-mpegURL"; else -> mime }
            })
        return stream.takeIf { !requirePlayable || (it.eligible && it.matches(track)) }
    }

    private fun mediaUrl(o: JSONObject) = listOf("url", "streamUrl", "streamURL", "directUrl", "mediaUrl", "downloadUrl")
        .firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()
    private fun number(o: JSONObject, metadata: JSONObject, keys: List<String>): Double = keys.firstNotNullOfOrNull { key ->
        o.optDouble(key, metadata.optDouble(key, Double.NaN)).takeIf { it.isFinite() && it > 0 }
    } ?: 0.0

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
