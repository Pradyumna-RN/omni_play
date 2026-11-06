package labs.rtensor.music

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import labs.rtensor.music.data.*
import labs.rtensor.music.imports.*
import labs.rtensor.music.spotify.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistImportControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var state = PlaylistImportState()
    private var saved = ""
    private val track = Track("yt:aaaaaaaaaaa", "A song", "Artist", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
    @Before fun clear() { context.getSharedPreferences("music_playlists", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun stop() { scope.cancel() }
    private fun controller(read: suspend (String, JSONObject) -> JSONObject) = PlaylistImportController(context, scope,
        DevicePlaylists(context), YouTubeSource(context), YouTubePlaylists(read), SpotifyRecommendations(context), { true }, { state = it }, { saved = it })
    private fun page(tracks: List<Track>, more: Boolean, skipped: Int = 0) = JSONObject().put("tracks", JSONArray(tracks.map { it.json() })).put("hasMore", more).put("skipped", skipped).put("title", "Original playlist")
    private fun settle(test: () -> Boolean) {
        repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (test()) return; Thread.sleep(10) }
        assertTrue("Import did not complete", test())
    }
    @Test fun allPagesAreReadInAnIsolatedSessionAndOnlyConfirmationSavesTheCopy() {
        val sessions = mutableListOf<String>()
        val importer = controller { method, args ->
            assertEquals("playlist-tracks", method); sessions += args.getString("importSession")
            if (!args.getBoolean("more")) page(listOf(track, track), true) else page(listOf(track.copy(id = "yt:bbbbbbbbbbb", title = "Other")), false, 2)
        }
        importer.show(); importer.choose(PlaylistImportChoice("PLtest", "Account title", "YouTube Music"))
        assertEquals(3, state.preview.single().tracks.size); assertEquals(2, state.unavailable)
        assertEquals(1, sessions.distinct().size); assertTrue(sessions.first().matches(Regex("[a-f0-9-]{36}")))
        assertTrue(DevicePlaylists(context).lists().isEmpty())
        importer.confirm(); settle { !state.visible }
        val list = DevicePlaylists(context).lists().single()
        assertEquals("Original playlist", list.title); assertFalse(list.remote)
        assertEquals(3, DevicePlaylists(context).tracks(list.id).size)
        assertTrue(saved.contains("3 songs")); assertTrue(saved.contains("2 unavailable"))
    }
    @Test fun aLaterPageFailureDoesNotSaveAPartialPlaylist() {
        val importer = controller { _, args -> if (args.getBoolean("more")) throw SourceException("FAIL", "Provider did not finish") else page(listOf(track), true) }
        importer.show(); importer.choose(PlaylistImportChoice("PLtest", "Original", "YouTube Music"))
        assertNotNull(state.error); assertTrue(state.preview.isEmpty()); assertTrue(DevicePlaylists(context).lists().isEmpty()); assertEquals("", saved)
    }
    @Test fun dismissingAnInFlightReadCannotPublishOrSaveItsLateResult() {
        val gate = CompletableDeferred<JSONObject>()
        val importer = controller { _, _ -> gate.await() }
        importer.show(); importer.choose(PlaylistImportChoice("PLtest", "Original", "YouTube Music")); assertTrue(state.busy)
        importer.dismiss(); gate.complete(page(listOf(track), false))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(state.visible); assertTrue(DevicePlaylists(context).lists().isEmpty()); assertEquals("", saved)
    }
}
