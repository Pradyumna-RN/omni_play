package labs.rtensor.music

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.*
import labs.rtensor.music.imports.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistImportDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val song = Track("yt:aaaaaaaaaaa", "Song", "Artist", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
    @Before fun clear() { context.getSharedPreferences("music_playlists", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun m3uWithoutHeaderPreservesOrderDurationsAndRepeatedOccurrences() {
        val list = PlaylistDocuments.parse("#EXTINF:90,Artist - Song\nAlbum/song.flac\nAlbum/song.flac\n", "List.m3u8").single()
        assertEquals("List", list.title); assertEquals(2, list.songs.size)
        assertEquals("Song", list.songs.first().title); assertEquals(90000L, list.songs.first().durationMs)
        assertEquals(list.songs.first().location, list.songs.last().location)
    }
    @Test fun spotifyCsvHandlesQuotedCommasNewlinesAndEscapedQuotes() {
        val csv = "Track URI,Track Name,Artist Name(s),Duration (ms)\r\nspotify:track:${"a".repeat(22)},\"A, song\",\"Artist \"\"name\"\"\",180000\r\n"
        val row = PlaylistDocuments.parse(csv, "Spotify.csv").single().songs.single()
        assertEquals("A, song", row.title); assertEquals("Artist \"name\"", row.artist); assertEquals(180000L, row.durationMs)
    }
    @Test fun jsonSupportsSpotifyDataExportWithSeveralPlaylists() {
        val data = """{"playlists":[{"name":"One","items":[{"track":{"trackName":"Song","artistName":"Artist","trackUri":"spotify:track:aaaaaaaaaaaaaaaaaaaaaa"}}]},{"name":"Two","items":[{"track":{"trackName":"Other","artistName":"Artist","trackUri":"spotify:track:bbbbbbbbbbbbbbbbbbbbbb"}}]}]}"""
        val lists = PlaylistDocuments.parse(data, "Export.json")
        assertEquals(listOf("One", "Two"), lists.map { it.title }); assertEquals("Song", lists[0].songs.single().title)
    }
    @Test fun plsUsesNumericOccurrenceOrder() {
        val songs = PlaylistDocuments.parse("[playlist]\nFile2=B.flac\nTitle2=Second\nFile1=A.flac\nLength1=90", "List.pls").single().songs
        assertEquals(listOf("A.flac", "B.flac"), songs.map { it.location }); assertEquals(90000L, songs.first().durationMs)
    }
    @Test fun xspfReadsLocalLocationsWithoutExecutingEntities() {
        val song = PlaylistDocuments.parse("""<playlist xmlns="http://xspf.org/ns/0/"><title>Local</title><trackList><track><location>Album/song.flac</location><title>Song</title><creator>Artist</creator><duration>80000</duration></track></trackList></playlist>""", "List.xspf").single()
        assertEquals("Local", song.title); assertEquals("Artist", song.songs.single().artist)
        assertThrows(IllegalArgumentException::class.java) { PlaylistDocuments.parse("<!DOCTYPE playlist [<!ENTITY steal SYSTEM 'file:///private'>]><playlist/>", "Bad.xspf") }
    }
    @Test fun oversizedAndDeeplyNestedExportsFailBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) { PlaylistDocuments.parse("x".repeat(PlaylistDocuments.MAX_BYTES + 1), "Big.m3u") }
        assertThrows(IllegalArgumentException::class.java) { PlaylistDocuments.parse("[".repeat(42) + "]".repeat(42), "Deep.json") }
        assertThrows(IllegalArgumentException::class.java) { PlaylistDocuments.parse("path\n".repeat(5001), "Big.m3u") }
    }
    @Test fun duplicateBasenamesAreNeverGuessedAndPathsCannotEscapeTheGrantedFolder() {
        val a = Uri.parse("content://music/a"); val b = Uri.parse("content://music/b")
        val files = mapOf("Music/A/song.flac" to a, "Music/B/song.flac" to b)
        assertNull(LocalPlaylistFiles.find("song.flac", files))
        assertEquals(a, LocalPlaylistFiles.find("/storage/emulated/0/Music/A/song.flac", files))
        assertEquals(b, LocalPlaylistFiles.find("B/song.flac", files))
        assertNull(LocalPlaylistFiles.find("../A/song.flac", files))
        assertNull(LocalPlaylistFiles.find("Wrong/song.flac", files))
    }
    @Test fun localReferencesAreNotTreatedAsOnlineMetadataQueries() {
        for (ref in listOf("content://audio/1", "file:///Music/one.flac", "Album/one.flac", "C:\\Music\\one.flac")) assertTrue(ref, PlaylistImportController.localReference(ImportSong(ref)))
        assertFalse(PlaylistImportController.localReference(ImportSong("https://www.youtube.com/watch?v=aaaaaaaaaaa")))
        assertFalse(PlaylistImportController.localReference(ImportSong(title = "Song", artist = "Artist")))
    }
    @Test fun importedCopiesPreserveOccurrencesAndAddingASongDoesNotEraseThem() {
        val store = DevicePlaylists(context)
        val created = store.importCopies(listOf(PlaylistImportPreview("Imported", listOf(song, song))), "Spotify").single()
        assertFalse(created.remote); assertEquals("Spotify", DevicePlaylists(context).lists().single().importedFrom)
        store.add(created.id, song.copy(id = "yt:bbbbbbbbbbb", title = "Other"))
        assertEquals(listOf(song, song, song.copy(id = "yt:bbbbbbbbbbb", title = "Other")), store.tracks(created.id))
        store.remove(created.id, 1, song.libraryKey()); assertEquals(2, store.tracks(created.id).size)
    }
    @Test fun multiPlaylistImportIsAtomicWhenOneDraftIsInvalid() {
        val store = DevicePlaylists(context); val original = store.create("Existing", listOf(song))
        assertThrows(IllegalArgumentException::class.java) { store.importCopies(listOf(PlaylistImportPreview("Valid", listOf(song)), PlaylistImportPreview("", listOf(song))), "File") }
        assertEquals(listOf(original), store.lists())
    }
}
