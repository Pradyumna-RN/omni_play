package labs.rtensor.music.apple

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.extractor.TrackOutput
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class AppleProtocolTest {
    private val credentials = AppleCredentials("header.payload.signature", "synthetic-user-token")
    private val media = "https://audio-ssl.itunes.apple.com/own/playlist.m3u8?token=synthetic"
    private val nativePlaylist = """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:6
#EXT-X-MAP:URI="audio.mp4",BYTERANGE="512@0"
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P123/s1/e1",KEYFORMAT="com.apple.streamingkeydelivery"
#EXTINF:6,
#EXT-X-BYTERANGE:1024@512
audio.mp4
#EXTINF:6,
#EXT-X-BYTERANGE:1024
audio.mp4
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P456/s1/e2",KEYFORMAT="com.apple.streamingkeydelivery"
#EXTINF:6,
next.mp4
#EXT-X-ENDLIST
"""
    @Test fun firstPartyOriginsRejectSpoofsUserinfoDowngradesAndForeignAccounts() {
        assertTrue(AppleOrigins.canNavigate("https://idmsa.apple.com/appleauth/auth"))
        assertFalse(AppleOrigins.canConfirm("https://idmsa.apple.com/"))
        for (bad in listOf("https://music.apple.com.evil.test/", "http://music.apple.com/", "https://user@music.apple.com/", "https://music.apple.com:444/", "javascript:alert(1)")) assertFalse(bad, AppleOrigins.canNavigate(bad))
        assertFalse(AppleOrigins.isMedia("https://apple.com.evil.test/song.mp4"))
        assertFalse(AppleOrigins.isApi("https://amp-api.music.apple.com/v2/me"))
        assertFalse(AppleOrigins.nativeKey("skd://itunes.apple.com/P123\n"))
    }
    @Test fun cookieDecodingPreservesPlusAndCredentialsNeverStringify() {
        assertEquals("abc+def/ghi=jkl", AppleOrigins.cookieToken("other=x; media-user-token=abc%2Bdef%2Fghi%3Djkl; another=y"))
        assertEquals("abc+defgh", AppleOrigins.cookieToken("media-user-token=abc+defgh"))
        assertNull(AppleOrigins.cookieToken("media-user-token=abc%0Adefgh"))
        assertFalse(credentials.toString().contains(credentials.musicUserToken))
        assertThrows(IllegalArgumentException::class.java) { AppleCredentials.parse(credentials.json().put("musicUserToken", "has whitespace")) }
    }
    @Test fun accountHeadersNeverReachMediaOrRedirectTargets() {
        val http = AppleHttp()
        assertEquals("Bearer header.payload.signature", http.request("${AppleHttp.API}/me/storefront", credentials).header("Authorization"))
        assertNull(http.request(media).header("Authorization"))
        assertNull(http.request(media).header("Media-User-Token"))
        assertThrows(IllegalArgumentException::class.java) { http.request(media, credentials) }
        assertThrows(IllegalArgumentException::class.java) { http.request("https://attacker.test/", credentials) }
        assertThrows(IllegalArgumentException::class.java) { http.request(AppleHttp.LICENSE + "?redirect=evil", credentials) }
    }
    @Test fun httpRedirectAndOversizedResponsesFailRatherThanForwardingSecrets() {
        var calls = 0
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            calls++; Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").code(302)
                .header("Location", "https://attacker.test/").body("".toResponseBody()).build()
        }.build()
        val http = AppleHttp(client)
        assertThrows(java.io.IOException::class.java) { http.execute(http.request("${AppleHttp.API}/me/storefront", credentials)) }
        assertEquals(1, calls)
        val large = AppleHttp(
            OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK").code(200).body("x".repeat(65).toResponseBody()).build() }.build()
        )
        assertThrows(java.io.IOException::class.java) { large.execute(large.request(media), 64) }
    }
    @Test fun nativePlaylistRetainsRangesAndBindsEachSegmentToItsKey() {
        val playlist = AppleHls.playlist(nativePlaylist, media, true)
        assertEquals(4, playlist.entries.size)
        assertEquals(listOf(0L, 512L, 1536L, null), playlist.entries.map { it.rangeStart })
        assertEquals(playlist.entries[1].keyUri, playlist.entries[2].keyUri)
        assertNotEquals(playlist.entries[2].keyUri, playlist.entries[3].keyUri)
        assertTrue(playlist.text.contains("music-apple://stream/2"))
        assertFalse(playlist.text.contains("skd://"))
        assertFalse(playlist.text.contains("synthetic"))
        assertThrows(IllegalArgumentException::class.java) { AppleHls.playlist(nativePlaylist.replace("#EXT-X-ENDLIST", ""), media, true) }
        assertThrows(IllegalStateException::class.java) { AppleHls.playlist(nativePlaylist.replace("audio.mp4", "https://evil.test/audio.mp4"), media, true) }
    }
    @Test fun masterParsingKeepsQuotedCodecsAndRatesWithoutSelectingVideo() {
        val text = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="alac",AUDIO="stereo-192000-24"
hires.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=768000,CODECS="ec-3",AUDIO="atmos"
spatial.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.640028,mp4a.40.2"
video.m3u8
"""
        val choices = AppleHls.variants(text, media)
        assertEquals(2, choices.size); assertEquals(192000, choices[0].rate); assertEquals(24, choices[0].depth)
        assertThrows(IllegalArgumentException::class.java) { AppleHls.attributes("#EXT-X-KEY:METHOD=A,METHOD=B") }
    }
    @Test fun rawWidevineKidsBecomeRealPsshAndDoNotInventKeys() {
        val kid = ByteArray(16) { (it + 1).toByte() }
        val uri = "data:text/plain;base64," + Base64.encodeToString(kid, Base64.NO_WRAP)
        val pssh = AppleHls.widevinePssh(uri)
        assertEquals(C.WIDEVINE_UUID, androidx.media3.extractor.mp4.PsshAtomUtil.parseUuid(pssh))
        assertArrayEquals(byteArrayOf(8, 1, 18, 16) + kid, androidx.media3.extractor.mp4.PsshAtomUtil.parseSchemeSpecificData(pssh, C.WIDEVINE_UUID))
        assertThrows(IllegalArgumentException::class.java) { AppleHls.widevinePssh("data:;base64," + Base64.encodeToString(ByteArray(16), Base64.NO_WRAP)) }
    }
    @Test fun nativeSamplesPreserveClearBytesAndPartialBlocksAndStripOnlyEncryptionPrefix() {
        val pcm = ByteArray(40) { it.toByte() }
        val prefixed = ByteBuffer.allocate(1 + 16 + 2 + 12 + pcm.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x90.toByte()).put(ByteArray(16)).putShort(2).putShort(3).putInt(17).putShort(2).putInt(18).put(pcm).array()
        var received = byteArrayOf()
        val output = AppleSample.decode(prefixed, TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CBC, ByteArray(16), 0, 0), false) {
            received = it; ByteArray(it.size) { n -> (n + 100).toByte() }
        }
        assertEquals(32, received.size)
        assertArrayEquals(pcm.copyOfRange(3, 19) + pcm.copyOfRange(22, 38), received)
        assertArrayEquals(pcm.copyOfRange(0, 3), output.copyOfRange(0, 3))
        assertEquals(pcm[19], output[19]); assertEquals(pcm[38], output[38]); assertEquals(pcm[39], output[39])
        assertArrayEquals(pcm.copyOfRange(20, 22), output.copyOfRange(20, 22))
        assertThrows(IllegalArgumentException::class.java) { AppleSample.decode(prefixed.copyOf(10), TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CBC, ByteArray(16), 0, 0), false) { it } }
        assertThrows(IllegalArgumentException::class.java) { AppleSample.decode(prefixed, TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, ByteArray(16), 0, 0), false) { it } }
    }
    @Test fun independentCbcVectorAndPatternSkipKeepUnencryptedBytesIntact() {
        val key = ByteArray(16) { (it + 11).toByte() }; val iv = ByteArray(16) { (31 - it).toByte() }
        val plain = ByteArray(77) { (it * 3).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val encrypted = plain.copyOf()
        cipher.update(plain, 3, 16).copyInto(encrypted, 3)
        cipher.update(plain, 35, 16).copyInto(encrypted, 35)
        cipher.update(plain, 67, 0)
        AppleSample.decryptCbc(encrypted, 3, 74, key, iv, 1, 1)
        assertArrayEquals(plain, encrypted)
    }
    @Test fun mappedSourcePreservesSeekRangesAndNeverAcceptsUnmappedUrls() {
        val playlist = AppleHls.playlist(nativePlaylist, media, true)
        val format = Format.Builder().setSampleMimeType("audio/alac").setSampleRate(96000).setChannelCount(2).build()
        var current = true; var captured: DataSpec? = null
        val bytes = ByteArrayDataSource(ByteArray(4096))
        val factory = DataSource.Factory { object : DataSource by bytes {
            override fun open(dataSpec: DataSpec): Long { captured = dataSpec; return bytes.open(dataSpec) }
        } }
        val asset = AppleMediaAsset("123", playlist, format, "test", credentials, isCurrent = { current })
        val active = ThreadLocal<AppleSegment>(); val source = AppleDataSource(asset, active, factory)
        val uri = Uri.parse("music-apple://stream/2")
        assertEquals(128L, source.open(DataSpec.Builder().setUri(uri).setPosition(1600).setLength(128).build()))
        assertEquals(1600L, captured!!.position); assertEquals(128L, captured!!.length)
        assertTrue(captured!!.httpRequestHeaders.isEmpty()); assertEquals(uri, source.uri)
        assertEquals(playlist.entries[2], active.get()); source.close(); assertNull(active.get())
        current = false
        assertThrows(IllegalStateException::class.java) { source.open(DataSpec(uri)) }
        current = true
        assertThrows(IllegalArgumentException::class.java) { source.open(DataSpec(Uri.parse("https://attacker.test/"))) }
    }
    @Test fun importedRuntimeRejectsTamperingTraversalAndCancelledImports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.io.File(context.cacheDir, "apple-import-test").apply { mkdirs() }
        val bytes = "owned synthetic ELF test bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for (name in listOf("../../escape", "lib/arm64-v8a/libfixture.so")) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        } }.toByteArray()
        AppleRuntimeFiles.readPackage(ByteArrayInputStream(archive), dir, mapOf("libfixture.so" to sha), "arm64-v8a")
        assertArrayEquals(bytes, java.io.File(dir, "libfixture.so").readBytes())
        assertFalse(java.io.File(context.cacheDir.parentFile, "escape").exists())
        assertThrows(IllegalArgumentException::class.java) { AppleRuntimeFiles.readPackage(ByteArrayInputStream(archive), dir, mapOf("libfixture.so" to "bad"), "arm64-v8a") }
        assertThrows(java.util.concurrent.CancellationException::class.java) { AppleRuntimeFiles.readPackage(ByteArrayInputStream(archive), dir, mapOf("libfixture.so" to sha), "arm64-v8a") { throw java.util.concurrent.CancellationException() } }
        dir.deleteRecursively()
    }
    @Test fun apkmBundlesReadNestedSplitsAndSelectOnlyTheRequestedArchitecture() {
        fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive -> entries.forEach { (name, bytes) ->
                archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
            } }
        }.toByteArray()
        val arm = "owned ARM64 library fixture".toByteArray()
        val x86 = "owned x86-64 library fixture".toByteArray()
        val bundle = zip("info.json" to "{}".toByteArray(), "base.apk" to zip("AndroidManifest.xml" to byteArrayOf(1)),
            "split_config.arm64_v8a.apk" to zip("lib/arm64-v8a/libfixture.so" to arm),
            "split_config.x86_64.apk" to zip("lib/x86_64/libfixture.so" to x86))
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((arch, expected) in listOf("arm64-v8a" to arm, "x86_64" to x86)) {
            val directory = java.io.File(context.cacheDir, "apkm-$arch").apply { mkdirs() }
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
                AppleRuntimeFiles.readPackage(ByteArrayInputStream(bundle), directory, mapOf("libfixture.so" to digest), arch)
                assertArrayEquals(expected, java.io.File(directory, "libfixture.so").readBytes())
                assertEquals(listOf("libfixture.so"), directory.listFiles()!!.map { it.name })
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun playbackDependencyClosureExcludesIcuTestToolsAndKeepsRequiredDependencies() {
        val graph = mapOf("libandroidappmusic.so" to listOf("libCoreFoundation.so", "libc.so"),
            "libCoreFoundation.so" to listOf("libicuuc_sv_apple.so"), "libicuuc_sv_apple.so" to listOf("libc.so"),
            "libicutest_sv_apple.so" to listOf("libicutu_sv_apple.so"))
        val loaded = mutableListOf<String>()
        val order = AppleRuntimeFiles.dependencyOrder(graph.keys, setOf("libandroidappmusic.so")) { name -> loaded += name; graph.getValue(name) }
        assertEquals(listOf("libicuuc_sv_apple.so", "libCoreFoundation.so", "libandroidappmusic.so"), order)
        assertFalse(loaded.contains("libicutest_sv_apple.so"))
        val required = assertThrows(IllegalArgumentException::class.java) {
            AppleRuntimeFiles.dependencyOrder(graph.keys, setOf("libicutest_sv_apple.so"), graph::getValue)
        }
        assertTrue(required.message.orEmpty().contains("libicutu_sv_apple.so"))
        assertTrue(required.message.orEmpty().contains("required by libicutest_sv_apple.so"))
        assertThrows(IllegalArgumentException::class.java) {
            AppleRuntimeFiles.dependencyOrder(graph.keys, setOf("libmissing.so"), graph::getValue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppleRuntimeFiles.dependencyOrder(setOf("liba.so", "libb.so"), setOf("liba.so")) { if (it == "liba.so") listOf("libb.so") else listOf("liba.so") }
        }
    }
    @Test fun appleSessionVaultEncryptsTokensAndDiscardsTamperedData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = AccountSession(context, "apple-vault-test") { SecretKeySpec(ByteArray(32) { 13 }, "AES") }
        vault.save(credentials.json().toString())
        val prefs = context.getSharedPreferences("apple-vault-test", Context.MODE_PRIVATE)
        assertFalse(prefs.getString("credentials", "")!!.contains(credentials.musicUserToken))
        assertEquals(credentials.musicUserToken, AppleCredentials.parse(JSONObject(vault.load()!!)).musicUserToken)
        val sealed = Base64.decode(prefs.getString("credentials", ""), Base64.NO_WRAP); sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        prefs.edit().putString("credentials", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        assertNull(vault.load()); assertTrue(prefs.all.isEmpty())
    }
}
