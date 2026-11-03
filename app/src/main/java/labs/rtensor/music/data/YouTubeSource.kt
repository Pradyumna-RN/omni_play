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
    /** YouTube Music's "Related" page for a song: similar songs, artists and playlists. */
    suspend fun related(track: Track): List<FeedSection> {
        val videoId = track.id.removePrefix("yt:").takeIf { track.source == Source.YOUTUBE && it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return emptyList()
        return feed(runtime.call("related", JSONObject().put("videoId", videoId))).sections
    }
    private fun feed(data: JSONObject): HomeFeed {
        val sections = data.getJSONArray("sections")
        return HomeFeed((0 until sections.length()).map { i ->
            val s = sections.getJSONObject(i); val items = s.getJSONArray("items")
            FeedSection(s.getString("title"), (0 until items.length()).map { FeedEntry.parse(items.getJSONObject(it)) })
        }, data.optBoolean("hasMore"), data.optBoolean("signedIn"), data.optBoolean("personalized"))
    }

    suspend fun resolve(track: Track, forceProof: Boolean = false, background: Boolean = false, rejectedPaths: Collection<String> = emptyList(), requiredFormat: ResolvedAudio? = null): ResolvedAudio {
        if (track.source == Source.LOCAL) return ResolvedAudio(track.url, null, emptyMap())
        val canonical = YouTubeLink.normalize(track.url)?.takeIf { it.contains("/watch?v=") } ?: throw SourceException("LINK", "Choose a valid YouTube recording.")
        val format = requiredFormat?.let { JSONObject().put("formatId", it.formatId).put("mimeType", it.mimeType).put("contentLength", it.contentLength) }
        val json = runtime.call("resolve", JSONObject().put("videoId", canonical.substringAfter("?v=")).put("forceProof", forceProof).put("background", background).put("rejectedPaths", org.json.JSONArray(rejectedPaths)).put("requiredFormat", format))
        val headers = json.optJSONObject("headers") ?: JSONObject()
        return ResolvedAudio(
            json.getString("url"),
            json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            headers.keys().asSequence().associateWith { headers.getString(it) },
            json.optLong("contentLength", 0),
            json.optString("playbackPath"),
            json.optInt("formatId"),
        )
    }

    suspend fun confirmPlayback(videoId: String, path: String) {
        runtime.call("confirm-playback", JSONObject().put("videoId", videoId).put("playbackPath", path))
    }

    private fun tracks(data: JSONObject): List<Track> {
        val items = data.getJSONArray("tracks")
        return (0 until items.length()).map { Track.fromJson(items.getJSONObject(it)) }
    }

}

class SourceException(val code: String, message: String, val diagnostic: String = "") : Exception(message)
