package labs.rtensor.music.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** One lyric line and the moment it starts. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Time-synced lyrics from LRCLIB (lrclib.net), a free community library that needs no account.
 * Only the song's title, artist and length are sent. Songs it does not have simply come back empty
 * and the plain lyrics are shown instead.
 */
class SyncedLyricsSource(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build(),
    private val base: String = "https://lrclib.net/api",
) {
    suspend fun find(track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        val title = cleanTitle(track.title)
        if (title.isBlank()) return@withContext emptyList()
        val seconds = (track.durationMs / 1000).takeIf { it > 0 }
        // An exact match on title, artist and length first, then the closest search result.
        val exact = get("get") { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist)
            .apply { if (seconds != null) addQueryParameter("duration", seconds.toString()) } }
        parse((exact as? JSONObject)?.optString("syncedLyrics").orEmpty()).ifEmpty {
            val results = get("search") { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist) } as? JSONArray
            val best = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                .filter { it.optString("syncedLyrics").isNotBlank() && (seconds == null || kotlin.math.abs(it.optDouble("duration", 0.0) - seconds) <= 4) }
                .minByOrNull { if (seconds == null) 0.0 else kotlin.math.abs(it.optDouble("duration", 0.0) - seconds) }
            parse(best?.optString("syncedLyrics").orEmpty())
        }
    }

    private fun get(path: String, query: (okhttp3.HttpUrl.Builder) -> Unit): Any? {
        val url = "$base/$path".toHttpUrl().newBuilder().also(query).build()
        val request = Request.Builder().url(url).header("User-Agent", "Hum (https://github.com/RTensorLabs/music-app)").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty().trim()
                if (body.startsWith("[")) JSONArray(body) else if (body.startsWith("{")) JSONObject(body) else null
            }
        } catch (_: java.io.IOException) { null } catch (_: org.json.JSONException) { null }
    }

    companion object {
        /** "Song (Official Video) [4K]" matches far more often as "Song". */
        internal fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[(\[](official|lyric|lyrics|audio|video|music video|hd|4k|visuali[sz]er|remaster(ed)?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()

        /** `[01:23.45] words` (several stamps allowed per line) → lines in time order; blank lines and tags dropped. */
        internal fun parse(lrc: String): List<LyricLine> {
            val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
            val lines = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) return@forEach
                val text = raw.substring(stamps.last().range.last + 1).trim()
                if (text.isEmpty()) return@forEach
                stamps.forEach { m ->
                    val fraction = m.groupValues[3]
                    val ms = when (fraction.length) { 0 -> 0L; 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; else -> fraction.take(3).toLong() }
                    lines += LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + ms, text)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        /** Index of the line playing at [positionMs]: the last one that has started, or -1 before the first. */
        fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
            var low = 0; var high = lines.lastIndex; var found = -1
            while (low <= high) { val mid = (low + high) ushr 1; if (lines[mid].timeMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1 }
            return found
        }
    }
}
