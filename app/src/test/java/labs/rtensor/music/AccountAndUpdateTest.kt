package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.YouTubeRuntime
import labs.rtensor.music.data.WebSession
import labs.rtensor.music.data.ProviderTransport
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
        assertFalse(YouTubeRuntime.isProviderUrl("https://oauth2.googleapis.com/token"))
        assertTrue(ProviderTransport.isMediaUrl("https://r1-test.googlevideo.com/videoplayback?secret=redacted"))
        assertFalse(ProviderTransport.isMediaUrl("https://googlevideo.com.attacker.test/videoplayback"))
        assertTrue(ProviderTransport.isProofUrl("https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT"))
        assertFalse(ProviderTransport.isProofUrl("https://music.youtube.com/youtubei/v1/browse"))
    }
    @Test fun accountCookiesAndAuthorizationCannotReachMediaOrTheProofSandbox() {
        val session = WebSession("SAPISID=synthetic-session", "visitor")
        val headers = JSONObject().put("cookie", "injected-cookie").put("authorization", "SAPISIDHASH synthetic-auth").put("x-goog-authuser", "1").put("range", "bytes=0-1")
        val options = JSONObject().put("method", "GET").put("headers", headers).toString()
        val youtube = ProviderTransport().request("https://music.youtube.com/youtubei/v1/browse", options, "real-browser-agent", session)
        assertEquals(session.cookie, youtube.header("Cookie"))
        assertEquals("SAPISIDHASH synthetic-auth", youtube.header("Authorization"))
        val media = ProviderTransport().request("https://r1-test.googlevideo.com/videoplayback", options, "real-browser-agent", session)
        val proof = ProviderTransport(proofOnly = true).request("https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT", options, "real-browser-agent", session)
        listOf(media, proof).forEach { request ->
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization")); assertNull(request.header("X-Goog-Authuser"))
        }
        assertTrue(runCatching { ProviderTransport().request("https://r1-test.googlevideo.com/videoplayback", "{}", "ua", session) }.isFailure)
        assertTrue(runCatching { ProviderTransport(proofOnly = true).request("https://music.youtube.com/", options, "ua", session) }.isFailure)
    }
    @Test fun webSessionNeedsAnActualYoutubeCookieAndDoesNotAcceptLegacyTvTokens() {
        val session = JSONObject().put("kind", "youtube-web").put("cookie", "SAPISID=synthetic-session; SID=synthetic-id").put("visitorData", "test-visitor").put("authUser", 1)
        assertEquals(1, WebSession.parse(session).authUser)
        assertEquals("synthetic-session", WebSession.parse(session).cookie.substringAfter("SAPISID=").substringBefore(';'))
        assertTrue(runCatching { WebSession.parse(JSONObject().put("access_token", "legacy-tv-token")) }.isFailure)
        assertTrue(runCatching { WebSession.parse(JSONObject(session.toString()).put("cookie", "SID=only-id")) }.isFailure)
        assertTrue(runCatching { WebSession.parse(JSONObject(session.toString()).put("cookie", "SAPISID=a\r\nCookie: injected")) }.isFailure)
        assertTrue(runCatching { WebSession.parse(JSONObject(session.toString()).put("authUser", 100)) }.isFailure)
    }
    @Test fun capturedPlayerMetadataRoundTripsWithoutAcceptingUntrustedScriptUrls() {
        val json = WebSession("SAPISID=fixture-cookie", "visitor", playerScript = "/s/player/fixture/player_ias.vflset/en_US/base.js", musicContentBoundGvs = false).json()
        val parsed = WebSession.parse(json)
        assertEquals(false, parsed.musicContentBoundGvs)
        assertEquals("/s/player/fixture/player_ias.vflset/en_US/base.js", parsed.playerScript)
        assertEquals(parsed, WebSession.parse(parsed.json()))
        assertNull(WebSession.parse(JSONObject().put("kind", "youtube-web").put("cookie", "SAPISID=fixture").put("visitorData", "visitor")).musicContentBoundGvs)
        listOf("https://attacker.test/s/player/a/base.js", "https://user@www.youtube.com/s/player/a/base.js", "http://www.youtube.com/s/player/a/base.js", "https://www.youtube.com/watch?v=invalid").forEach { url ->
            assertTrue(url, runCatching { WebSession.parse(JSONObject(json.toString()).put("playerScript", url)) }.isFailure)
        }
        assertTrue(runCatching { WebSession.parse(JSONObject(json.toString()).put("musicContentBoundGvs", "false")) }.isFailure)
    }
    @Test fun loginOnlyConfirmsYoutubeMusicAndCannotNavigateToAnImpersonatedOrigin() {
        assertTrue(WebSession.canConfirm("https://music.youtube.com/"))
        assertFalse(WebSession.canConfirm("https://accounts.google.com/"))
        assertTrue(WebSession.isSignInNavigation("https://accounts.google.com/ServiceLogin"))
        // Regional cookie hand-off after 2-Step Verification (reported stuck in India).
        assertTrue(WebSession.isSignInNavigation("https://accounts.google.co.in/accounts/SetSID?ssdc=1"))
        assertTrue(WebSession.isSignInNavigation("https://www.google.co.in/accounts/SetSID"))
        assertTrue(WebSession.isSignInNavigation("https://accounts.google.com.br/accounts/SetSID"))
        assertFalse(WebSession.isSignInNavigation("https://www.google.co.in/search?q=x"))
        assertFalse(WebSession.isSignInNavigation("https://accounts.google.co.in.attacker.test/accounts/SetSID"))
        listOf("https://music.youtube.com.attacker.test/", "https://user@music.youtube.com/", "http://music.youtube.com/", "intent://login", "https://accounts.google.com:444/").forEach {
            assertFalse(WebSession.canConfirm(it)); assertFalse(WebSession.isSignInNavigation(it))
        }
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
    @Test fun integratedMusicOtaUpdatesStableAndPrivatePreviewInstallations() {
        val tag = "v0.3.23"
        fun asset(name: String) = JSONObject().put("name", name).put("size", 18000000)
            .put("browser_download_url", "https://github.com/RTensorLabs/music-app/releases/download/$tag/$name")
        val fixturePath = System.getProperty("music.otaReleaseFixture")
        val release = if (fixturePath == null) JSONObject().put("tag_name", tag).put("draft", false)
            .put("assets", JSONArray().put(asset("music-debug.apk")).put(asset("SHA256SUMS")))
        else JSONObject(java.io.File(fixturePath).readText())
        for (installed in listOf("0.3.9", "0.3.10", "0.3.11", "0.3.12", "0.3.13", "0.3.14", "0.3.15", "0.3.16", "0.3.17", "0.3.18", "0.3.19", "0.3.20", "0.3.21", "0.3.22")) {
            val update = GitHubUpdater.selectRelease(JSONArray().put(release), installed)
            assertNotNull("Music $installed must receive the integrated OTA", update)
            assertEquals("0.3.23", update!!.version)
            assertEquals("music-debug.apk", update.apkName)
            assertEquals("https://github.com/RTensorLabs/music-app/releases/download/$tag/music-debug.apk", update.apkUrl)
            assertTrue(GitHubUpdater.isReleaseAsset(update.checksumUrl))
        }
        for (installedCode in listOf(12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L)) GitHubUpdater.verifyIdentity(
            AppIdentity("labs.rtensor.music", installedCode, setOf("same-signing-key")),
            AppIdentity("labs.rtensor.music", 26, setOf("same-signing-key")),
        )
    }
}
