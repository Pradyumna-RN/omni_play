package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.ProviderMediaSourceFactory
import labs.rtensor.music.sources.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderSourcesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val song = Track("yt:abcdefghijk", "Afterimage", "Serein", "https://www.youtube.com/watch?v=abcdefghijk", durationMs = 200000)
    private val connection = SourceConnection("one", "Fixture", MusicProvider.APPLE_MUSIC, SourceProtocol.GATEWAY, "https://account.example/personal", "synthetic-server-token")
    private fun settings(): SourceSettings = SourceSettings(context, AccountSession(context, "source-fixture") { SecretKeySpec(ByteArray(32) { 7 }, "AES") }).also {
        it.connections.value.forEach { c -> it.remove(c.id) }
    }
    private fun stream(id: String, provider: MusicProvider, rate: Int, mime: String = "audio/flac", channels: Int = 2, atmos: Boolean = false) =
        ProviderStream(id, provider, "https://cdn.example/$id", song.title, song.artist, 200000, mime, rate, 24, channels, atmos)
    private fun json(s: ProviderStream) = JSONObject().put("id", s.id).put("provider", s.provider.wire).put("url", s.url)
        .put("title", s.title).put("artist", s.artist).put("durationMs", s.durationMs).put("mime", s.mime)
        .put("sampleRate", s.sampleRate).put("bitDepth", s.bitDepth).put("channels", s.channels).put("atmos", s.atmos)
    private fun client(profile: SourceConnection = connection, requests: MutableList<Request> = CopyOnWriteArrayList(), respond: (Request) -> String): SourceServerClient {
        val http = SourceServerClient.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(request).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SourceServerClient(profile, http)
    }

    @Test fun atmosOutranksLosslessAndAllProvidersCompeteByRateThenDepth() {
        val cd = stream("cd", MusicProvider.APPLE_MUSIC, 44100).copy(bitDepth = 16)
        val hires = stream("hires", MusicProvider.QOBUZ, 192000)
        val atmos = stream("atmos", MusicProvider.APPLE_MUSIC, 48000, "audio/eac3-joc", 6, true)
        assertEquals(atmos, SourceQuality.best(song, listOf(cd, hires, atmos)))
        assertEquals(hires, SourceQuality.best(song, listOf(cd, hires)))
        assertEquals(cd, SourceQuality.best(song, listOf(cd, hires.copy(title = "Afterimage (Live)"))))
        assertNull(SourceQuality.best(song, listOf(hires.copy(artist = "Another Serein"), hires.copy(durationMs = 10000))))
    }

    @Test fun signedUrlRotationKeepsRepresentationIdentityButDifferentFormatsNeverShareIt() {
        val a = stream("a", MusicProvider.TIDAL, 96000)
        assertEquals(a.cacheKey, a.copy(url = a.url + "?token=rotated").cacheKey)
        assertNotEquals(a.cacheKey, a.copy(sampleRate = 192000).cacheKey)
        assertNotEquals(a.cacheKey, a.copy(mime = "audio/alac").cacheKey)
    }

    @Test fun nonAtmosLossyMultichannelNeverOutranksLosslessAndMissingRateIsNotInvented() {
        val hires = stream("hires", MusicProvider.QOBUZ, 192000)
        val lossy = stream("ac3", MusicProvider.TIDAL, 48000, "audio/ac3", 6)
        val unknown = stream("unknown-rate", MusicProvider.APPLE_MUSIC, 0)
        assertEquals(hires, SourceQuality.best(song, listOf(lossy, hires, unknown)))
        assertTrue(unknown.eligible); assertEquals(0, unknown.sampleRate)
        val mislabeled = hires.asMaster().json().put("atmos", true)
        assertFalse(labs.rtensor.music.data.Master.fromJson(mislabeled).atmos)
    }

    @Test fun sixFailuresExhaustRemoteCandidatesAndUseTheOriginalRoute() = runBlocking {
        val store = settings(); store.save(connection)
        val candidates = (1..9).map { stream("choice-$it", MusicProvider.APPLE_MUSIC, 44100 + it * 1000) }
        val registry = PlaybackSources(store) { profile -> client(profile) { JSONObject().put("streams", JSONArray().apply { candidates.forEach { put(json(it)) } }).toString() } }
        repeat(6) {
            val candidate = checkNotNull(registry.choose(song, "limited", null))
            registry.selected("limited", candidate, "YouTube")
            assertTrue(registry.reject("limited"))
        }
        assertNull(registry.choose(song, "limited", null))
    }

    @Test fun privateConnectionsAreEncryptedAndUnsafeConfigurationIsRejected() {
        val store = settings(); store.save(connection)
        val raw = context.getSharedPreferences("source-fixture", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(raw.contains(connection.token)); assertFalse(raw.contains("account.example"))
        val reopened = SourceSettings(context, AccountSession(context, "source-fixture") { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        assertEquals(connection, reopened.connections.value.single())
        listOf("http://server.example", "https://user:password@server.example", "https://server.example/?token=secret").forEach {
            assertTrue(runCatching { SourceConnection.validateUrl(it) }.isFailure)
        }
        store.remove(connection.id); assertTrue(store.connections.value.isEmpty())
        assertFalse(context.getSharedPreferences("source-fixture", Context.MODE_PRIVATE).contains("credentials"))
    }

    @Test fun gatewayUsesConfiguredAuthorizationAndRejectsPreviewProtectedAndShortResponses() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val valid = json(stream("full", MusicProvider.APPLE_MUSIC, 96000))
        val values = JSONArray().put(valid).put(JSONObject(valid.toString()).put("preview", true))
            .put(JSONObject(valid.toString()).put("encrypted", true)).put(JSONObject(valid.toString()).put("drm", JSONObject()))
            .put(JSONObject(valid.toString()).put("durationMs", 30000)).put(JSONObject(valid.toString()).put("url", "http://cdn.example/audio"))
        val source = client(requests = requests) { request ->
            if (request.url.encodedPath.endsWith("capabilities")) """{"version":1,"fullTracks":true,"providers":["apple_music"],"name":"Personal source"}"""
            else JSONObject().put("streams", values).toString()
        }
        assertEquals("Personal source", source.probe())
        assertEquals(listOf("full"), source.resolve(song).map { it.id })
        assertEquals("/personal/v1/resolve", requests.last().url.encodedPath)
        assertEquals("Bearer synthetic-server-token", requests.last().header("Authorization"))
        assertEquals(2, requests.size) // Returned CDN is never probed with server credentials.
        assertFalse(SourceServerClient.defaultClient().followRedirects)
        assertFalse(SourceServerClient.defaultClient().followSslRedirects)
    }

    @Test fun addonSearchAndStreamMergeMetadataWithoutInventingAPlayableUrl() = runBlocking {
        val profile = connection.copy(protocol = SourceProtocol.ADDON, provider = MusicProvider.LIBRARY)
        var missingUrl = false
        val requests = CopyOnWriteArrayList<Request>()
        val source = client(profile, requests) { request ->
            when {
                request.url.encodedPath.endsWith("search") -> """{"tracks":[{"id":"one/id","title":"Afterimage","artist":"Serein","duration":200}]}"""
                missingUrl -> """{"codec":"flac","sample_rate":192000,"bit_depth":24}"""
                else -> """{"url":"https://cdn.example/one.flac","codec":"flac","sample_rate":192000,"bit_depth":24,"format":"flac"}"""
            }
        }
        val resolved = source.resolve(song).single()
        assertEquals(192000, resolved.sampleRate); assertEquals(24, resolved.bitDepth)
        assertEquals("/personal/stream/one%2Fid", requests.last().url.encodedPath)
        assertEquals("Serein Afterimage", requests.first().url.queryParameter("q"))
        missingUrl = true; assertTrue(source.resolve(song).isEmpty())
    }

    @Test fun tidalHifiAcceptsClearFullFlacAndRejectsEncryptedManifests() = runBlocking {
        val profile = connection.copy(protocol = SourceProtocol.TIDAL_HIFI, provider = MusicProvider.TIDAL)
        var encrypted = false
        val source = client(profile) { request ->
            if (request.url.encodedPath.endsWith("search/")) """{"data":{"items":[{"id":7,"title":"Afterimage","artist":{"name":"Serein"},"duration":200}]}}"""
            else {
                val manifest = JSONObject().put("codecs", "flac").put("encryptionType", if (encrypted) "AES" else "NONE").put("urls", JSONArray(listOf("https://cdn.example/full.flac")))
                JSONObject().put("data", JSONObject().put("manifest", android.util.Base64.encodeToString(manifest.toString().toByteArray(), android.util.Base64.NO_WRAP))
                    .put("sampleRate", 192000).put("bitDepth", 24)).toString()
            }
        }
        val resolved = source.resolve(song).single()
        assertEquals(MusicProvider.TIDAL, resolved.provider); assertEquals(192000, resolved.sampleRate)
        encrypted = true; assertTrue(source.resolve(song).isEmpty())
    }

    @Test fun failedRepresentationIsExcludedAndEventuallyFallsBackWithoutLooping() = runBlocking {
        val store = settings(); store.save(connection)
        val high = stream("hires", MusicProvider.APPLE_MUSIC, 192000)
        val cd = stream("cd", MusicProvider.APPLE_MUSIC, 44100)
        var requests = 0
        val registry = PlaybackSources(store) { profile -> client(profile) { requests++; JSONObject().put("streams", JSONArray().put(json(high)).put(json(cd))).toString() } }
        assertEquals(high, registry.choose(song, "play-one", null))
        registry.selected("play-one", high, "YouTube"); assertTrue(registry.reject("play-one"))
        assertEquals(cd, registry.choose(song, "play-one", null))
        registry.selected("play-one", cd, "YouTube"); assertTrue(registry.reject("play-one"))
        assertNull(registry.choose(song, "play-one", null))
        assertEquals("Fallback must use already resolved candidates", 1, requests)
        registry.selected("play-one", null, "YouTube"); assertFalse(registry.reject("play-one"))
        assertEquals(high, registry.choose(song, "play-two", null))
    }

    @Test fun labelsUseActualCodecAndDecodedChannelsInsteadOfAnAddonClaim() {
        val stream = stream("claimed", MusicProvider.APPLE_MUSIC, 192000)
        val info = SourcePlaybackInfo(stream, "YouTube", Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).build())
        val stereo = DownmixReport(48000, listOf(SpeakerPosition.FL, SpeakerPosition.FR), processingEnabled = false, comparisonAvailable = false)
        assertTrue(info.label(stereo).contains("AAC")); assertFalse(info.label(stereo).contains("FLAC"))
        assertTrue(info.label(stereo).contains("stereo · unchanged")); assertFalse(info.label(stereo).contains("DMR"))
        assertFalse(SourcePlaybackInfo(stream, "YouTube").label(stereo).contains("192"))
    }

    @Test fun selectedSourceKeepsLogicalCatalogueIdentityAndUsesAnIsolatedPhysicalCache() {
        val store = settings(); store.save(connection)
        val selected = stream("high", MusicProvider.APPLE_MUSIC, 192000)
        val registry = PlaybackSources(store) { profile -> client(profile) { JSONObject().put("streams", JSONArray().put(json(selected))).toString() } }
        var physical: MediaItem? = null
        val basic = DefaultMediaSourceFactory(context)
        val fake = object : MediaSource.Factory by basic {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                physical = mediaItem
                return SilenceMediaSource.Factory().setDurationUs(200_000_000).createMediaSource()
            }
        }
        val original = MediaItem.Builder().setMediaId(song.id).setUri(song.url).setCustomCacheKey("queue-nonce")
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(Bundle().apply { putString("catalogue_track", song.json().toString()) }).build()).build()
        val source = ProviderMediaSourceFactory(fake, fake, registry).createMediaSource(original)
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null, PlayerId.UNSET)
        try {
            val until = System.nanoTime() + 5_000_000_000
            while (timeline == null && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            source.maybeThrowSourceInfoRefreshError()
            val logical = checkNotNull(timeline).getWindow(0, Timeline.Window()).mediaItem
            assertEquals(song.id, logical.mediaId); assertEquals(song.url, logical.localConfiguration!!.uri.toString())
            assertEquals("queue-nonce", logical.localConfiguration!!.customCacheKey)
            assertEquals(selected.url, physical!!.localConfiguration!!.uri.toString())
            assertEquals("queue-nonce:${selected.cacheKey}", physical!!.localConfiguration!!.customCacheKey)
        } finally { source.releaseSource(caller) }
    }
}
