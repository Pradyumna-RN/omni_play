package labs.rtensor.music.apple

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Media3's encryption prefix is removed only after a complete sample succeeds. */
@UnstableApi
internal object AppleSample {
    private data class Span(val start: Int, val size: Int)
    fun decode(input: ByteArray, crypto: TrackOutput.CryptoData, bootstrap: Boolean,
        decrypt: (ByteArray) -> ByteArray): ByteArray {
        require(input.size in 2..262144 && crypto.cryptoMode == C.CRYPTO_MODE_AES_CBC)
        val b = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        val signal = b.get().toInt() and 255
        val ivSize = signal and 127
        require(ivSize == 8 || ivSize == 16)
        require(b.remaining() >= ivSize)
        val iv = ByteArray(16); b.get(iv, 0, ivSize)
        val spans = mutableListOf<Span>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        if (signal and 128 != 0) {
            require(b.remaining() >= 2)
            val count = b.short.toInt() and 65535
            require(count in 1..4096 && b.remaining() >= count * 6)
            repeat(count) {
                val clear = b.short.toInt() and 65535
                val encrypted = b.int.toLong() and 0xffffffffL
                require(encrypted <= 262144)
                ranges += clear to encrypted.toInt()
            }
        }
        val data = input.copyOfRange(b.position(), input.size)
        if (ranges.isEmpty()) ranges += 0 to data.size
        var position = 0
        for ((clear, encrypted) in ranges) {
            require(clear <= data.size - position); position += clear
            require(encrypted <= data.size - position)
            spans += Span(position, encrypted); position += encrypted
        }
        require(position == data.size)
        val pattern = crypto.encryptedBlocks > 0 && crypto.clearBlocks > 0
        require(crypto.clearBlocks == 0 || crypto.encryptedBlocks > 0)
        require(bootstrap || !pattern) { "This Apple native audio pattern is not supported." }
        if (bootstrap && pattern) {
            for (span in spans) bootstrap(data, span.start, span.size, iv, crypto.encryptedBlocks, crypto.clearBlocks)
        } else {
            // Keep clear bytes and each region's partial block exactly as received.
            val encrypted = ByteArrayOutputStream()
            spans.forEach { encrypted.write(data, it.start, it.size / 16 * 16) }
            val packed = encrypted.toByteArray()
            require(packed.size <= 131072)
            if (packed.isNotEmpty()) {
                val plain = if (bootstrap) packed.copyOf().also { bootstrap(it, 0, it.size, iv, 0, 0) } else decrypt(packed)
                require(plain.size == packed.size)
                var offset = 0
                spans.forEach { span -> val count = span.size / 16 * 16; plain.copyInto(data, span.start, offset, offset + count); offset += count }
                plain.fill(0)
            }
        }
        return data
    }
    private fun bootstrap(data: ByteArray, start: Int, size: Int, iv: ByteArray, crypt: Int, skip: Int) {
        // Public Apple bootstrap description; subscriber content uses the imported SDK.
        val key = byteArrayOf(0x32, 0xb8.toByte(), 0xad.toByte(), 0xe1.toByte(), 0x76, 0x9e.toByte(), 0x26, 0xb1.toByte(),
            0xff.toByte(), 0xb8.toByte(), 0x98.toByte(), 0x63, 0x52, 0x79, 0x3f, 0xc6.toByte())
        decryptCbc(data, start, size, key, iv, crypt, skip)
    }
    internal fun decryptCbc(data: ByteArray, start: Int, size: Int, key: ByteArray, iv: ByteArray, crypt: Int, skip: Int) {
        require(start >= 0 && size >= 0 && start <= data.size - size && crypt in 0..15 && skip in 0..15)
        val pattern = crypt > 0 && skip > 0
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        var p = start; val end = start + size
        while (end - p >= 16) {
            val count = minOf(if (pattern) crypt * 16 else end - p, (end - p) / 16 * 16)
            val plain = cipher.update(data, p, count)
            plain.copyInto(data, p); p += count
            if (pattern) p += minOf(skip * 16, end - p)
        }
        require(cipher.doFinal().isEmpty())
    }
}
