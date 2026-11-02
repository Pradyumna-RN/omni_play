package labs.rtensor.music.data

import android.content.Context
import org.json.JSONObject

class YouTubeSource(context: Context) {
    val runtime = YouTubeRuntime.get(context)
    suspend fun metadata(track: Track) {
        if (track.source == Source.YOUTUBE) runtime.call("metadata", JSONObject().put("videoId", track.id.removePrefix("yt:")))
    }
    suspend fun search(query: String): List<Track> = tracks(runtime.call("search", JSONObject().put("query", query)))
    suspend fun collection(entry: FeedEntry): List<Track> = tracks(runtime.call("collection", JSONObject().put("id", entry.browseId).put("kind", entry.kind)))
    suspend fun home(more: Boolean = false): HomeFeed {
        return feed(runtime.call("home", JSONObject().put("more", more)))
    }
    /** YouTube Music's radio for a song: the same up-next mix its own player continues with. */
    suspend fun radio(track: Track, exclude: Collection<String> = emptyList()): List<Track> {
        if (track.source != Source.YOUTUBE) return emptyList()
        val videoId = track.id.removePrefix("yt:").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return emptyList()
        val skip = exclude.toSet() + track.id
        return tracks(runtime.call("radio", JSONObject().put("videoId", videoId))).filter { it.id !in skip }
    }
    suspend fun explore(): HomeFeed = feed(runtime.call("explore"))
    private fun feed(data: JSONObject): HomeFeed {
        val sections = data.getJSONArray("sections")
        return HomeFeed((0 until sections.length()).map { i ->
            val s = sections.getJSONObject(i); val items = s.getJSONArray("items")
            FeedSection(s.getString("title"), (0 until items.length()).map { FeedEntry.parse(items.getJSONObject(it)) })
        }, data.optBoolean("hasMore"), data.optBoolean("signedIn"), data.optBoolean("personalized"))
    }

    suspend fun resolve(track: Track, forceProof: Boolean = false, background: Boolean = false): ResolvedAudio {
        if (track.source == Source.LOCAL) return ResolvedAudio(track.url, null, emptyMap())
        val canonical = YouTubeLink.normalize(track.url)?.takeIf { it.contains("/watch?v=") } ?: throw SourceException("LINK", "Choose a valid YouTube recording.")
        val json = runtime.call("resolve", JSONObject().put("videoId", canonical.substringAfter("?v=")).put("forceProof", forceProof).put("background", background))
        val headers = json.optJSONObject("headers") ?: JSONObject()
        return ResolvedAudio(
            json.getString("url"),
            json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            headers.keys().asSequence().associateWith { headers.getString(it) },
            json.optLong("contentLength", 0),
        )
    }

    private fun tracks(data: JSONObject): List<Track> {
        val items = data.getJSONArray("tracks")
        return (0 until items.length()).map { Track.fromJson(items.getJSONObject(it)) }
    }

}

class SourceException(val code: String, message: String, val diagnostic: String = "") : Exception(message)
