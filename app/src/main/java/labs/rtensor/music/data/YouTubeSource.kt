package labs.rtensor.music.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class YouTubeSource(context: Context) {
    private val app = context.applicationContext

    private fun module() = synchronized(initLock) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        Python.getInstance().getModule("youtube_adapter")
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val json = call("search", query)
        val entries = json.getJSONArray("tracks")
        (0 until entries.length()).map { Track.fromJson(entries.getJSONObject(it)) }
    }

    suspend fun resolve(track: Track): ResolvedAudio = withContext(Dispatchers.IO) {
        if (track.source == Source.LOCAL) return@withContext ResolvedAudio(track.url, null, emptyMap())
        val canonical = YouTubeLink.normalize(track.url) ?: error("This YouTube link is invalid.")
        val json = call("resolve", canonical)
        val headers = json.optJSONObject("headers") ?: JSONObject()
        ResolvedAudio(
            json.getString("url"),
            json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            headers.keys().asSequence().associateWith { headers.getString(it) },
        )
    }

    private fun call(method: String, argument: String): JSONObject {
        val result = JSONObject(module().callAttr(method, argument).toString())
        if (result.has("error")) throw SourceException(result.getString("error"))
        return result
    }

    companion object { private val initLock = Any() }
}

class SourceException(message: String) : Exception(message)
