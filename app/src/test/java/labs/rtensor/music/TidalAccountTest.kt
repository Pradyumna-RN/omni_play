package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TidalAccountTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requests = CopyOnWriteArrayList<Request>()
    private var clock = 1_000_000L
    private var refreshed = false
    private var refreshError = false
    private val song = Track("yt:abcdefghijk", "Afterimage", "Serein", "https://www.youtube.com/watch?v=abcdefghijk", durationMs = 200000)
    private fun vault() = AccountSession(context, "tidal-fixture") { SecretKeySpec(ByteArray(32) { 6 }, "AES") }
    @Before fun clearAccount() { vault().clear() }
    private fun response(request: Request): Pair<Int, String> = when {
        request.url.host == "auth.tidal.com" -> {
            val form = request.body as FormBody
            if (form.value(form.names().indexOf("grant_type")) == "refresh_token") {
                if (refreshError) 401 to """{"error":"invalid_grant","error_description":"do-not-leak-provider-body"}"""
                else { refreshed = true; 200 to token("updated-access", "rotated-refresh") }
            } else 200 to token("initial-access", "initial-refresh")
        }
        request.url.encodedPath.endsWith("/sessions") -> 200 to """{"userId":17,"countryCode":"IN"}"""
        request.url.encodedPath.endsWith("/search/tracks") -> 200 to """{"items":[{"id":42,"title":"Afterimage","artist":{"name":"Serein"},"duration":200}]}"""
        else -> {
            val manifest = """{"codecs":"flac","encryptionType":"NONE","channels":6,"urls":["https://cdn.example/music.flac"]}"""
            200 to JSONObject().put("assetPresentation", "FULL").put("sampleRate", 44100).put("bitDepth", 24)
                .put("manifest", android.util.Base64.encodeToString(manifest.toByteArray(), android.util.Base64.NO_WRAP)).toString()
        }
    }
    private fun token(access: String, refresh: String) = JSONObject().put("access_token", access).put("refresh_token", refresh)
        .put("expires_in", 3600).put("token_type", "Bearer").toString()
    private fun FormBody.names() = (0 until size).map(::name)
    private fun http(respond: (Request) -> Pair<Int, String> = ::response) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); val (code, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun account(respond: (Request) -> Pair<Int, String> = ::response) = TidalAccount(vault(), http(respond), "public-fixture-client") { clock }
    private fun callback(url: String): String = "tidal://login/auth?code=synthetic-one-time-code&state=${url.toHttpUrl().queryParameter("state")}"
    private suspend fun connected(): TidalAccount = account().also { it.complete(callback(it.begin())); assertTrue(it.state.value.connected) }

    @Test fun pkceUsesTheBrowserAndOnlyPersistsEncryptedTokens() = runBlocking {
        val a = account(); val url = a.begin().toHttpUrl()
        assertEquals("login.tidal.com", url.host)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(TidalAccount.REDIRECT, url.queryParameter("redirect_uri"))
        a.complete(callback(url.toString()))
        assertTrue(a.state.value.connected); assertFalse(a.state.value.connecting)
        val form = requests.first().body as FormBody
        val verifier = form.value(form.names().indexOf("code_verifier"))
        val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(url.queryParameter("code_challenge"), challenge)
        assertFalse(form.names().contains("client_secret"))
        val stored = context.getSharedPreferences("tidal-fixture", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(stored.contains("initial-access")); assertFalse(stored.contains(verifier))
        assertTrue(account().state.value.connected)
    }

    @Test fun pendingLoginSurvivesProcessRecreation() = runBlocking {
        val a = account(); val url = a.begin()
        val recreated = account(); recreated.complete(callback(url))
        assertTrue(recreated.state.value.connected)
        assertEquals(2, requests.size)
    }

    @Test fun wrongOrDuplicateStateNeverExchangesACode() = runBlocking {
        val a = account(); val url = a.begin()
        a.complete("tidal://login/auth?code=synthetic&state=wrong")
        a.complete(callback(url) + "&state=duplicate")
        a.complete(callback(url).replace("tidal://login/auth", "tidal://evil/auth"))
        assertTrue(requests.isEmpty()); assertFalse(a.state.value.connected)
        a.complete(callback(url)); assertTrue(a.state.value.connected)
    }

    @Test fun cancelledExpiredAndOldLoginAttemptsCannotSignIn() = runBlocking {
        val a = account(); val first = a.begin(); val current = a.begin()
        a.complete(callback(first)); assertTrue(requests.isEmpty())
        a.cancel(); a.complete(callback(current)); assertTrue(requests.isEmpty())
        val expired = a.begin(); clock += 600_001; a.complete(callback(expired))
        assertTrue(requests.isEmpty()); assertFalse(a.state.value.connected)
    }

    @Test fun aNativeAccountResolvesMultichannelWithoutAnyMusicServer() = runBlocking {
        val a = connected()
        val streams = a.resolve(song)
        assertEquals(1, streams.size); assertEquals(6, streams.single().channels); assertEquals(44100, streams.single().sampleRate)
        assertEquals("https://cdn.example/music.flac", streams.single().url)
        assertTrue(requests.filter { it.url.host == "api.tidal.com" }.all { it.header("Authorization") == "Bearer initial-access" })
        assertTrue(requests.none { it.url.host == "cdn.example" })
        assertEquals("IN", requests.last().url.queryParameter("countryCode"))
        val store = SourceSettings(context, AccountSession(context, "tidal-source-fixture") { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        store.connections.value.forEach { store.remove(it.id) }
        val registry = PlaybackSources(store, tidal = a)
        assertTrue(registry.enabled); assertNotNull(registry.choose(song, "one", null))
        a.disconnect()
        assertFalse(registry.enabled); assertNull(registry.choose(song, "one", null))
    }

    @Test fun refreshIsSerializedAndRotatedWithoutAClientSecret() = runBlocking {
        val a = connected(); clock += 3_590_000
        coroutineScope { List(2) { async(Dispatchers.Default) { a.resolve(song) } }.awaitAll().forEach { assertEquals(1, it.size) } }
        val refreshes = requests.filter { (it.body as? FormBody)?.let { f -> "refresh_token" in f.names() } == true }
        assertEquals(1, refreshes.size)
        assertFalse((refreshes.single().body as FormBody).names().contains("client_secret"))
        assertTrue(vault().load()!!.contains("rotated-refresh")); assertTrue(refreshed)
        assertEquals("Bearer updated-access", requests.last().header("Authorization"))
    }

    @Test fun aRejectedRefreshNeverBecomesAPlayableCandidateOrLeaksTheResponse() = runBlocking {
        val a = connected(); clock += 3_590_000; refreshError = true
        assertTrue(a.resolve(song).isEmpty())
        assertFalse(a.state.value.error.orEmpty().contains("do-not-leak-provider-body"))
        assertTrue(a.state.value.error.orEmpty().contains("Sign in again"))
    }

    @Test fun clearAtmosUsesTheAccountApiButNeverSendsItsBearerToTheManifestHost() = runBlocking {
        val a = account { r ->
            when {
                r.url.encodedPath.endsWith("/search/tracks") -> 200 to """{"items":[{"id":42,"title":"Afterimage","artist":{"name":"Serein"},"duration":200,"audioModes":["DOLBY_ATMOS"]}]}"""
                r.url.host == "openapi.tidal.com" -> {
                    assertEquals("EAC3_JOC", r.url.queryParameter("formats"))
                    assertEquals("/v2/trackManifests/42", r.url.encodedPath)
                    200 to """{"data":{"attributes":{"formats":["EAC3_JOC"],"uri":"https://cdn.example/manifests/42.mpd"}}}"""
                }
                r.url.host == "cdn.example" -> 200 to """
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT200S" minBufferTime="PT1S">
                    <Period><AdaptationSet mimeType="audio/mp4" codecs="ec-3" audioSamplingRate="48000">
                    <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="6"/>
                    <Representation id="scene" bandwidth="768000"><SegmentTemplate timescale="1" duration="5" initialization="../audio/init.mp4" media="../audio/segment-${'$'}Number${'$'}.m4s"/></Representation>
                    </AdaptationSet></Period></MPD>
                """.trimIndent()
                else -> response(r)
            }
        }
        a.complete(callback(a.begin()))
        val streams = a.resolve(song)
        assertEquals(2, streams.size)
        val atmos = streams.single { it.verifiedAtmosCodec }
        assertEquals(6, atmos.channels); assertEquals(48000, atmos.sampleRate)
        assertEquals("https://cdn.example/manifests/42.mpd", atmos.url)
        assertTrue(atmos.streamKeys.isNotEmpty())
        assertEquals(atmos, SourceQuality.best(song, streams))
        assertEquals("Bearer initial-access", requests.single { it.url.host == "openapi.tidal.com" }.header("Authorization"))
        assertNull(requests.single { it.url.host == "cdn.example" }.header("Authorization"))
        assertNull(requests.single { it.url.host == "cdn.example" }.header("Cookie"))
    }

    @Test fun unavailableProtectedOrDowngradedAtmosNeverPoisonsTheLosslessAlternative() = runBlocking {
        for (atmos in listOf(
            403 to "{}",
            200 to """{"data":{"attributes":{"formats":["FLAC"],"uri":"https://cdn.example/42.mpd"}}}""",
            200 to """{"data":{"attributes":{"formats":["EAC3_JOC"],"drmData":{"licenseUrl":"https://license.example"},"uri":"https://cdn.example/42.mpd"}}}""",
        )) {
            vault().clear(); requests.clear()
            val a = account { r -> when {
                r.url.encodedPath.endsWith("/search/tracks") -> 200 to """{"items":[{"id":42,"title":"Afterimage","artist":{"name":"Serein"},"duration":200,"audioModes":["DOLBY_ATMOS"]}]}"""
                r.url.host == "openapi.tidal.com" -> atmos
                else -> response(r)
            } }
            a.complete(callback(a.begin()))
            val stream = a.resolve(song).single()
            assertTrue(stream.lossless); assertFalse(stream.verifiedAtmosCodec)
            assertTrue(requests.none { it.url.host == "cdn.example" })
        }
    }

    @Test fun aLaterForbiddenEditionDoesNotDiscardTheFirstPlayableMatch() = runBlocking {
        val a = account { r -> when {
            r.url.encodedPath.endsWith("/search/tracks") -> 200 to """{"items":[{"id":42,"title":"Afterimage","artist":{"name":"Serein"},"duration":200},{"id":43,"title":"Afterimage","artist":{"name":"Serein"},"duration":200}]}"""
            r.url.encodedPath.contains("/tracks/43/") -> 403 to "{}"
            else -> response(r)
        } }
        a.complete(callback(a.begin()))
        assertEquals(1, a.resolve(song).size)
        assertNull(a.state.value.error)
    }

    @Test fun missingOrPreviewPresentationNeverBecomesAFullNativeRecording() = runBlocking {
        for (presentation in listOf(null, "PREVIEW")) {
            vault().clear()
            val a = account { r ->
                val result = response(r)
                if (r.url.encodedPath.contains("/playbackinfopostpaywall")) {
                    val json = JSONObject(result.second)
                    if (presentation == null) json.remove("assetPresentation") else json.put("assetPresentation", presentation)
                    200 to json.toString()
                } else result
            }
            a.complete(callback(a.begin()))
            assertTrue(a.resolve(song).isEmpty())
        }
    }

    @Test fun disconnectDuringTokenExchangeCannotResurrectTheAccount() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val a = account { r ->
            if (r.url.host == "auth.tidal.com") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            response(r)
        }
        val code = callback(a.begin())
        val completion = launch(Dispatchers.Default) { a.complete(code) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); a.disconnect(); release.countDown(); completion.join()
        assertFalse(a.state.value.connected); assertNull(vault().load())
    }
    @Test fun cancellingAResolveCancelsItsHttpCallInsteadOfWaitingForTheSocketTimeout() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var block = false
        var activeCall: Call? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (block && chain.request().url.encodedPath.endsWith("/search/tracks")) {
                activeCall = chain.call(); started.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
            val (code, body) = response(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val a = TidalAccount(vault(), http, "public-fixture-client") { clock }
        a.complete(callback(a.begin())); assertTrue(a.state.value.connected); block = true
        try {
            val job = async(Dispatchers.Default) { withTimeoutOrNull(500) { a.resolve(song) } }
            assertTrue(started.await(3, TimeUnit.SECONDS)); assertNull(job.await())
            assertTrue(activeCall!!.isCanceled())
        } finally { release.countDown() }
    }

}
