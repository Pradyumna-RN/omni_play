package labs.rtensor.music.spotify

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import org.json.JSONObject

internal class SpotifyTrackMatcher(context: Context, private val search: suspend (String) -> List<Track>,
    private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("spotify_recording_matches_v1", Context.MODE_PRIVATE)
    private val slots = Semaphore(3)
    private val locks = Array(16) { Mutex() }
    suspend fun match(item: SpotifyItem): Track? = locks[(item.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        val cached = runCatching { JSONObject(prefs.getString(item.id, null) ?: return@runCatching null) }.getOrNull()
        if (cached != null && now() - cached.optLong("time") in 0..TTL) {
            val track = runCatching { Track.fromJson(cached.getJSONObject("track")) }.getOrNull()
            if (track != null && track.source == Source.YOUTUBE && SpotifyRecordingMatch.matches(item, track)) return@withLock track.copy(spotifyId = item.id)
        }
        slots.withPermit {
            val found = search("${item.title} ${item.artist}").asSequence()
                .filter { it.source == Source.YOUTUBE && SpotifyRecordingMatch.matches(item, it) }.firstOrNull()?.copy(spotifyId = item.id)
            if (found != null) {
                // Cache catalogue matches, never expiring media URLs or account credentials.
                if (prefs.all.size >= 256 && !prefs.contains(item.id)) {
                    prefs.all.entries.minByOrNull { runCatching { JSONObject(it.value as String).optLong("time") }.getOrDefault(0L) }
                        ?.let { prefs.edit().remove(it.key).apply() }
                }
                prefs.edit().putString(item.id, JSONObject().put("time", now()).put("track", found.json()).toString()).apply()
            }
            found
        }
    }
    suspend fun matches(items: List<SpotifyItem>): List<Track> = coroutineScope {
        items.filter { it.kind == "track" }.distinctBy { it.id }.take(40).map { item -> async {
            try { match(item) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } }.awaitAll().filterNotNull().distinctBy { it.libraryKey() }
    }
    suspend fun resolve(track: Track): Track {
        if (!pending(track)) return track
        return match(SpotifyItem(track.spotifyId, "track", track.title, track.artist, track.artwork, track.durationMs))
            ?: throw labs.rtensor.music.data.SourceException("SPOTIFY_MATCH", "No verified audio match for this Spotify recording. Search for the song to choose a version.")
    }
    companion object {
        private const val TTL = 7 * 24 * 60 * 60 * 1000L
        fun pending(track: Track) = SpotifyItem.ID.matches(track.spotifyId) && track.id == "spotify:${track.spotifyId}"
        @Volatile private var shared: SpotifyTrackMatcher? = null
        fun get(context: Context): SpotifyTrackMatcher = shared ?: synchronized(this) {
            shared ?: SpotifyTrackMatcher(context.applicationContext, labs.rtensor.music.data.YouTubeSource(context.applicationContext)::search).also { shared = it }
        }
    }
}
