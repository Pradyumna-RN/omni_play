package labs.rtensor.music.apple

import android.net.Uri
import android.util.Base64
import androidx.media3.common.*
import androidx.media3.common.util.*
import androidx.media3.datasource.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import kotlinx.coroutines.runBlocking
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class ApplePlaybackTest {
    private val credentials = AppleCredentials("header.payload.signature", "synthetic-user-token", storefront = "us")
    private val song = Track("yt:fixture", "Afterimage", "Serein", "https://youtube.com/watch?v=abcdefghijk", durationMs = 200000)
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/audio/$name")).readBytes()
    private fun init(bytes: ByteArray): ByteArray {
        var p = 0
        while (p < bytes.size) {
            val n = java.nio.ByteBuffer.wrap(bytes, p, 4).int
            require(n >= 8 && n <= bytes.size - p)
            if (String(bytes, p + 4, 4, Charsets.US_ASCII) == "moov") return bytes.copyOfRange(0, p + n)
            p += n
        }
        error("No moov")
    }
    private class Native : AppleNativeAccess {
        var decryptions = 0; var lookups = 0
        override suspend fun ensure(session: String) = JSONObject().put("runtimeReady", true)
        override suspend fun account(operation: Int, user: String, secret: String) = JSONObject()
        override suspend fun credentials() = error("Not needed")
        override suspend fun playback(song: String): JSONObject {
            lookups++
            return JSONObject().put("songList", JSONArray().put(JSONObject().put("adamId", song).put("hls-playlist-url", "https://audio.itunes.apple.com/master.m3u8")))
        }
        override fun decrypt(session: String, song: String, key: String, bytes: ByteArray): ByteArray {
            assertEquals("skd://itunes.apple.com/P123/s1/e1", key)
            assertEquals(0, bytes.size % 16); decryptions++
            return Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(16) { it.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
            }.doFinal(bytes)
        }
        override fun close() {}
    }
    @Test fun realFragmentedMp4EncryptedAlacSamplesMatchOriginalEncodedFramesExactly() {
        val clear = resource("owned-apple-alac.m4a"); val protected = resource("owned-apple-cbcs-alac.mp4")
        val privateRuntime = Native(); val format = AppleHls.probe(init(protected))
        assertEquals("audio/alac", format.sampleMimeType); assertEquals(192000, format.sampleRate); assertEquals(2, format.channelCount)
        val kids = AppleInitMetadata.prefetchKids(init(protected))
        assertEquals(setOf((32 until 48).joinToString("") { "%02x".format(it) }), kids)
        val entry = AppleSegment("https://audio.itunes.apple.com/own.mp4", "skd://itunes.apple.com/P123/s1/e1", AppleHls.FAIRPLAY, null, null, false)
        val asset = AppleMediaAsset("123", ApplePlaylist("", listOf(entry)), format, "owned", credentials, "session", privateRuntime, kids)
        fun extract(bytes: ByteArray, encrypted: Boolean): List<ByteArray> {
            val packets = mutableListOf<ByteArray>(); val buffer = ByteArrayOutputStream()
            val output = object : TrackOutput {
                override fun format(value: Format) { assertNull(value.drmInitData); assertEquals(C.CRYPTO_TYPE_NONE, value.cryptoType) }
                override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int {
                    val b = ByteArray(length); val n = input.read(b, 0, length); if (n > 0) buffer.write(b, 0, n); return n
                }
                override fun sampleData(data: ParsableByteArray, length: Int, part: Int) { val b = ByteArray(length); data.readBytes(b, 0, length); buffer.write(b) }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    assertEquals(0, flags and C.BUFFER_FLAG_ENCRYPTED); assertNull(crypto); assertEquals(size, buffer.size())
                    packets += buffer.toByteArray(); buffer.reset()
                }
            }
            val active = ThreadLocal<AppleSegment>().apply { set(entry) }
            val track = if (encrypted) NativeTrackOutput(output, asset, active) else output
            val extractor = FragmentedMp4Extractor()
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput { assertEquals(C.TRACK_TYPE_AUDIO, type); return track }
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {}
            })
            val data = ByteArrayDataSource(bytes); data.open(DataSpec(Uri.parse("memory://owned.mp4")))
            try {
                val input = DefaultExtractorInput(data, 0, bytes.size.toLong()); val holder = PositionHolder(); var n = 0
                while (extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) check(++n < 1000)
            } finally { data.close(); extractor.release() }
            return packets
        }
        val originals = extract(clear, false); val actual = extract(protected, true)
        assertEquals(2, actual.size); assertEquals(2, privateRuntime.decryptions)
        originals.indices.forEach { assertArrayEquals("Whole sample $it, including its partial-block tail", originals[it], actual[it]) }
        assertTrue(AppleInitMetadata.prefetchKids(init(clear)).isEmpty())
    }
    private fun http(requests: MutableList<Request>, wrongArtist: Boolean = false): AppleHttp {
        val aac = resource("owned-apple-aac-init.mp4")
        val alac = init(resource("owned-apple-cbcs-alac.mp4"))
        val kid = Base64.encodeToString(ByteArray(16) { (it + 1).toByte() }, Base64.NO_WRAP)
        return AppleHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val path = request.url.encodedPath
            val body: ByteArray = when {
                path.endsWith("/search") -> JSONObject().put("results", JSONObject().put("songs", JSONObject().put("data", JSONArray().put(JSONObject().put("id", "123")
                    .put("attributes", JSONObject().put("name", song.title).put("artistName", if (wrongArtist) "Someone else" else song.artist).put("durationInMillis", song.durationMs)))))).toString().toByteArray()
                request.url.toString() == AppleHttp.PLAYBACK -> """{"songList":[{"adamId":"123","assets":[{"flavor":"28:ctrp256","URL":"https://audio.itunes.apple.com/aac.m3u8"}]}]}""".toByteArray()
                path == "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS=\"alac\",AUDIO=\"stereo-192000-24\"\nalac.m3u8\n".toByteArray()
                path == "/aac.m3u8" || path == "/alac.m3u8" -> {
                    val native = path == "/alac.m3u8"
                    val length = if (native) alac.size else aac.size
                    val encryption = if (native) "METHOD=SAMPLE-AES,KEYFORMAT=\"${AppleHls.FAIRPLAY}\",URI=\"skd://itunes.apple.com/P123/s1/e1\""
                        else "METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"${AppleHls.WIDEVINE}\",URI=\"data:text/plain;base64,$kid\""
                    "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"${if (native) "alac" else "aac"}.mp4\",BYTERANGE=\"$length@0\"\n#EXT-X-KEY:$encryption\n#EXTINF:6,\nsample.mp4\n#EXT-X-ENDLIST\n".toByteArray()
                }
                path == "/aac.mp4" -> aac
                path == "/alac.mp4" -> alac
                else -> error("Unexpected fixture path $path")
            }
            if (request.url.host.endsWith("itunes.apple.com") && request.url.toString() != AppleHttp.PLAYBACK) {
                assertNull(request.header("Authorization")); assertNull(request.header("Media-User-Token"))
            }
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(body.toResponseBody()).build()
        }.build())
    }
    @Test fun accountResolverReturnsVerifiedAacFallbackAndRealHiresAlacWithoutTrustingBadges() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>(); val runtime = Native()
        val choices = AppleResolver(http(requests), runtime).resolve(song, credentials, "native-session", "apple-account:1") { true }
        assertEquals(setOf("audio/alac", "audio/mp4a-latm"), choices.map { it.mime }.toSet())
        val best = checkNotNull(SourceQuality.best(song, choices))
        assertEquals("audio/alac", best.mime); assertEquals(192000, best.sampleRate); assertEquals(24, best.bitDepth)
        assertFalse(best.atmos); assertNotNull(best.appleAsset); assertEquals(1, runtime.lookups)
        assertTrue(choices.all { it.sourceId == "apple-account:1" && it.eligible })
        val aac = choices.single { it.mime == "audio/mp4a-latm" }
        assertFalse(aac.lossless); assertFalse(aac.copy(appleAsset = null).eligible)
    }
    @Test fun webOnlyAccountNeverCallsNativeAndDifferentRecordingsAreRejectedBeforePlayback() = runBlocking {
        val runtime = Native(); val requests = CopyOnWriteArrayList<Request>()
        val choices = AppleResolver(http(requests), runtime).resolve(song, credentials, null, "web:1") { true }
        assertEquals(1, choices.size); assertEquals("audio/mp4a-latm", choices.single().mime); assertEquals(0, runtime.lookups)
        requests.clear()
        assertTrue(AppleResolver(http(requests, true), runtime).resolve(song, credentials, null, "web:1") { true }.isEmpty())
        assertEquals(1, requests.size)
        assertTrue(AppleResolver(http(requests), runtime).resolve(song, credentials, null, "web:1") { false }.isEmpty())
        assertEquals(1, requests.size)
    }
}
