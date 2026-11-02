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
) {
    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("artist", artist)
        put("url", url); put("artwork", artwork); put("durationMs", durationMs)
        put("source", source.name)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            id = o.getString("id"), title = o.getString("title"),
            artist = o.optString("artist", "Unknown artist"), url = o.getString("url"),
            artwork = o.optString("artwork"), durationMs = o.optLong("durationMs"),
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.YOUTUBE),
        )
    }
}

data class ResolvedAudio(val url: String, val mimeType: String?, val headers: Map<String, String>)
