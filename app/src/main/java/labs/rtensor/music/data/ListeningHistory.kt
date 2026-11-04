package labs.rtensor.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** One listen: when it started, how long it actually played, and whether it was skipped early. */
data class PlayEvent(val track: Track, val startedAt: Long, val listenedMs: Long, val skipped: Boolean) {
    fun json(): JSONObject = JSONObject().put("track", track.json()).put("at", startedAt).put("ms", listenedMs).put("skip", skipped)
    companion object {
        fun parse(o: JSONObject) = PlayEvent(Track.fromJson(o.getJSONObject("track")), o.getLong("at"), o.optLong("ms"), o.optBoolean("skip"))
    }
}

data class ListeningStats(val minutesThisWeek: Long, val playsThisWeek: Int, val topArtist: String?, val topTrack: Track?)

/** Day buckets for the History screen, newest first. */
data class HistoryDay(val label: String, val events: List<PlayEvent>)

/**
 * Local listening history. It stays on this device and feeds the History screen and the
 * on-device "Mixed for you" ranking; nothing here is sent anywhere.
 */
class ListeningHistory(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "listening-history.json"))

    @Volatile var events: List<PlayEvent> = load(); private set
    private var revision = -1L

    /** UI mutations have an ordered revision, whereas IO jobs may run out of
     * order. A late write must never undo a clear/remove or a newer listen. */
    @Synchronized fun replace(snapshot: List<PlayEvent>, version: Long) {
        if (version <= revision) return
        revision = version
        events = snapshot.take(MAX_EVENTS).toList()
        persist()
    }

    private fun load(): List<PlayEvent> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { PlayEvent.parse(array.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun add(event: PlayEvent) {
        events = (listOf(event) + events).take(MAX_EVENTS)
        persist()
    }

    @Synchronized fun remove(startedAt: Long) { events = events.filterNot { it.startedAt == startedAt }; persist() }
    /** Forget every play of one song, so removing it from History doesn't reveal an older play. */
    @Synchronized fun removeSong(trackId: String) { events = events.filterNot { songKey(it.track.id) == songKey(trackId) }; persist() }
    @Synchronized fun clear() { events = emptyList(); persist() }

    @Synchronized fun enrich(transform: (Track) -> Track) {
        val next = events.map { it.copy(track = transform(it.track)) }
        if (next != events) { events = next; persist() }
    }

    private fun persist() {
        val array = JSONArray(); events.forEach { array.put(it.json()) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString()); tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_EVENTS = 2000
        /** A listen counts once it reaches 30 seconds or half the song, like common scrobbling rules. */
        fun counts(listenedMs: Long, durationMs: Long) = listenedMs >= 30_000 || (durationMs in 1..60_000 && listenedMs * 2 >= durationMs)

        fun stats(events: List<PlayEvent>, now: Long = System.currentTimeMillis()): ListeningStats {
            val week = events.filter { !it.skipped && now - it.startedAt < 7 * DAY }
            val topArtist = week.flatMap { artists(it.track.artist) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val topTrack = week.groupBy { it.track.id }.maxByOrNull { it.value.size }?.value?.first()?.track
            return ListeningStats(week.sumOf { it.listenedMs } / 60_000, week.size, topArtist, topTrack)
        }

        /** One id per song, whether or not it carries the "yt:" prefix. */
        fun songKey(id: String) = id.removePrefix("yt:")

        /** History shows each song once, at its latest play; all plays still count for stats and recommendations. */
        fun latestPerSong(events: List<PlayEvent>) = events.sortedByDescending { it.startedAt }.distinctBy { it.track.libraryKey() }

        fun days(events: List<PlayEvent>, now: Long = System.currentTimeMillis()): List<HistoryDay> {
            val today = startOfDay(now)
            return latestPerSong(events).groupBy { startOfDay(it.startedAt) }.toSortedMap(compareByDescending { it }).map { (day, list) ->
                val label = when (day) {
                    today -> "Today"
                    today - DAY -> "Yesterday"
                    else -> java.text.SimpleDateFormat(if (now - day < 6 * DAY) "EEEE" else "EEE, d MMM", java.util.Locale.getDefault()).format(day)
                }
                HistoryDay(label, list)
            }
        }

        fun artists(value: String) = value.split(',', '&', '•').map { it.trim() }.filter { it.isNotBlank() }

        private fun startOfDay(time: Long) = Calendar.getInstance().apply {
            timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        const val DAY = 24L * 60 * 60 * 1000
    }
}
