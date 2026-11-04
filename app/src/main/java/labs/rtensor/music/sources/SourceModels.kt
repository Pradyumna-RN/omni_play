package labs.rtensor.music.sources

import labs.rtensor.music.data.Master
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Track
import androidx.media3.common.StreamKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest

enum class MusicProvider(val label: String, val wire: String) {
    APPLE_MUSIC("Apple Music", "apple_music"), TIDAL("TIDAL", "tidal"), QOBUZ("Qobuz", "qobuz"), LIBRARY("Music server", "library");
    companion object {
        fun parse(value: String) = entries.firstOrNull { it.wire == value.lowercase().replace('-', '_') || it.name.equals(value, true) }
    }
}

enum class SourceProtocol(val label: String) {
    GATEWAY("Provider account server"), ADDON("Audio addon"), TIDAL_HIFI("TIDAL HiFi server")
}

data class SourceSummary(val id: String, val name: String, val provider: String, val protocol: String)
fun SourceConnection.summary() = SourceSummary(id, name, provider.label, protocol.label)

/** The URL and token can contain account credentials; persist the whole profile encrypted. */
data class SourceConnection(val id: String, val name: String, val provider: MusicProvider, val protocol: SourceProtocol,
    val url: String, val token: String = "", val enabled: Boolean = true) {
    fun json() = JSONObject().put("id", id).put("name", name).put("provider", provider.name).put("protocol", protocol.name)
        .put("url", url).put("token", token).put("enabled", enabled)
    companion object {
        fun fromJson(o: JSONObject) = SourceConnection(o.getString("id"), o.getString("name"), MusicProvider.valueOf(o.getString("provider")),
            SourceProtocol.valueOf(o.getString("protocol")), o.getString("url"), o.optString("token"), o.optBoolean("enabled", true))
        fun validateUrl(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: error("Enter a valid HTTPS server URL.")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Use an HTTPS server URL without login details or query parameters."
            }
            return url.toString().trimEnd('/')
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class ProviderStream(val id: String, val provider: MusicProvider, val url: String, val title: String, val artist: String,
    val durationMs: Long, val mime: String, val sampleRate: Int, val bitDepth: Int, val channels: Int,
    val atmos: Boolean = false, val manifest: Boolean = false, val transportMime: String = mime,
    val streamKeys: List<StreamKey> = emptyList()) {
    val lossless: Boolean get() = mime in setOf("audio/flac", "audio/alac", "audio/wav", "audio/x-wav", "audio/raw")
    val verifiedAtmosCodec: Boolean get() = atmos && mime == "audio/eac3-joc"
    val cacheKey: String get() {
        // Signed URLs rotate. Stable recording/format identity keeps seeks safe,
        // while preventing AAC, FLAC and different masters from sharing bytes.
        val location = url.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString() ?: url
        val identity = "${provider.wire}:$id:$mime:$transportMime:$sampleRate:$bitDepth:$channels:${streamKeys.joinToString()}:$location"
        return "source-v2:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun matches(track: Track): Boolean = PlaybackRoute.matches(track, asMaster())
    fun asMaster() = Master(url, title, artist, durationMs, channels, sampleRate, bitDepth, mime, verifiedAtmosCodec)
    val eligible: Boolean get() {
        val remote = url.toHttpUrlOrNull()
        val safeUrl = (remote?.isHttps == true && remote.username.isEmpty() && remote.password.isEmpty()) ||
            (manifest && url.startsWith("data:application/dash+xml;base64,"))
        val supportedMultichannel = channels in 3..16 && sampleRate in setOf(44100, 48000, 88200, 96000, 176400, 192000)
        return safeUrl && id.isNotBlank() && id.length <= 256 && title.length <= 512 && artist.length <= 512 && url.length <= 16384 && bitDepth in 0..32 &&
            channels in 0..16 && (channels > 0 || lossless || verifiedAtmosCodec) &&
            (sampleRate in 8000..768000 || (sampleRate == 0 && (lossless || verifiedAtmosCodec))) && (lossless || verifiedAtmosCodec || supportedMultichannel) &&
            (channels <= 2 || supportedMultichannel || (sampleRate == 0 && (lossless || verifiedAtmosCodec))) && durationMs > 0
    }
    val requestedLabel: String get() = "${provider.label} · checking audio…"
}

object SourceQuality {
    /** Actual available editions only; a lossy stream in a FLAC-labelled response is not lossless. */
    fun best(track: Track, streams: List<ProviderStream>): ProviderStream? = streams
        .filter { it.eligible && it.matches(track) }
        .maxWithOrNull(compareBy<ProviderStream>({ it.verifiedAtmosCodec }, { it.lossless && it.channels > 2 }, { it.lossless }, { it.sampleRate }, { it.bitDepth }, { it.channels > 2 }, { it.channels }, { it.provider == MusicProvider.APPLE_MUSIC }))
}
