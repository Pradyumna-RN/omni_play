package labs.rtensor.music.data

import android.content.Context
import org.json.JSONArray

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    init {
        // Retired modes must not return after an upgrade or a later playback restart.
        if (prefs.contains("spatial_audio") || prefs.contains("joc_objects"))
            prefs.edit().remove("spatial_audio").remove("joc_objects").apply()
    }

    fun tracks(key: String): List<Track> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { Track.fromJson(array.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList()).filterNot(::isRemoved).distinctBy { it.libraryKey() }

    fun writeTracks(key: String, tracks: List<Track>) {
        val array = JSONArray()
        val unique = tracks.filterNot(::isRemoved).distinctBy { it.libraryKey() }
        (if (key == "saved") unique else unique.take(100)).forEach { array.put(it.json()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    /** Unregister the import, never delete the user's original document. Keep a
     * tombstone so an old media session cannot restore it after an app restart. */
    fun removeLocal(track: Track) {
        require(track.source == Source.LOCAL)
        prefs.edit().putStringSet("removed_local_uris", removedLocalUris + track.url)
            .putLong("local_removal_revision", localRemovalRevision + 1).apply()
    }
    fun restoreLocal(track: Track, importRevision: Long = localRemovalRevision): Boolean {
        if (importRevision != localRemovalRevision && isRemoved(track)) return false
        prefs.edit().putStringSet("removed_local_uris", removedLocalUris - track.url).apply()
        return true
    }
    val localRemovalRevision: Long get() = prefs.getLong("local_removal_revision", 0)
    val removedLocalUris: Set<String> get() = prefs.getStringSet("removed_local_uris", emptySet()).orEmpty().toSet()
    fun isRemoved(track: Track) = track.source == Source.LOCAL && track.url in removedLocalUris
    /** Older builds left unhearted imports in Home/history. Their registration
     * lived in Saved, so migrate those orphan references to explicit removals. */
    fun reconcileImports(otherReferences: List<Track>) {
        val registered = tracks("saved").filter { it.source == Source.LOCAL }.map { it.libraryKey() }.toSet()
        val orphans = (tracks("history") + otherReferences).filter { it.source == Source.LOCAL && it.libraryKey() !in registered }
        if (orphans.isNotEmpty()) prefs.edit().putStringSet("removed_local_uris", removedLocalUris + orphans.map { it.url }).apply()
    }

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) { prefs.edit().putString("theme", value).apply() }
    var accent: AccentChoice
        get() = AccentChoice.parse(prefs.getString("accent", null))
        set(value) { prefs.edit().putString("accent", value.wire).apply() }
    /** Listening room or Classic. Reading the default writes nothing. */
    var look: LookChoice
        get() = LookChoice.parse(prefs.getString("look", null))
        set(value) { prefs.edit().putString("look", value.wire).apply() }
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
    var processedAudio: Boolean
        get() = prefs.getBoolean("processed_audio", true)
        set(value) { prefs.edit().putBoolean("processed_audio", value).apply() }
    var developerOptions: Boolean
        get() = prefs.getBoolean("developer_options", false)
        set(value) { prefs.edit().putBoolean("developer_options", value).apply() }
    var recommendationEngine: labs.rtensor.music.recommendations.RecommendationEngine
        get() = labs.rtensor.music.recommendations.RecommendationEngine.parse(prefs.getString("recommendation_engine", null))
        set(value) { prefs.edit().putString("recommendation_engine", value.wire).apply() }

    companion object {
        /** The phone's latest Home recommendations, so the car can show them without the UI. */
        const val HOME_RECOMMENDATIONS = "home_recommendations"
    }
}
