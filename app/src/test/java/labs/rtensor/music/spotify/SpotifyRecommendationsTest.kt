package labs.rtensor.music.spotify

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import labs.rtensor.music.data.*
import labs.rtensor.music.recommendations.RecommendationEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpotifyRecommendationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = SpotifyItem("a".repeat(22), "track", "Afterimage", "Mira", durationMs = 180000)
    private val next = first.copy(id = "b".repeat(22), title = "Daylight")
    private val youtube = Track("yt:abcdefghijk", first.title, "Mira - Topic", "https://music.youtube.com/watch?v=abcdefghijk", durationMs = 181000)
    private class Api(val respond: suspend (String, JSONObject) -> JSONObject) : SpotifyCatalogueAccess {
        val calls = mutableListOf<String>(); var closed = false
        override suspend fun request(operation: String, args: JSONObject): JSONObject { calls += operation; return respond(operation, args) }
        override fun close() { closed = true }
    }
    private fun page(vararg items: SpotifyItem) = JSONObject().put("items", JSONArray().apply { items.forEach { put(it.json()) } })
    private fun home() = JSONObject().put("sections", JSONArray().put(JSONObject().put("title", "Made for you").put("items", JSONArray().put(first.json()))))

    @Test fun sourcePreferenceDefaultsToYoutubeAndSurvivesRecreation() {
        val store = LibraryStore(context)
        assertEquals(RecommendationEngine.YOUTUBE, store.recommendationEngine)
        store.recommendationEngine = RecommendationEngine.SPOTIFY
        assertEquals(RecommendationEngine.SPOTIFY, LibraryStore(context).recommendationEngine)
        assertEquals(RecommendationEngine.YOUTUBE, RecommendationEngine.parse("unknown-future-source"))
    }
    @Test fun spotifyDiscoveryIdentitySurvivesMatchingAndJsonWithoutChangingOldTrackKeys() {
        val placeholder = first.track()
        val matched = youtube.copy(spotifyId = first.id)
        assertEquals(placeholder.libraryKey(), matched.libraryKey())
        assertEquals(matched, Track.fromJson(matched.json()))
        assertEquals(placeholder, first.entry().asTrack())
        assertEquals("abcdefghijk", youtube.libraryKey())
        assertEquals("local:content://owned/one", youtube.copy(source = Source.LOCAL, url = "content://owned/one", spotifyId = first.id).libraryKey())
        assertEquals("", Track.fromJson(youtube.json().put("spotifyId", "malformed")).spotifyId)
    }
    @Test fun strictRecordingMatchRejectsWrongArtistEditionDurationAndUnknownDuration() {
        assertTrue(SpotifyRecordingMatch.matches(first, youtube.copy(title = "Afterimage (Official Audio)")))
        for (bad in listOf(youtube.copy(artist = "Mira Tribute"), youtube.copy(artist = "Another Artist"),
            youtube.copy(title = "Afterimage (Live)"), youtube.copy(title = "Afterimage (Remix)"),
            youtube.copy(durationMs = 0), youtube.copy(durationMs = 195000))) assertFalse(bad.toString(), SpotifyRecordingMatch.matches(first, bad))
        assertFalse(SpotifyRecordingMatch.matches(first.copy(title = "Afterimage (2011 Remastered)"), youtube.copy(title = "Afterimage (2020 Remastered)")))
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr")); assertTrue(SpotifyRecordingMatch.matches(first.copy(title = "MIRROR"), youtube.copy(title = "Mirror"))) }
        finally { Locale.setDefault(original) }
    }
    @Test fun matchCacheCoalescesConcurrentSearchesExpiresAndNeverStoresMediaUrls() = runBlocking {
        var now = 1000L; var searches = 0
        val release = CompletableDeferred<Unit>()
        val matcher = SpotifyTrackMatcher(context, { searches++; release.await(); listOf(youtube.copy(title = "Afterimage (Live)"), youtube) }, { now })
        val a = async(start = CoroutineStart.UNDISPATCHED) { matcher.resolve(first.track()) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { matcher.resolve(first.track()) }
        assertEquals(1, searches); release.complete(Unit)
        assertEquals(a.await(), b.await()); assertEquals(first.id, a.await().spotifyId)
        assertFalse(SpotifyTrackMatcher.pending(a.await()))
        val restored = SpotifyTrackMatcher(context, { searches++; listOf(youtube) }, { now })
        assertEquals(a.await(), restored.resolve(first.track())); assertEquals(1, searches)
        now += 8 * 24 * 60 * 60 * 1000L
        restored.resolve(first.track()); assertEquals(2, searches)
        assertEquals(youtube, restored.resolve(youtube))
        val stored = context.getSharedPreferences("spotify_recording_matches_v1", Context.MODE_PRIVATE).all.toString()
        assertTrue(stored.contains("music.youtube.com")); assertFalse(stored.contains("googlevideo"))
    }
    @Test fun unresolvedRecordingDoesNotSilentlyPlayFirstSearchResult() = runBlocking {
        val matcher = SpotifyTrackMatcher(context, { listOf(youtube.copy(title = "Afterimage (Cover)")) })
        val failure = runCatching { matcher.resolve(first.track()) }.exceptionOrNull()
        assertTrue(failure is SourceException); assertTrue(failure!!.message!!.contains("No verified audio match"))
    }
    @Test fun catalogueLoadsLazilyCachesHomeAndKeepsProviderRadioOrderWithoutHistoryCandidates() = runBlocking {
        var now = 1000L
        val api = Api { op, args -> when (op) {
            "profile" -> JSONObject().put("name", "Listener")
            "home" -> home()
            "radio" -> { assertEquals(first.id, args.getString("id")); page(next, first, next, next.copy(id = "c".repeat(22))) }
            else -> error("Unexpected operation $op")
        } }
        val backend = SpotifyRecommendations(context, api, { now })
        assertTrue(backend.verify())
        val feed = backend.home(); backend.home()
        assertEquals(1, api.calls.count { it == "home" }); assertEquals(first.entry(), feed.sections.single().items.single())
        assertEquals(listOf("spotify:${next.id}", "spotify:${"c".repeat(22)}"), backend.radio(first.track(), emptyList()).map { it.id })
        assertTrue(backend.radio(first.track(), listOf("spotify:${next.id}")).none { it.spotifyId == next.id })
        assertEquals(listOf("profile", "home", "radio"), api.calls)
        assertTrue(backend.radio(youtube.copy(source = Source.LOCAL), emptyList()).isEmpty())
        now += 11 * 60 * 1000L; backend.home(); assertEquals(2, api.calls.count { it == "home" })
    }
    @Test fun concurrentHomeRequestsShareOneProviderCall() = runBlocking {
        val wait = CompletableDeferred<JSONObject>()
        val api = Api { op, _ -> if (op == "profile") JSONObject().put("name", "Listener") else wait.await() }
        val backend = SpotifyRecommendations(context, api); backend.verify()
        val a = async(start = CoroutineStart.UNDISPATCHED) { backend.home() }
        val b = async(start = CoroutineStart.UNDISPATCHED) { backend.home() }
        assertEquals(1, api.calls.count { it == "home" }); wait.complete(home())
        assertEquals(a.await(), b.await())
    }
    @Test fun disconnectInvalidatesPendingFeedAndLateSignInCannotRestoreTheAccount() = runBlocking {
        val wait = CompletableDeferred<JSONObject>(); var delayed = false
        val api = Api { op, _ -> when {
            op == "disconnect" -> JSONObject()
            delayed -> wait.await()
            else -> JSONObject().put("name", "Listener")
        } }
        val backend = SpotifyRecommendations(context, api); backend.verify(); delayed = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { backend.home() } }
        backend.disconnect(); wait.complete(home())
        assertTrue(pending.await().exceptionOrNull() is CancellationException); assertFalse(backend.state.value.connected); assertTrue(api.closed)
        val loginWait = CompletableDeferred<JSONObject>()
        val auth = SpotifyRecommendations(context, Api { op, _ -> if (op == "profile") loginWait.await() else JSONObject() })
        val login = async(start = CoroutineStart.UNDISPATCHED) { auth.verify() }
        auth.cancel(); loginWait.complete(JSONObject().put("name", "Stale listener"))
        assertFalse(login.await()); assertFalse(auth.state.value.connected)
        assertFalse(context.getSharedPreferences("spotify_profile", Context.MODE_PRIVATE).getBoolean("connected", false))
    }
    @Test fun expiredAccountShowsSafeErrorAndDoesNotReturnCachedPersonalData() = runBlocking {
        var expire = false
        val api = Api { op, _ -> when {
            expire -> throw SpotifyCatalogueException("AUTH_REQUIRED")
            op == "profile" -> JSONObject().put("name", "Listener")
            else -> home()
        } }
        val backend = SpotifyRecommendations(context, api); backend.verify(); backend.home(); expire = true
        assertTrue(runCatching { backend.collection(first.entry()) }.exceptionOrNull() is SpotifyCatalogueException)
        assertFalse(backend.state.value.connected)
        assertTrue(runCatching { backend.home() }.exceptionOrNull() is SpotifyCatalogueException)
        assertEquals(1, api.calls.count { it == "home" })
    }
    @Test fun browserAndNavigationStayOnHttpsSpotifyAndRejectUserinfoAndImpostors() {
        assertTrue(SpotifyWebRuntime.playerOrigin("https://open.spotify.com/"))
        assertTrue(SpotifyWebRuntime.navigationAllowed(Uri.parse("https://accounts.spotify.com/en/login")))
        for (url in listOf("http://open.spotify.com/", "https://open.spotify.com.attacker.test/", "https://user@open.spotify.com/", "https://open.spotify.com:444/", "file:///tmp/audio", "javascript:alert(1)")) {
            assertFalse(url, SpotifyWebRuntime.playerOrigin(url)); assertFalse(url, SpotifyWebRuntime.navigationAllowed(Uri.parse(url)))
        }
        assertFalse(SpotifyCatalogueException("cookie=PRIVATE").message!!.contains("PRIVATE"))
    }
}
