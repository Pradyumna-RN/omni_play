package labs.rtensor.music.imports

import labs.rtensor.music.data.Track
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

internal data class ImportSong(val location: String = "", val title: String = "", val artist: String = "", val durationMs: Long = 0)
internal data class ImportDocument(val title: String, val songs: List<ImportSong>)

/** Bounded metadata parsing only. File locations are resolved separately inside a user-granted tree. */
internal object PlaylistDocuments {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SONGS = 5000
    fun parse(text: String, filename: String): List<ImportDocument> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Playlist files must be smaller than 2 MB." }
        val data = text.removePrefix("\uFEFF").trim()
        val title = filename.substringBeforeLast('.').take(150).ifBlank { "Imported playlist" }
        val result = when {
            filename.endsWith(".pls", true) || data.startsWith("[playlist]", true) -> pls(data, title)
            filename.endsWith(".m3u", true) || filename.endsWith(".m3u8", true) || data.startsWith("#EXTM3U") || data.startsWith("#EXTINF") -> m3u(data, title)
            filename.endsWith(".csv", true) -> csv(data, title)
            data.startsWith("{") || data.startsWith("[") -> json(data, title)
            data.startsWith("<") -> xml(data, title)
            data.lineSequence().firstOrNull()?.contains(",") == true -> csv(data, title)
            else -> m3u(data, title)
        }.filter { it.songs.isNotEmpty() }
        require(result.isNotEmpty()) { "No songs were found. Choose an M3U, PLS, XSPF, CSV or JSON playlist export." }
        require(result.sumOf { it.songs.size } <= MAX_SONGS) { "Import at most $MAX_SONGS songs at a time." }
        return result.map { document ->
            require(document.songs.all { it.location.length <= 8192 && it.title.length <= 512 && it.artist.length <= 512 }) { "A playlist entry is too long." }
            document.copy(title = document.title.trim().take(150).ifBlank { title }, songs = document.songs.map { it.copy(durationMs = it.durationMs.takeIf { d -> d in 0..86_400_000 } ?: 0) })
        }
    }
    private fun m3u(data: String, name: String): List<ImportDocument> {
        var title = name; var next = ImportSong(); val songs = mutableListOf<ImportSong>()
        for (raw in data.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#PLAYLIST:", true) -> title = line.substringAfter(':').trim()
                line.startsWith("#EXTINF:", true) -> {
                    val label = line.substringAfter(',', ""); val parts = label.split(" - ", limit = 2)
                    next = ImportSong(title = parts.last(), artist = if (parts.size == 2) parts.first() else "",
                        durationMs = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0))
                }
                line.isNotBlank() && !line.startsWith('#') -> { songs += next.copy(location = line); next = ImportSong() }
            }
            require(songs.size <= MAX_SONGS) { "This playlist is too large." }
        }
        return listOf(ImportDocument(title, songs))
    }
    private fun pls(data: String, title: String): List<ImportDocument> {
        val fields = data.lineSequence().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].trim().lowercase() to it[1].trim() }
        val songs = fields.keys.mapNotNull { Regex("file([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.sorted().map { i ->
            ImportSong(fields["file$i"].orEmpty(), fields["title$i"].orEmpty(), durationMs = (fields["length$i"]?.toLongOrNull() ?: 0).coerceAtLeast(0) * 1000)
        }
        return listOf(ImportDocument(title, songs))
    }
    private fun xml(data: String, fallback: String): List<ImportDocument> {
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(data)) { "Playlist XML must not contain a DOCTYPE or entities." }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(data)); var depth = 0; var inTrack = false; var title = fallback
        var song = ImportSong(); val songs = mutableListOf<ImportSong>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                depth++; require(depth <= 40) { "Playlist XML is too deeply nested." }
                when (parser.name) {
                    "track" -> { inTrack = true; song = ImportSong() }
                    "location", "title", "creator", "duration" -> {
                        val tag = parser.name; val value = parser.nextText(); depth--
                        if (inTrack) song = when (tag) {
                            "location" -> song.copy(location = value)
                            "title" -> song.copy(title = value)
                            "creator" -> song.copy(artist = value)
                            else -> song.copy(durationMs = value.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                        } else if (tag == "title") title = value
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                if (parser.name == "track") { songs += song; inTrack = false; require(songs.size <= MAX_SONGS) }
                depth--
            }
            parser.next()
        }
        return listOf(ImportDocument(title, songs))
    }
    private fun json(data: String, fallback: String): List<ImportDocument> {
        var quoted = false; var escaped = false; var depth = 0; var tokens = 0
        for (c in data) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; tokens++; require(depth <= 40) { "Playlist JSON is too deeply nested." } }
                '}', ']' -> depth--
                ',' -> tokens++
            }
            require(tokens <= 100000) { "Playlist JSON contains too many entries." }
        }
        val root = if (data.startsWith('[')) JSONObject().put("tracks", JSONArray(data)) else JSONObject(data)
        val lists = root.optJSONArray("playlists") ?: JSONArray().put(root)
        require(lists.length() <= MAX_SONGS) { "Too many playlists in this file." }
        var total = 0
        return (0 until lists.length()).map { i ->
            val list = lists.getJSONObject(i)
            val entries = list.optJSONArray("tracks") ?: list.optJSONArray("items") ?: list.optJSONArray("songs") ?: JSONArray()
            total += entries.length(); require(total <= MAX_SONGS) { "Import at most $MAX_SONGS songs at a time." }
            val songs = (0 until entries.length()).mapNotNull { j ->
                val raw = entries.optJSONObject(j) ?: return@mapNotNull entries.optString(j).takeIf { it.isNotBlank() }?.let { ImportSong(location = it) }
                val item = raw.optJSONObject("track") ?: raw
                ImportSong(first(item, "url", "uri", "trackUri", "location", "path"), first(item, "trackName", "title", "name"),
                    first(item, "artistName", "artist", "creator").ifBlank { item.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name") ?: a.optString(it) } }.orEmpty() },
                    item.optLong("durationMs", item.optLong("duration_ms", 0)).coerceAtLeast(0))
            }
            ImportDocument(first(list, "title", "name").ifBlank { fallback }, songs)
        }
    }
    private fun first(o: JSONObject, vararg names: String) = names.firstNotNullOfOrNull { o.optString(it).takeUnless { v -> v.isBlank() || v == "null" } }.orEmpty()
    private fun csv(data: String, fallback: String): List<ImportDocument> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i = 0
        while (i < data.length) {
            val c = data[i++]
            when {
                c == '"' && quoted && i < data.length && data[i] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && c == '\n' -> { row += field.toString().trimEnd('\r'); rows += row; require(rows.size <= MAX_SONGS + 1) { "Too many CSV rows." }; row = mutableListOf(); field.clear() }
                else -> field.append(c)
            }
        }
        require(!quoted) { "The CSV contains an unclosed quoted field." }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString().trimEnd('\r'); rows += row }
        val headers = rows.firstOrNull()?.map { it.lowercase().filter(Char::isLetterOrDigit) }.orEmpty()
        fun value(row: List<String>, vararg names: String) = names.firstNotNullOfOrNull { n -> headers.indexOf(n).takeIf { it >= 0 }?.let { row.getOrNull(it) }?.takeIf { it.isNotBlank() } }.orEmpty()
        val groups = linkedMapOf<String, MutableList<ImportSong>>()
        for (r in rows.drop(1).filter { it.any(String::isNotBlank) }) {
            val title = value(r, "playlistname", "playlist").ifBlank { fallback }
            val rawDuration = value(r, "durationms", "durationinms", "trackdurationms")
            val duration = rawDuration.toLongOrNull() ?: value(r, "duration", "length").split(':').takeIf { it.all { p -> p.toLongOrNull() != null } }?.fold(0L) { total, p -> total * 60 + p.toLong() }?.times(1000) ?: 0
            groups.getOrPut(title) { mutableListOf() } += ImportSong(value(r, "trackuri", "spotifyuri", "trackurl", "url", "location", "path"),
                value(r, "trackname", "songname", "title", "name"), value(r, "artistnames", "artistname", "artists", "artist"), duration.coerceAtLeast(0))
        }
        return groups.map { ImportDocument(it.key, it.value) }
    }
}

data class PlaylistImportChoice(val id: String, val title: String, val provider: String)
data class PlaylistImportPreview(val title: String, val tracks: List<Track>)
data class PlaylistImportState(val visible: Boolean = false, val busy: Boolean = false, val saving: Boolean = false, val choices: List<PlaylistImportChoice> = emptyList(),
    val preview: List<PlaylistImportPreview> = emptyList(), val unavailable: Int = 0, val error: String? = null, val needsFolder: Boolean = false,
    val message: String = "Import a copy into Music. Your original playlists stay unchanged.")
