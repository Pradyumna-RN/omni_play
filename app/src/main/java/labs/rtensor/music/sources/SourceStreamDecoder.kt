package labs.rtensor.music.sources

import labs.rtensor.music.data.Track
import org.json.JSONObject

/** Shared response validation for direct accounts and configured music servers. */
internal object SourceStreamDecoder {
    fun decode(o: JSONObject?, track: Track, provider: MusicProvider, requirePlayable: Boolean = true): ProviderStream? {
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
        val stream = ProviderStream(o.optString("id").ifBlank { o.optString("trackId") }, MusicProvider.parse(o.optString("provider")) ?: provider,
            mediaUrl(o), title, artist, duration,
            mime, rate, number(o, metadata, listOf("bitDepth", "bit_depth", "bitsPerSample")).toInt(), o.optInt("channels", 0),
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

}
