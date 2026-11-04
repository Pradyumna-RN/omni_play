package labs.rtensor.music.sources

import android.net.Uri
import android.util.Base64
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Clear playback responses only. Format claims come from each representation,
 * not the search result or a hard-coded stereo assumption. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object TidalManifests {
    data class Candidate(val metadata: JSONObject, val streamKeys: List<StreamKey> = emptyList())

    fun decode(item: JSONObject, response: JSONObject, baseUrl: String, externalManifest: Boolean = false): List<Candidate> = runCatching {
        require(response.optString("assetPresentation", "FULL") == "FULL")
        val encoded = response.optString("manifest")
        require(encoded.length in 1..1_000_000)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size <= 750_000)
        val text = String(bytes, Charsets.UTF_8)
        if (text.trimStart().startsWith('<')) dash(item, response, baseUrl, bytes, text, externalManifest)
        else bts(item, response, JSONObject(text))
    }.getOrDefault(emptyList())

    private fun metadata(item: JSONObject, response: JSONObject, mime: String, url: String, channels: Int, rate: Int) =
        JSONObject(item.toString()).put("provider", "tidal").put("url", url).put("mime", mime)
            .put("sampleRate", rate).put("bitDepth", response.optInt("bitDepth"))
            .put("channels", channels).put("atmos", mime == "audio/eac3-joc")

    private fun bts(item: JSONObject, response: JSONObject, manifest: JSONObject): List<Candidate> {
        require(manifest.optString("encryptionType", "NONE").equals("NONE", true))
        val codec = manifest.optString("codecs").lowercase()
        val mime = when {
            "flac" in codec -> "audio/flac"
            "alac" in codec -> "audio/alac"
            "ec-3" in codec || "eac3" in codec -> if (response.optString("audioMode") == "DOLBY_ATMOS") "audio/eac3-joc" else "audio/eac3"
            "ac-3" in codec || "ac3" in codec -> "audio/ac3"
            else -> return emptyList()
        }
        val urls = manifest.optJSONArray("urls") ?: return emptyList()
        val url = (0 until urls.length()).map { urls.optString(it) }.firstOrNull(::https) ?: return emptyList()
        val channels = manifest.optInt("channels", response.optInt("channels"))
        val rate = response.optInt("sampleRate", response.optInt("samplingRate"))
        return listOf(Candidate(metadata(item, response, mime, url, channels, rate)))
    }

    private fun dash(item: JSONObject, response: JSONObject, baseUrl: String, bytes: ByteArray, text: String, external: Boolean): List<Candidate> {
        // Keep provider protection in its own player; never mistake a protected
        // manifest for a clear stream or resolve external XML entities here.
        require(!text.contains("ContentProtection", true) && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val uri = if (external) baseUrl.also { require(https(it)) }
            else "data:application/dash+xml;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        // Inline manifests need absolute BaseURLs; external manifests retain
        // their real origin so relative initialization/segment URLs stay valid.
        val manifest = DashManifestParser().parse(Uri.parse(uri), ByteArrayInputStream(bytes))
        require(!manifest.dynamic && manifest.periodCount == 1) { "Expected a complete static music presentation" }
        val expectedMs = item.optLong("durationMs").takeIf { it > 0 } ?: (item.optDouble("duration", 0.0) * 1000).toLong()
        val actualUs = manifest.getPeriodDurationUs(0)
        require(expectedMs <= 0 || actualUs < 0 || actualUs / 1000.0 + 4000 >= expectedMs) { "The manifest contains only part of the recording" }
        val candidates = mutableListOf<Candidate>()
        manifest.getPeriod(0).adaptationSets.forEachIndexed { adaptation, set ->
            set.representations.forEachIndexed representationLoop@ { index, representation ->
                val f = representation.format
                val mime = f.sampleMimeType ?: return@representationLoop
                if (!mime.startsWith("audio/") || f.drmInitData != null) return@representationLoop
                val bases = representation.baseUrls.map { it.url }
                if (bases.isEmpty() || bases.any { !https(it) }) return@representationLoop
                val ranged = listOfNotNull(representation.initializationUri, representation.indexUri).toMutableList()
                representation.index?.let { segments ->
                    val count = segments.getSegmentCount(manifest.getPeriodDurationUs(0))
                    if (count > 0) {
                        ranged += segments.getSegmentUrl(segments.firstSegmentNum)
                        ranged += segments.getSegmentUrl(segments.firstSegmentNum + count - 1)
                    }
                }
                if (bases.any { base -> ranged.any { !https(it.resolveUriString(base)) } }) return@representationLoop
                val channels = f.channelCount.takeIf { it > 0 } ?: response.optInt("channels")
                val rate = f.sampleRate.takeIf { it > 0 } ?: response.optInt("sampleRate", response.optInt("samplingRate"))
                val actualMime = if (mime == "audio/eac3" && response.optString("audioMode") == "DOLBY_ATMOS") "audio/eac3-joc" else mime
                val data = metadata(item, response, actualMime, uri, channels, rate)
                    .put("manifest", true).put("transportMime", "application/dash+xml")
                candidates += Candidate(data, listOf(StreamKey(0, adaptation, index)))
            }
        }
        return candidates.take(12)
    }

    private fun https(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    } == true
}
