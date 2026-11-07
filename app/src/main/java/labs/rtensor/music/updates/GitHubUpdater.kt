package labs.rtensor.music.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labs.rtensor.music.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(val version: String, val apkUrl: String, val checksumUrl: String, val apkName: String, val bytes: Long)
data class UpdateState(val checking: Boolean = false, val downloading: Boolean = false,
    val progress: Int = 0, val release: AppRelease? = null, val apkPath: String? = null, val message: String = "")
/** A completed check. A verified download of the same release stays installable instead of being downloaded again. */
fun UpdateState.checked(found: AppRelease?, fileExists: (String) -> Boolean = { File(it).isFile }): UpdateState {
    val ready = apkPath?.takeIf { found != null && found == release && fileExists(it) }
    return UpdateState(release = found, apkPath = ready, message = when {
        found == null -> "You’re up to date."
        ready != null -> "Verified update. Ready to install."
        else -> "Version ${found.version} is available."
    })
}

/** Messages the updater wrote for people are kept; library and network internals are not shown. */
fun updateFailureMessage(error: Exception, fallback: String): String = when (error) {
    is java.io.IOException -> "Could not reach GitHub. Check your connection and try again."
    is IllegalStateException -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    else -> fallback
}

data class AppIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

class GitHubUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/$REPOSITORY/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "RTensorLabs-Music/${BuildConfig.VERSION_NAME}").build()
        val json = client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "GitHub update check failed (HTTP ${r.code}). Try again later." }
            require((r.body?.contentLength() ?: 0) <= 2_000_000)
            JSONArray(r.body!!.string())
        }
        selectRelease(json, BuildConfig.VERSION_NAME)
    }

    suspend fun download(release: AppRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        require(isReleaseAsset(release.apkUrl) && isReleaseAsset(release.checksumUrl))
        val sum = client.newCall(Request.Builder().url(release.checksumUrl).build()).execute().use { r ->
            check(r.isSuccessful) { "Could not load update checksums (HTTP ${r.code})." }
            require((r.body?.contentLength() ?: 0) <= 64_000)
            checksumFor(r.body!!.string(), release.apkName) ?: error("The release has no valid APK checksum.")
        }
        require(release.bytes in 1..200_000_000)
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(folder, "music-update.part.apk")
        val target = File(folder, "music-update.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                check(r.isSuccessful) { "APK download failed (HTTP ${r.code})." }
                var total = 0L
                r.body!!.byteStream().use { input -> partial.outputStream().use { out ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= release.bytes) { "The update size does not match the release." }
                        out.write(buffer, 0, n); digest.update(buffer, 0, n)
                        progress((total * 100 / release.bytes).toInt())
                    }
                } }
                check(total == release.bytes) { "The update download was incomplete. Retry the download." }
            }
            check(digest.digest().hex() == sum) { "The update checksum did not match. The download was discarded." }
            verifyApk(partial)
            target.delete(); check(partial.renameTo(target)) { "Could not save the verified update." }
            target
        } catch (e: Exception) { partial.delete(); throw e }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("The downloaded file is not a valid Android package.")
        fun identity(info: android.content.pm.PackageInfo): AppIdentity {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return AppIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                certificates.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet())
        }
        verifyIdentity(identity(installed), identity(candidate))
    }

    companion object {
        const val REPOSITORY = "RTensorLabs/music-app"
        private const val PREFIX = "https://github.com/$REPOSITORY/releases/download/"
        fun isReleaseAsset(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            value.startsWith(PREFIX) && uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null
        }.getOrDefault(false)
        fun checksumFor(text: String, name: String): String? = text.lineSequence().mapNotNull {
            Regex("^([A-Fa-f0-9]{64})\\s+\\*?([A-Za-z0-9._-]+)$").matchEntire(it.trim())
        }.firstOrNull { it.groupValues[2] == name }?.groupValues?.get(1)?.lowercase()

        private val APK_NAMES = listOf("Hum.apk", "music-debug.apk", "music-release.apk")

        fun selectRelease(releases: JSONArray, installed: String): AppRelease? {
            val current = Version.parse(installed) ?: return null
            return (0 until releases.length()).mapNotNull { i ->
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) return@mapNotNull null
                val tag = r.optString("tag_name"); val version = Version.parse(tag) ?: return@mapNotNull null
                if (version <= current) return@mapNotNull null
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val files = (0 until assets.length()).map { assets.getJSONObject(it) }
                // Releases carry the app's own name (Hum.apk); older ones used music-debug.apk / music-release.apk.
                val apk = APK_NAMES.firstNotNullOfOrNull { wanted -> files.firstOrNull { it.optString("name") == wanted } } ?: return@mapNotNull null
                val sums = files.firstOrNull { it.optString("name") == "SHA256SUMS" } ?: return@mapNotNull null
                if (!isReleaseAsset(apk.optString("browser_download_url")) || !isReleaseAsset(sums.optString("browser_download_url"))) return@mapNotNull null
                version to AppRelease(tag.removePrefix("v"), apk.getString("browser_download_url"), sums.getString("browser_download_url"), apk.getString("name"), apk.getLong("size"))
            }.maxByOrNull { it.first }?.second
        }
        fun verifyIdentity(installed: AppIdentity, candidate: AppIdentity) {
            check(candidate.packageName == installed.packageName) { "This APK belongs to a different app." }
            check(candidate.versionCode > installed.versionCode) { "This APK is not a newer version." }
            check(installed.signers.isNotEmpty() && candidate.signers == installed.signers) { "This APK has a different signing certificate. It cannot update this installation." }
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String = "") : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for ((a, b) in listOf(major to other.major, minor to other.minor, patch to other.patch)) if (a != b) return a.compareTo(b)
        if (pre == other.pre) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val a = pre.split('.'); val b = other.pre.split('.')
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] == b[i]) continue
            val x = a[i].toIntOrNull(); val y = b[i].toIntOrNull()
            return if (x != null && y != null) x.compareTo(y) else if (x != null) -1 else if (y != null) 1 else a[i].compareTo(b[i])
        }
        return a.size.compareTo(b.size)
    }
    companion object {
        fun parse(value: String): Version? {
            val m = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.-]+))?(?:\\+[A-Za-z0-9.-]+)?$").matchEntire(value) ?: return null
            return runCatching { Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4]) }.getOrNull()
        }
    }
}
