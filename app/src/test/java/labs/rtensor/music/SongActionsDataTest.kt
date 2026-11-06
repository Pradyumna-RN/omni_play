package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import labs.rtensor.music.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongActionsDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = Track("yt:aaaaaaaaaaa", "First", "Artist", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
    private val second = first.copy(id = "yt:bbbbbbbbbbb", title = "Second", url = "https://www.youtube.com/watch?v=bbbbbbbbbbb")
    @Before fun clear() {
        context.getSharedPreferences("music_playlists", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun devicePlaylistsSurviveRestartAndSupportRenameDeduplicationAndDeletion() {
        val store = DevicePlaylists(context)
        val created = store.create("  Favourites  ", listOf(first, first))
        store.add(created.id, first); store.add(created.id, second)
        val reopened = DevicePlaylists(context)
        assertEquals(listOf(first, second), reopened.tracks(created.id))
        assertEquals("Favourites", reopened.lists().single().title)
        assertTrue(reopened.lists(first).single().contains)
        reopened.rename(created.id, "My songs")
        reopened.remove(created.id, 0, first.libraryKey())
        assertEquals(listOf(second), store.tracks(created.id))
        assertEquals("My songs", store.lists().single().title)
        reopened.delete(created.id)
        assertTrue(store.lists().isEmpty())
    }
    @Test fun removingAnImportCannotResurrectItThroughAPlaylistAndStaleIndicesCannotRemoveAnotherSong() {
        val local = first.copy(id = "local:one", url = "content://documents/audio/one", source = Source.LOCAL)
        val store = DevicePlaylists(context)
        val list = store.create("Imported", listOf(local, first, second))
        LibraryStore(context).removeLocal(local)
        assertEquals(listOf(first, second), store.tracks(list.id))
        assertThrows(IllegalStateException::class.java) { store.remove(list.id, 1, first.libraryKey()) }
        assertEquals(listOf(first, second), store.tracks(list.id))
        assertThrows(IllegalArgumentException::class.java) { store.add(list.id, local) }
    }
    @Test fun invalidNamesDoNotCommitAndPlaylistOccurrenceIsNotRecordingIdentity() {
        val store = DevicePlaylists(context)
        assertThrows(IllegalArgumentException::class.java) { store.create("  ", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { store.create("a".repeat(151), emptyList()) }
        assertTrue(store.lists().isEmpty())
        val member = first.copy(playlistItemId = "SECOND_OCCURRENCE")
        assertEquals(first.libraryKey(), member.libraryKey())
        assertEquals(member, Track.fromJson(member.json()))
    }
    @Test fun remoteWritesRequireConfirmationAndCarryTheExactPlaylistOccurrence() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var confirm = false
        val service = YouTubePlaylists { method, args ->
            calls += method to args
            when (method) {
                "playlists" -> JSONObject().put("playlists", JSONArray().put(UserPlaylist("PLowned", "Synced", true).json()))
                "playlist-create" -> JSONObject().put("playlist", UserPlaylist("PLnew", "New", true).json())
                else -> JSONObject().put("success", confirm)
            }
        }
        assertTrue(service.lists().single().remote)
        assertTrue(runCatching { service.add("PLowned", "aaaaaaaaaaa") }.isFailure)
        confirm = true
        service.remove("PLowned", "SECOND_OCCURRENCE")
        assertEquals("SECOND_OCCURRENCE", calls.last().second.getString("itemId"))
        val queue = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa")
        service.create("New", queue)
        assertEquals(queue, calls.last().second.getJSONArray("videoIds").let { ids -> (0 until ids.length()).map(ids::getString) })
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun suggestionsDebounceAndIgnoreAResponseThatOutlivesItsQuery() = runTest {
        val scope = backgroundScope
        val old = CompletableDeferred<List<SearchSuggestion>>()
        val fresh = CompletableDeferred<List<SearchSuggestion>>()
        val requested = mutableListOf<String>(); val published = mutableListOf<Pair<String, List<SearchSuggestion>>>()
        val controller = SearchSuggestions(scope, fetch = { query ->
            requested += query
            withContext(NonCancellable) { if (query == "old") old.await() else fresh.await() }
        }, publish = { query, results, _ -> published += query to results }, debounceMs = 10)
        try {
            controller.query("o"); controller.query("ol"); controller.query("old"); advanceTimeBy(10); runCurrent()
            controller.query("new"); advanceTimeBy(10); runCurrent()
            fresh.complete(listOf(SearchSuggestion("New song", second)))
            old.complete(listOf(SearchSuggestion("Old song", first)))
            runCurrent()
            assertEquals(listOf("old", "new"), requested)
            assertEquals("new" to listOf(SearchSuggestion("New song", second)), published.last())
            assertTrue(published.none { it.second.any { s -> s.text == "Old song" } })
            controller.query("https://youtu.be/aaaaaaaaaaa"); controller.query(""); advanceTimeBy(20); runCurrent()
            assertEquals(listOf("old", "new"), requested)
            assertEquals("" to emptyList<SearchSuggestion>(), published.last())
        } finally { old.complete(emptyList()); fresh.complete(emptyList()); scope.cancel() }
    }
}
