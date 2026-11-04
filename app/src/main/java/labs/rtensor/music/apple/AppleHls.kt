package labs.rtensor.music.apple

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.PsshAtomUtil
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class AppleVariant(val url: String, val codec: String, val group: String,
    val rate: Int = 0, val depth: Int = 0, val bandwidth: Long = 0)
internal data class AppleSegment(val url: String, val keyUri: String?, val keyFormat: String?,
    val rangeStart: Long?, val rangeLength: Long?, val initialization: Boolean)
internal data class ApplePlaylist(val text: String, val entries: List<AppleSegment>, val webKeyUri: String? = null,
    val webPssh: ByteArray? = null) {
    val initialization: AppleSegment get() = entries.firstOrNull { it.initialization }
        ?: error("Apple did not provide an MP4 initialization segment.")
}

/** Bounded HLS metadata handling. Encoded media bytes are never rewritten. */
@UnstableApi
internal object AppleHls {
    const val WIDEVINE = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    const val FAIRPLAY = "com.apple.streamingkeydelivery"
    private val widevineId = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    fun attributes(line: String): Map<String, String> {
        val source = line.substringAfter(':', "")
        require(source.length <= 16384)
        val out = linkedMapOf<String, String>(); var position = 0
        while (position < source.length) {
            while (position < source.length && source[position].isWhitespace()) position++
            val equals = source.indexOf('=', position)
            require(equals > position)
            val key = source.substring(position, equals).trim()
            require(key.matches(Regex("[A-Z0-9-]+")) && key !in out)
            position = equals + 1
            val value: String
            if (position < source.length && source[position] == '"') {
                val end = source.indexOf('"', position + 1); require(end >= 0)
                value = source.substring(position + 1, end); position = end + 1
            } else {
                val end = source.indexOf(',', position).takeIf { it >= 0 } ?: source.length
                value = source.substring(position, end).trim(); position = end
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
            out[key] = value
            if (position < source.length) { require(source[position] == ','); position++ }
        }
        return out
    }
    private fun lines(text: String): List<String> {
        require(text.length <= 512 * 1024)
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull() == "#EXTM3U" && lines.size <= 16384)
        return lines
    }
    private fun mediaUrl(base: String, value: String): String = base.toHttpUrl().resolve(value)?.toString()
        ?.takeIf(AppleOrigins::isMedia) ?: error("Apple supplied an unsupported media address.")
    fun variants(text: String, base: String): List<AppleVariant> {
        val source = lines(text)
        val audio = source.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes).filter { it["TYPE"] == "AUDIO" && it["URI"] != null }
        val out = mutableListOf<AppleVariant>()
        var pending: Map<String, String>? = null
        for (line in source) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) pending = attributes(line)
            else if (!line.startsWith('#') && pending != null) {
                val a = pending; pending = null
                val group = a["AUDIO"].orEmpty()
                val codec = a["CODECS"].orEmpty().split(',').firstOrNull { it == "alac" || it == "ec-3" || it == "ec+3" || it.startsWith("mp4a.") } ?: continue
                val video = a["CODECS"].orEmpty().split(',').any { it.startsWith("avc") || it.startsWith("hvc") || it.startsWith("hev") }
                val choices = audio.filter { it["GROUP-ID"] == group }
                val urls = if (choices.isNotEmpty()) choices.map { it.getValue("URI") } else if (!video) listOf(line) else emptyList()
                val quality = Regex("(?:stereo|surround)-(\\d{4,6})-(\\d{1,2})").find(group)
                val rate = a["SAMPLE-RATE"]?.toIntOrNull() ?: quality?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val depth = a["BIT-DEPTH"]?.toIntOrNull() ?: quality?.groupValues?.get(2)?.toIntOrNull() ?: 0
                urls.forEach { out += AppleVariant(mediaUrl(base, it), codec, group, rate, depth, a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: a["BANDWIDTH"]?.toLongOrNull() ?: 0) }
            }
        }
        return out.distinctBy { it.url }.take(32)
    }
    private fun range(value: String?, previous: Long?): Pair<Long?, Long?> {
        if (value == null) return null to null
        val pieces = value.split('@'); require(pieces.size in 1..2)
        val length = pieces[0].toLong(); val start = pieces.getOrNull(1)?.toLong() ?: previous ?: 0L
        require(length in 1..(1024L * 1024 * 1024) && start >= 0 && start <= Long.MAX_VALUE - length)
        return start to length
    }
    fun playlist(text: String, base: String, native: Boolean): ApplePlaylist {
        val source = lines(text); val output = mutableListOf<String>(); val entries = mutableListOf<AppleSegment>()
        require("#EXT-X-ENDLIST" in source) { "Only complete Apple audio recordings are supported." }
        var keyUri: String? = null; var keyFormat: String? = null; var webUri: String? = null; var pssh: ByteArray? = null
        var nextRange: String? = null; var nextOffset: Long? = null; var previousUrl: String? = null
        fun entry(uri: String, rangeValue: String?, init: Boolean): String {
            val url = mediaUrl(base, uri)
            val (start, length) = range(rangeValue, if (previousUrl == url) nextOffset else null)
            if (!init) { previousUrl = url; nextOffset = if (start != null && length != null) start + length else null }
            entries += AppleSegment(url, if (init) null else keyUri, if (init) null else keyFormat, start, length, init)
            return "music-apple://stream/${entries.lastIndex}"
        }
        for (line in source) when {
            line.startsWith("#EXT-X-KEY:") -> {
                val a = attributes(line)
                if (a["METHOD"] == "NONE") { keyUri = null; keyFormat = null; if (!native) output += line }
                else if (native && a["KEYFORMAT"] == FAIRPLAY) {
                    require(a["METHOD"] == "SAMPLE-AES" && AppleOrigins.nativeKey(a["URI"].orEmpty()))
                    keyUri = a.getValue("URI"); keyFormat = FAIRPLAY
                } else if (!native && a["KEYFORMAT"] == WIDEVINE) {
                    require(a["METHOD"] in setOf("SAMPLE-AES-CTR", "SAMPLE-AES-CENC", "SAMPLE-AES"))
                    val uri = a.getValue("URI"); val header = widevinePssh(uri)
                    require(webUri == null || webUri == uri) { "This Apple AAC recording rotates license identifiers and is not supported yet." }
                    webUri = uri; pssh = header; keyUri = uri; keyFormat = WIDEVINE
                    val value = Base64.encodeToString(header, Base64.NO_WRAP)
                    output += "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,$value\",KEYFORMAT=\"$WIDEVINE\",KEYFORMATVERSIONS=\"1\""
                } else if (a["KEYFORMAT"] !in setOf(FAIRPLAY, WIDEVINE)) {
                    error("Apple supplied an unsupported audio encryption format.")
                }
            }
            line.startsWith("#EXT-X-MAP:") -> {
                val a = attributes(line); val url = entry(a.getValue("URI"), a["BYTERANGE"], true)
                output += "#EXT-X-MAP:URI=\"$url\"${a["BYTERANGE"]?.let { ",BYTERANGE=\"$it\"" }.orEmpty()}"
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> { nextRange = line.substringAfter(':'); output += line }
            line.startsWith("#EXT-X-SESSION-KEY:") -> Unit
            !line.startsWith('#') -> { output += entry(line, nextRange, false); nextRange = null }
            else -> output += line
        }
        require(entries.any { !it.initialization } && entries.size <= 8192)
        if (native) require(entries.any { !it.initialization && it.keyFormat == FAIRPLAY && it.keyUri != null }) { "Apple did not provide a supported native audio key." }
        else require(webUri != null && pssh != null) { "Apple did not provide a Widevine audio stream." }
        return ApplePlaylist(output.joinToString("\n", postfix = "\n"), entries, webUri, pssh)
    }
    fun widevinePssh(uri: String): ByteArray {
        require(uri.length <= 16384 && uri.startsWith("data:"))
        val data = Base64.decode(uri.substringAfter(',', ""), Base64.DEFAULT)
        if (PsshAtomUtil.isPsshAtom(data)) return data.also { require(PsshAtomUtil.parseUuid(it) == widevineId) }
        val kid = if (data.size == 16) data else {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val value = json.optString("keyId").ifBlank { json.optString("keyID") }.ifBlank { json.optString("kid") }
                .ifBlank { json.optJSONArray("key-ids")?.optString(0).orEmpty() }
            val clean = value.replace("-", "")
            if (clean.matches(Regex("[a-fA-F0-9]{32}"))) clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else Base64.decode(value, Base64.DEFAULT)
        }
        require(kid.size == 16 && kid.any { it != 0.toByte() }) { "Apple did not provide a valid encryption identifier." }
        return PsshAtomUtil.buildPsshAtom(widevineId, byteArrayOf(0x08, 0x01, 0x12, 0x10) + kid)
    }
    fun probe(bytes: ByteArray): Format {
        require(bytes.size in 8..(1024 * 1024))
        val data = ByteArrayDataSource(bytes)
        data.open(DataSpec(android.net.Uri.parse("memory://apple-init")))
        val input = DefaultExtractorInput(data, 0, bytes.size.toLong())
        var format: Format? = null
        val extractor = FragmentedMp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) object : TrackOutput by DummyTrackOutput() {
                override fun format(value: Format) { check(format == null) { "Multiple Apple audio tracks are unsupported." }; format = value }
            } else DummyTrackOutput()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try {
            val holder = PositionHolder()
            repeat(4096) {
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return format ?: throw IOException("Apple audio format was not found.")
            }
            throw IOException("Apple initialization parsing exceeded its limit.")
        } finally { extractor.release(); data.close() }
    }
}
