package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.YouTubeRuntime
import labs.rtensor.music.updates.AppIdentity
import labs.rtensor.music.updates.GitHubUpdater
import labs.rtensor.music.updates.Version
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountAndUpdateTest {
    @Test fun sessionIsEncryptedSurvivesReloadAndRejectsTampering() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = AccountSession(context) { key }
        store.clear(); store.save("{\"access_token\":\"synthetic-test-token\"}")
        val raw = context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(raw.contains("synthetic-test-token"))
        assertEquals("{\"access_token\":\"synthetic-test-token\"}", AccountSession(context) { key }.load())
        context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE).edit().putString("credentials", raw.dropLast(8) + "AAAAAAAA").commit()
        assertNull(store.load()); assertFalse(context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE).contains("credentials"))
    }
    @Test fun networkBridgeRejectsOtherHostsCredentialsAndCleartext() {
        assertTrue(YouTubeRuntime.isProviderUrl("https://music.youtube.com/youtubei/v1/browse"))
        listOf("https://youtube.com.attacker.test/x", "https://user@www.youtube.com/x", "file:///etc/passwd", "http://www.youtube.com/x", "https://www.youtube.com:444/x").forEach { assertFalse(it, YouTubeRuntime.isProviderUrl(it)) }
        assertTrue(YouTubeRuntime.isVerificationUrl("https://www.google.com/device"))
        assertFalse(YouTubeRuntime.isVerificationUrl("https://accounts.google.com.attacker.test/device"))
    }
    @Test fun versionOrderingHandlesPatchNumbersAndPreviewBuilds() {
        assertTrue(Version.parse("v0.10.0")!! > Version.parse("0.2.0")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("1.0.0-rc.2")!!)
        assertTrue(Version.parse("1.0.0-rc.10")!! > Version.parse("1.0.0-rc.2")!!)
        assertNull(Version.parse("nightly"))
    }
    @Test fun updatesRejectOtherAppsDowngradesAndDifferentCertificates() {
        val installed = AppIdentity("labs.rtensor.music", 2, setOf("cert-one"))
        GitHubUpdater.verifyIdentity(installed, installed.copy(versionCode = 3))
        listOf(installed.copy(versionCode = 1), installed.copy(packageName = "another.app", versionCode = 3), installed.copy(signers = setOf("cert-two"), versionCode = 3)).forEach {
            assertTrue(runCatching { GitHubUpdater.verifyIdentity(installed, it) }.isFailure)
        }
    }
    @Test fun checksumMustNameTheExactApkAndReleaseUrlMustBelongToOurRepo() {
        val hash = "a".repeat(64)
        assertEquals(hash, GitHubUpdater.checksumFor("$hash  music-debug.apk\n", "music-debug.apk"))
        assertNull(GitHubUpdater.checksumFor("$hash  other.apk", "music-debug.apk"))
        assertTrue(GitHubUpdater.isReleaseAsset("https://github.com/RTensorLabs/music-app/releases/download/v0.3.0/music-debug.apk"))
        assertFalse(GitHubUpdater.isReleaseAsset("https://github.com/another/app/releases/download/v0.3.0/music-debug.apk"))
        assertFalse(GitHubUpdater.isReleaseAsset("https://github.com@attacker.test/RTensorLabs/music-app/releases/download/v0.3.0/a.apk"))
    }
    @Test fun updateSelectionIgnoresDraftsAndUsesNumericVersionOrder() {
        fun release(tag: String, draft: Boolean = false): JSONObject {
            fun asset(name: String) = JSONObject().put("name", name).put("size", 100)
                .put("browser_download_url", "https://github.com/RTensorLabs/music-app/releases/download/$tag/$name")
            return JSONObject().put("tag_name", tag).put("draft", draft)
                .put("assets", JSONArray().put(asset("music-debug.apk")).put(asset("SHA256SUMS")))
        }
        val list = JSONArray().put(release("v0.3.0")).put(release("v0.10.0")).put(release("v9.0.0", true))
        assertEquals("0.10.0", GitHubUpdater.selectRelease(list, "0.2.0")?.version)
    }
}
