package labs.rtensor.music.apple

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** One-time, local import of compatible runtime files. These files are never in our APK. */
internal class AppleRuntimeFiles(private val context: Context) {
    private val profileText by lazy { context.assets.open("apple/runtime-profile.json").bufferedReader().use { it.readText() } }
    private val profile by lazy { JSONObject(profileText) }
    private val profileId by lazy { sha(profileText.toByteArray()).take(20) }
    val arch: String get() = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "x86_64") }
        ?: error("The Apple runtime requires a supported 64-bit Android device.")
    private fun expected(): Map<String, String> = profile.getJSONObject("libs").getJSONObject(arch).let { json ->
        json.keys().asSequence().associateWith { json.getString(it) }
    }
    val directory: File get() = File(context.filesDir, "apple-runtime/$profileId/$arch")
    fun installed(): Boolean = runCatching { File(directory, "verified").readText() == profileId && expected().keys.all { File(directory, it).isFile } }.getOrDefault(false)
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val root = File(context.filesDir, "apple-runtime").apply { mkdirs() }
        val pending = File(root, "pending-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            context.contentResolver.openInputStream(uri)?.use { readPackage(it, pending, expected(), arch) { job.ensureActive() } }
                ?: error("The installer could not be opened.")
            expected().forEach { (name, digest) -> require(File(pending, name).isFile && sha(File(pending, name)) == digest) {
                "This installer is not compatible with the native adapter. It needs Apple Music 3.6.0-beta, build 1109, with $arch libraries."
            } }
            loadOrder(pending, expected().keys)
            File(pending, "verified").writeText(profileId)
            val target = directory
            job.ensureActive()
            target.parentFile?.mkdirs()
            if (target.exists()) {
                require(verify()) { "An existing runtime installation could not be verified." }
            } else check(pending.renameTo(target)) { "The runtime could not be installed." }
        } finally { pending.deleteRecursively() }
    }
    fun verify(): Boolean = runCatching {
        installed() && expected().all { (name, digest) -> sha(File(directory, name)) == digest }
    }.getOrDefault(false)
    fun order(): Array<String> {
        check(verify()) { "Import a compatible Apple Music installer first." }
        return loadOrder(directory, expected().keys).toTypedArray()
    }
    companion object {
        private const val MAX_IMAGE = 64 * 1024 * 1024
        private const val MAX_TOTAL = 384L * 1024 * 1024
        private val publicLibraries = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libOpenSLES.so",
            "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "libz.so", "libstdc++.so",
            "libOpenMAXAL.so", "libmediandk.so", "libcamera2ndk.so", "libvulkan.so", "libnativewindow.so", "libaaudio.so",
            "libamidi.so", "libbinder_ndk.so", "libneuralnetworks.so", "libsync.so")
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun readPackage(input: InputStream, directory: File, expected: Map<String, String>, arch: String, checkActive: () -> Unit = {}) {
            var total = 0L; var packages = 0
            fun bounded(stream: InputStream, max: Int): ByteArray {
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
                while (true) {
                    checkActive()
                    val count = stream.read(buffer); if (count < 0) break
                    total += count
                    require(total <= MAX_TOTAL && out.size().toLong() + count <= max) { "The installer exceeds its size limit." }
                    out.write(buffer, 0, count)
                }
                return out.toByteArray()
            }
            fun apk(stream: InputStream, allowNested: Boolean) {
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.removePrefix("lib/$arch/")
                        when {
                            entry.name.startsWith("lib/$arch/") && name in expected -> {
                                val bytes = bounded(zip, MAX_IMAGE)
                                require(sha(bytes) == expected[name]) { "The installer does not match the supported Apple runtime." }
                                val output = File(directory, name)
                                if (!output.exists()) {
                                    output.outputStream().use { target -> check(output.setReadOnly()); target.write(bytes) }
                                }
                            }
                            allowNested && entry.name.endsWith(".apk", true) -> {
                                require(++packages <= 32) { "Too many split packages." }
                                val bytes = bounded(zip, 128 * 1024 * 1024)
                                apk(ByteArrayInputStream(bytes), false)
                            }
                            else -> {
                                val discard = ByteArray(65536)
                                while (true) {
                                    checkActive()
                                    val count = zip.read(discard); if (count < 0) break
                                    total += count; require(total <= MAX_TOTAL) { "The installer exceeds its size limit." }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            apk(input, true)
        }
        private fun loadOrder(root: File, names: Set<String>): List<String> {
            val dependencies = names.associateWith { needed(File(root, it).readBytes()) }
            val result = mutableListOf<String>(); val visiting = mutableSetOf<String>()
            fun visit(name: String) {
                if (name in result) return
                require(visiting.add(name)) { "The runtime has an unsupported native dependency cycle." }
                dependencies.getValue(name).forEach { dependency ->
                    if (dependency in names) visit(dependency)
                    else require(dependency in publicLibraries) { "The runtime needs an unsupported Android library: $dependency" }
                }
                visiting.remove(name); result += name
            }
            names.sorted().forEach(::visit)
            return result
        }
        internal fun needed(bytes: ByteArray): List<String> {
            require(bytes.size >= 64 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) && bytes[4] == 2.toByte() && bytes[5] == 1.toByte())
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val offset = b.getLong(32); val size = b.getShort(54).toInt() and 65535; val count = b.getShort(56).toInt() and 65535
            require(offset >= 64 && size == 56 && count in 1..256 && offset + size.toLong() * count <= bytes.size)
            data class Segment(val type: Int, val offset: Long, val address: Long, val length: Long)
            val segments = (0 until count).map { i ->
                val p = (offset + i * size).toInt()
                Segment(b.getInt(p), b.getLong(p + 8), b.getLong(p + 16), b.getLong(p + 32)).also {
                    require(it.offset >= 0 && it.length >= 0 && it.offset <= bytes.size && it.length <= bytes.size - it.offset)
                }
            }
            val dynamic = segments.singleOrNull { it.type == 2 } ?: return emptyList()
            require(dynamic.length % 16 == 0L && dynamic.length / 16 <= 4096)
            var stringAddress = -1L; var stringLength = -1L; val offsets = mutableListOf<Long>(); var terminated = false
            for (i in 0 until (dynamic.length / 16).toInt()) {
                val p = (dynamic.offset + i * 16).toInt(); val tag = b.getLong(p); val value = b.getLong(p + 8)
                if (tag == 0L) { terminated = true; break }
                when (tag) { 1L -> offsets += value; 5L -> stringAddress = value; 10L -> stringLength = value }
            }
            require(terminated)
            if (offsets.isEmpty()) return emptyList()
            require(stringLength in 1..MAX_IMAGE.toLong())
            val load = segments.firstOrNull { it.type == 1 && stringAddress >= it.address && stringAddress - it.address < it.length }
                ?: error("Invalid runtime string table")
            val start = load.offset + stringAddress - load.address
            require(start >= 0 && start <= bytes.size && stringLength <= bytes.size - start && stringLength <= load.length - (stringAddress - load.address))
            return offsets.map { relative ->
                require(relative in 0 until stringLength)
                val from = (start + relative).toInt(); val limit = (start + stringLength).toInt()
                var end = from
                while (end < limit && end - from <= 128 && bytes[end] != 0.toByte()) end++
                require(end < limit && end - from in 1..128 && bytes[end] == 0.toByte())
                String(bytes, from, end - from, Charsets.UTF_8).also { require(it.matches(Regex("lib[A-Za-z0-9_.+-]+\\.so"))) }
            }
        }
    }
}
