package labs.rtensor.music

import labs.rtensor.music.data.DownloadStore
import labs.rtensor.music.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class DownloadStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun track(id: String) = Track("yt:$id", "Song $id", "Artist", "https://www.youtube.com/watch?v=$id")

    private fun save(store: DownloadStore, track: Track, bytes: Int = 10) {
        val file = store.finalFile(track, "m4a"); file.writeBytes(ByteArray(bytes)); store.commit(track, file)
    }

    @Test fun aCommittedSongIsPlayableAndSurvivesRestart() {
        val dir = folder.newFolder("dl")
        val song = track("abc")
        save(DownloadStore(dir), song, 42)
        val reopened = DownloadStore(dir)
        assertTrue(reopened.has(song))
        assertEquals(42L, reopened.song(song)?.bytes)
        assertNotNull(reopened.file(song))
    }

    @Test fun aMissingFileIsDroppedSoOfflineIsNeverPromisedWithoutAudio() {
        val dir = folder.newFolder("dl")
        val song = track("gone")
        val store = DownloadStore(dir)
        save(store, song)
        store.finalFile(song, "m4a").delete()
        assertNull(store.file(song))
        assertFalse(DownloadStore(dir).has(song))
    }

    @Test fun unfinishedDownloadsAreNeverListed() {
        val dir = folder.newFolder("dl")
        val song = track("half")
        val store = DownloadStore(dir)
        store.partFile(song).writeBytes(ByteArray(5))
        assertFalse(store.has(song))
        assertTrue(DownloadStore(dir).all.isEmpty())
    }

    @Test fun removeDeletesTheFile() {
        val dir = folder.newFolder("dl")
        val song = track("x1")
        val store = DownloadStore(dir)
        save(store, song)
        val file = store.finalFile(song, "m4a")
        store.remove(song)
        assertFalse(file.exists()); assertFalse(store.has(song)); assertEquals(0L, store.totalBytes())
    }

    @Test fun fileNamesNeverEscapeTheFolder() {
        val name = DownloadStore.safeName(Track("yt:../../etc/passwd", "t", "a", "u"))
        assertFalse(name.contains("/") || name.contains(".") || name.contains("\\"))
    }

    @Test fun extensionsFollowTheMimeType() {
        assertEquals("webm", DownloadStore.extensionFor("audio/webm; codecs=\"opus\""))
        assertEquals("m4a", DownloadStore.extensionFor("audio/mp4"))
        assertEquals("m4a", DownloadStore.extensionFor(null))
    }
}
