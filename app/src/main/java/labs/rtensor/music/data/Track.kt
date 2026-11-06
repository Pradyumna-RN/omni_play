package labs.rtensor.music.data

import org.json.JSONObject

enum class Source { YOUTUBE, LOCAL }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val artwork: String = "",
    val durationMs: Long = 0,
    val source: Source = Source.YOUTUBE,
    /** Discovery identity; the audio URL/source above still names the actual recording. */
    val spotifyId: String = "",
    /** YouTube playlist occurrence; never part of the recording/cache identity. */
    val playlistItemId: String = "",
) {
    /** Imports of the same document share an identity even if an older build used another id. */
    fun libraryKey(): String = if (source == Source.LOCAL) "local:$url" else if (spotifyId.isNotBlank()) "spotify:$spotifyId" else id.removePrefix("yt:")

    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("artist", artist)
        put("url", url); put("artwork", artwork); put("durationMs", durationMs)
        put("source", source.name)
        if (spotifyId.isNotBlank()) put("spotifyId", spotifyId)
        if (playlistItemId.isNotBlank()) put("playlistItemId", playlistItemId)
    }

    companion object {
        /** A Spotify track id: 22 base-62 characters. Compiled once, not per track. */
        private val SpotifyId = Regex("[A-Za-z0-9]{22}")
        fun fromJson(o: JSONObject) = Track(
            id = o.getString("id"), title = o.getString("title"),
            artist = o.optString("artist").takeUnless { it in setOf("Unknown artist", "YouTube Music") }.orEmpty(), url = o.getString("url"),
            artwork = o.optString("artwork"), durationMs = o.optLong("durationMs"),
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.YOUTUBE),
            spotifyId = o.optString("spotifyId").takeIf { it.matches(SpotifyId) }.orEmpty(),
            playlistItemId = o.optString("playlistItemId"),
        )
    }
}

data class ResolvedAudio(val url: String, val mimeType: String?, val headers: Map<String, String>, val contentLength: Long = 0, val playbackPath: String = "", val formatId: Int = 0)
