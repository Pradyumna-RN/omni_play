package labs.rtensor.music.playback

import labs.rtensor.music.data.ResolvedAudio

/** Per-queue-item media failures. A prefetch or another recording cannot overwrite them. */
internal class PlaybackPaths {
    private data class Entry(var current: String = "", val rejected: MutableSet<String> = linkedSetOf(), var format: ResolvedAudio? = null)
    private val entries = object : LinkedHashMap<String, Entry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 128
    }
    @Synchronized fun record(key: String, path: String) { require(path in ALL); entries.getOrPut(key) { Entry() }.current = path }
    @Synchronized fun rememberFormat(key: String, audio: ResolvedAudio) { entries.getOrPut(key) { Entry() }.format = audio }
    @Synchronized fun format(key: String) = entries[key]?.format
    @Synchronized fun current(key: String) = entries[key]?.current
    @Synchronized fun rejected(key: String): Set<String> = entries[key]?.rejected?.toSet() ?: emptySet()
    @Synchronized fun rejectCurrent(key: String): Boolean {
        val entry = entries[key] ?: return false
        return entry.current in ALL && entry.rejected.add(entry.current)
    }
    @Synchronized fun clear() = entries.clear()
    companion object { val ALL = setOf("VISIONOS", "VISIONOS_MUSIC", "WEB_EMBEDDED", "YTMUSIC", "WEB", "YTMUSIC:proof", "WEB:proof") }
}
