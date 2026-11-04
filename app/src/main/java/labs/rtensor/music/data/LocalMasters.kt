package labs.rtensor.music.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

/**
 * One audio file from the folder the user chose, with what the decoder will actually see:
 * channel count, sample rate, bit depth and codec.
 */
data class Master(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val channels: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val mime: String,
    val atmos: Boolean,
) {
    val lossless: Boolean get() = mime in LosslessMimes
    val multichannel: Boolean get() = channels > 2

    fun json(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("artist", artist)
        .put("durationMs", durationMs).put("channels", channels).put("sampleRate", sampleRate)
        .put("bitDepth", bitDepth).put("mime", mime).put("atmos", atmos)

    companion object {
        val LosslessMimes = setOf("audio/flac", "audio/alac", "audio/raw", "audio/x-wav", "audio/wav", "audio/true-hd", "audio/vnd.dts.hd", "audio/mlp")
        fun fromJson(o: JSONObject) = Master(
            o.getString("uri"), o.optString("title"), o.optString("artist"), o.optLong("durationMs"),
            o.optInt("channels"), o.optInt("sampleRate"), o.optInt("bitDepth"), o.optString("mime"), o.optString("mime") == "audio/eac3-joc",
        )
    }
}

/**
 * The user's own lossless and multichannel files, indexed from a folder they picked.
 * Nothing is downloaded: these are files the user already has on the device.
 */
class LocalMasters(private val context: Context) {
    private val file = File(context.filesDir, "masters.json")
    private val prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    @Volatile var entries: List<Master> = load(); private set
    var folder: String?
        get() = prefs.getString("masters_folder", null)
        set(value) { prefs.edit().putString("masters_folder", value).apply() }

    private fun load(): List<Master> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { Master.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    /** Walks the chosen folder (and its subfolders) and reads each audio file's real format. Call off the main thread. */
    fun index(tree: Uri, progress: (Int) -> Unit = {}): List<Master> {
        val found = mutableListOf<Master>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val mime = c.getString(1).orEmpty(); val name = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending.add(id)
                        mime.startsWith("audio/") || mime.startsWith("video/") || name.substringAfterLast('.').lowercase() in AudioExtensions ->
                            read(DocumentsContract.buildDocumentUriUsingTree(tree, id), name)?.let { found += it; progress(found.size) }
                    }
                }
            }
        }
        file.writeText(JSONArray().apply { found.forEach { put(it.json()) } }.toString())
        folder = tree.toString()
        entries = found
        return found
    }

    fun clear() { file.delete(); folder = null; entries = emptyList() }

    private fun read(uri: Uri, name: String): Master? {
        val extractor = MediaExtractor()
        val format = try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .filter { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
                .maxByOrNull { it.int(MediaFormat.KEY_CHANNEL_COUNT) }
        } catch (_: Exception) { null } finally { extractor.release() } ?: return null
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val bitDepth = when (format.int(MediaFormat.KEY_PCM_ENCODING, -1)) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> format.int("bits-per-sample")
        }
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(context, uri)
            val title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank) ?: name.substringBeforeLast('.', name)
            val artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            val retrievedDepth = if (android.os.Build.VERSION.SDK_INT >= 31) reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0 else 0
            // A filename or generic AC-4/E-AC-3 codec is not proof of Atmos.
            val atmos = mime == "audio/eac3-joc"
            Master(uri.toString(), title, artist,
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: (format.long(MediaFormat.KEY_DURATION) / 1000),
                format.int(MediaFormat.KEY_CHANNEL_COUNT), format.int(MediaFormat.KEY_SAMPLE_RATE), bitDepth.takeIf { it > 0 } ?: retrievedDepth, mime, atmos)
        } catch (_: Exception) { null } finally { reader.release() }
    }

    private fun MediaFormat.int(key: String, fallback: Int = 0) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
    private fun MediaFormat.long(key: String) = if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L

    companion object {
        private val AudioExtensions = setOf("flac", "alac", "m4a", "wav", "aiff", "aif", "mka", "mkv", "mp4", "ec3", "eac3", "ac3", "ac4", "thd", "dts")
    }
}

/** How a song will be played, in priority order: Atmos/multichannel, lossless, then YouTube. */
data class PlaybackRoute(val master: Master?) {
    val kind: Kind get() = when {
        master == null -> Kind.YOUTUBE
        master.atmos -> Kind.ATMOS
        master.multichannel -> Kind.MULTICHANNEL
        else -> Kind.LOSSLESS
    }
    enum class Kind { ATMOS, MULTICHANNEL, LOSSLESS, YOUTUBE }

    /** Short label for the player, e.g. "Atmos · 7.1 → DMR" or "Lossless 24/96 · FLAC → DMR". */
    val label: String get() {
        val m = master ?: return "YouTube"
        val rate = if (m.sampleRate % 1000 == 0) "${m.sampleRate / 1000}" else "%.1f".format(m.sampleRate / 1000f)
        val codec = m.mime.substringAfter('/').substringBefore(';').uppercase().replace("X-", "").replace("RAW", "PCM")
        return when (kind) {
            Kind.ATMOS -> "Atmos · ${layout(m.channels)} bed → DMR"
            Kind.MULTICHANNEL -> "${layout(m.channels)} · $codec $rate kHz → DMR"
            else -> "Lossless ${m.bitDepth.takeIf { it > 0 }?.let { "$it/" }.orEmpty()}$rate · $codec · stereo"
        }
    }

    private fun layout(channels: Int) = when (channels) { 6 -> "5.1"; 8 -> "7.1"; 10 -> "7.1.2"; 12 -> "7.1.4"; else -> "$channels ch" }

    companion object {
        /**
         * The best file the user owns for this song: Atmos first, then lossless
         * at the highest sample rate/bit depth, then other multichannel files.
         * Lossy files never outrank the YouTube stream.
         */
        fun choose(track: Track, masters: List<Master>): PlaybackRoute {
            if (masters.isEmpty() || track.source == Source.LOCAL) return PlaybackRoute(null)
            // The sink converts the 44.1 kHz family to the core's 48 kHz family.
            val best = masters.filter { (it.lossless || it.multichannel) && (!it.multichannel || (it.channels <= 16 && it.sampleRate in DmrRates)) && matches(track, it) }
                .maxWithOrNull(qualityOrder)
            return PlaybackRoute(best)
        }

        fun matches(track: Track, master: Master): Boolean {
            val title = clean(track.title); val other = clean(master.title)
            if (title.isBlank() || other.isBlank()) return false
            if (title != other) return false
            val artists = words(track.artist); val theirs = words(master.artist)
            if (artists.isEmpty() || theirs.isEmpty() || artists != theirs) return false
            // Different edits (radio, extended, live) share a title; the length tells them apart.
            if (track.durationMs > 0 && master.durationMs > 0 && abs(track.durationMs - master.durationMs) > 4_000) return false
            return true
        }

        private val DmrRates = setOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
        val qualityOrder = compareBy<Master>({ it.atmos }, { it.lossless && it.multichannel }, { it.lossless }, { it.sampleRate }, { it.bitDepth }, { it.multichannel }, { it.channels })
        private val Noise = Regex("""[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|hd|hq|4k|atmos|mv)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val Featuring = Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
        private val Edition = Regex("""live|remaster|clean|explicit|acoustic|instrumental|remix|edit""", RegexOption.IGNORE_CASE)
        fun clean(text: String): String {
            val plain = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            return Noise.replace(plain) { if (Edition.containsMatchIn(it.value)) it.value else " " }.replace(Featuring, " ").lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }
        private fun words(text: String) = clean(text.replace(Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE), ""))
            .split(' ').filter { it.isNotBlank() && it !in setOf("the", "and", "vevo") }.toSet()
    }
}
