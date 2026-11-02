package labs.rtensor.music.data

import org.json.JSONObject

/** Metadata returned by YouTube for the exact recording, never inferred from its title. */
data class YouTubeMetadata(val videoId: String, val title: String, val artist: String, val artwork: String, val durationMs: Long) {
    fun enrich(track: Track): Track = if (track.source != Source.YOUTUBE || track.id != "yt:$videoId") track else track.copy(
        title = if (track.title.isBlank() || track.title == "YouTube recording") title.ifBlank { track.title } else track.title,
        artist = if (track.needsArtist()) artist.ifBlank { track.artist } else track.artist,
        artwork = track.artwork.ifBlank { artwork }, durationMs = track.durationMs.takeIf { it > 0 } ?: durationMs,
    )
    companion object {
        fun parse(o: JSONObject): YouTubeMetadata {
            val id = o.getString("videoId"); require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
            return YouTubeMetadata(id, o.optString("title").take(1000), o.optString("artist").take(1000), o.optString("artwork").take(4096), o.optLong("durationMs").coerceAtLeast(0))
        }
    }
}
fun Track.needsArtist() = source == Source.YOUTUBE && (artist.isBlank() || artist in setOf("Unknown artist", "YouTube Music"))
