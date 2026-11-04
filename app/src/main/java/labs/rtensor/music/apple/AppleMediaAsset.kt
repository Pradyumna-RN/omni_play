package labs.rtensor.music.apple

import androidx.media3.common.Format

/** Only our signed-in account resolver constructs this binding, never addon JSON. */
class AppleMediaAsset internal constructor(internal val songId: String, internal val playlist: ApplePlaylist, internal val format: Format,
    internal val cacheIdentity: String, internal val credentials: AppleCredentials, internal val nativeSession: String? = null,
    internal val nativeClient: AppleNativeAccess? = null, internal val prefetchKids: Set<String> = emptySet(),
    internal val isCurrent: () -> Boolean = { true }) {
    internal val native: Boolean get() = nativeSession != null && nativeClient != null
    override fun toString() = "AppleMediaAsset($songId, credentials=redacted)"
}

internal object AppleInitMetadata {
    private data class Box(val start: Int, val header: Int, val end: Int, val type: String)
    /** Apple multi-description audio uses description zero for the bootstrap prefix. */
    fun prefetchKids(bytes: ByteArray): Set<String> {
        fun boxes(start: Int, end: Int): List<Box> {
            val out = mutableListOf<Box>(); var p = start
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
            while (p < end) {
                require(end - p >= 8)
                val short = b.getInt(p).toLong() and 0xffffffffL
                val header = if (short == 1L) 16 else 8
                require(end - p >= header)
                val length = when (short) { 0L -> (end - p).toLong(); 1L -> b.getLong(p + 8); else -> short }
                require(length >= header && length <= end - p)
                out += Box(p, header, p + length.toInt(), String(bytes, p + 4, 4, Charsets.US_ASCII))
                p += length.toInt()
            }
            return out
        }
        val result = mutableSetOf<String>()
        fun findTenc(box: Box, depth: Int): String? {
            require(depth <= 16)
            if (box.type == "tenc") {
                val payload = box.start + box.header
                require(box.end - payload >= 24)
                if (bytes[payload + 6] != 1.toByte()) return null
                return bytes.copyOfRange(payload + 8, payload + 24).joinToString("") { "%02x".format(it) }
            }
            if (box.type !in setOf("sinf", "schi")) return null
            return boxes(box.start + box.header, box.end).firstNotNullOfOrNull { findTenc(it, depth + 1) }
        }
        fun visit(start: Int, end: Int, depth: Int) {
            require(depth <= 16)
            for (box in boxes(start, end)) {
                if (box.type in setOf("moov", "trak", "mdia", "minf", "stbl")) visit(box.start + box.header, box.end, depth + 1)
                if (box.type == "stsd") {
                    val payload = box.start + box.header
                    require(box.end - payload >= 8)
                    val count = java.nio.ByteBuffer.wrap(bytes, payload + 4, 4).int
                    if (count < 2) continue
                    require(count <= 16)
                    val descriptions = boxes(payload + 8, box.end)
                    require(descriptions.size == count)
                    val first = descriptions.first()
                    if (first.type !in setOf("enca", "alac", "ec-3", "mp4a")) continue
                    val base = first.start + first.header
                    require(first.end - base >= 28)
                    val version = ((bytes[base + 8].toInt() and 255) shl 8) or (bytes[base + 9].toInt() and 255)
                    val extra = when (version) { 0 -> 0; 1 -> 16; 2 -> 36; else -> error("Unsupported audio description") }
                    require(base + 28 + extra <= first.end)
                    boxes(base + 28 + extra, first.end).firstNotNullOfOrNull { findTenc(it, 0) }?.let(result::add)
                }
            }
        }
        require(bytes.size in 8..(1024 * 1024)); visit(0, bytes.size, 0)
        return result
    }
}
