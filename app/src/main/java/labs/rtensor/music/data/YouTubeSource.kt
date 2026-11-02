package labs.rtensor.music.data

import android.content.Context
import org.json.JSONObject

class YouTubeSource(context: Context) {
    val runtime = YouTubeRuntime.get(context)
    suspend fun search(query: String): List<Track> = tracks(runtime.call("search", JSONObject().put("query", query)))
    suspend fun collection(entry: FeedEntry): List<Track> = tracks(runtime.call("collection", JSONObject().put("id", entry.browseId).put("kind", entry.kind)))
    suspend fun home(more: Boolean = false): HomeFeed {
        val data = runtime.call("home", JSONObject().put("more", more)); val sections = data.getJSONArray("sections")
        return HomeFeed((0 until sections.length()).map { i ->
            val s = sections.getJSONObject(i); val items = s.getJSONArray("items")
            FeedSection(s.getString("title"), (0 until items.length()).map { FeedEntry.parse(items.getJSONObject(it)) })
        }, data.optBoolean("hasMore"))
    }

    suspend fun resolve(track: Track): ResolvedAudio {
        if (track.source == Source.LOCAL) return ResolvedAudio(track.url, null, emptyMap())
        val canonical = YouTubeLink.normalize(track.url)?.takeIf { it.contains("/watch?v=") } ?: throw SourceException("LINK", "Choose a valid YouTube recording.")
        val json = runtime.call("resolve", JSONObject().put("videoId", canonical.substringAfter("?v=")))
        val headers = json.optJSONObject("headers") ?: JSONObject()
        return ResolvedAudio(
            json.getString("url"),
            json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            headers.keys().asSequence().associateWith { headers.getString(it) },
        )
    }

    private fun tracks(data: JSONObject): List<Track> {
        val items = data.getJSONArray("tracks")
        return (0 until items.length()).map { Track.fromJson(items.getJSONObject(it)) }
    }

}

class SourceException(val code: String, message: String) : Exception(message)
