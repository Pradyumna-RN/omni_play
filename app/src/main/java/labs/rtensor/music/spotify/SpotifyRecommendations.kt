package labs.rtensor.music.spotify

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import labs.rtensor.music.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

internal class SpotifyRecommendations(context: Context,
    private val api: SpotifyCatalogueAccess = SpotifyCatalogueClient(context), private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("spotify_profile", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AccountState(connected = prefs.getBoolean("connected", false), name = prefs.getString("name", "").orEmpty()))
    val state = mutable.asStateFlow()
    private data class Cached(val at: Long, val data: JSONObject)
    private val cache = LinkedHashMap<String, Cached>()
    private val locks = Array(12) { Mutex() }
    @Volatile private var revision = 0L
    private var authGeneration = 0L
    fun connecting() { authGeneration++; mutable.update { it.copy(connecting = true, error = null) } }
    fun cancel() { authGeneration++; mutable.update { it.copy(connecting = false) } }
    suspend fun verify(): Boolean {
        val ticket = ++authGeneration
        return try {
            val name = api.request("profile").getString("name").take(120)
            require(name.isNotBlank())
            if (ticket != authGeneration) return false
            synchronized(cache) { cache.clear(); revision++ }
            prefs.edit().putBoolean("connected", true).putString("name", name).apply()
            mutable.value = AccountState(connected = true, name = name, revision = revision)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (ticket != authGeneration) return false
            prefs.edit().putBoolean("connected", false).apply()
            mutable.update { it.copy(connected = false, connecting = false, error = (e as? SpotifyCatalogueException)?.message ?: "Spotify sign-in could not be verified. Reconnect and try again.") }; false
        }
    }
    suspend fun disconnect() {
        authGeneration++
        synchronized(cache) { cache.clear(); revision++ }
        prefs.edit().clear().apply()
        mutable.value = AccountState(revision = revision)
        try { api.request("disconnect") } finally { api.close() }
    }
    private suspend fun fetch(operation: String, args: JSONObject = JSONObject()): JSONObject {
        if (!mutable.value.connected) throw SpotifyCatalogueException("AUTH_REQUIRED")
        val epoch = revision
        val key = "$epoch:$operation:$args"
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            synchronized(cache) { cache[key]?.takeIf { now() - it.at in 0..CACHE_MS }?.data }?.let { return@withLock it }
            val result = try { api.request(operation, args) }
            catch (e: SpotifyCatalogueException) {
                if (e.code == "AUTH_REQUIRED" && epoch == revision) {
                    prefs.edit().putBoolean("connected", false).apply()
                    mutable.update { it.copy(connected = false, error = e.message) }
                    synchronized(cache) { cache.clear() }
                }
                throw e
            }
            ensureActiveEpoch(epoch)
            synchronized(cache) {
                cache[key] = Cached(now(), result)
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            result
        }
    }
    private fun ensureActiveEpoch(epoch: Long) { if (epoch != revision) throw CancellationException("Spotify account changed") }
    suspend fun home(): HomeFeed {
        val sections = fetch("home", JSONObject().put("timeZone", TimeZone.getDefault().id)).getJSONArray("sections")
        return HomeFeed((0 until sections.length()).mapNotNull { i ->
            val section = sections.getJSONObject(i)
            val entries = items(section.getJSONArray("items")).map { it.entry() }
            entries.takeIf { it.isNotEmpty() }?.let { FeedSection(section.optString("title", "Spotify picks"), it) }
        }, false, signedIn = true, personalized = true)
    }
    suspend fun collection(entry: FeedEntry, offset: Int = 0): JSONObject =
        fetch("collection", JSONObject().put("uri", SpotifyItem.from(entry).uri).put("offset", offset))
    fun tracks(page: JSONObject): List<Track> = items(page.getJSONArray("items")).filter { it.kind == "track" }.map { it.track() }
    suspend fun radio(seed: Track, exclude: Collection<String>): List<Track> {
        if (seed.source == Source.LOCAL) return emptyList()
        val id = seed.spotifyId.takeIf(SpotifyItem.ID::matches) ?: run {
            val results = items(fetch("search", JSONObject().put("query", "${seed.title} ${seed.artist}".take(400))).getJSONArray("items"))
            results.firstOrNull { SpotifyRecordingMatch.matches(it, seed) }?.id ?: throw SpotifyCatalogueException("NO_RADIO")
        }
        val ignored = exclude.toSet()
        return items(fetch("radio", JSONObject().put("id", id)).getJSONArray("items"))
            .filter { it.kind == "track" && it.id != id }.map { it.track() }.filter { it.id !in ignored }
    }
    fun close() { api.close() }
    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L
        private fun items(array: JSONArray) = (0 until array.length()).take(80).mapNotNull { i ->
            runCatching { SpotifyItem.parse(array.getJSONObject(i)) }.getOrNull()
        }.distinctBy { it.uri }
    }
}
