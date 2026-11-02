package labs.rtensor.music.data

import android.content.Context
import org.json.JSONArray

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    fun tracks(key: String): List<Track> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { Track.fromJson(array.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun writeTracks(key: String, tracks: List<Track>) {
        val array = JSONArray()
        tracks.distinctBy { it.id }.take(100).forEach { array.put(it.json()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) { prefs.edit().putString("theme", value).apply() }
    var reducedMotion: Boolean
        get() = prefs.getBoolean("reduced_motion", false)
        set(value) { prefs.edit().putBoolean("reduced_motion", value).apply() }
    /** Keep playing YouTube Music radio after the queue ends. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }
    /** Record listening history on this device. */
    var trackHistory: Boolean
        get() = prefs.getBoolean("track_history", true)
        set(value) { prefs.edit().putBoolean("track_history", value).apply() }
}
