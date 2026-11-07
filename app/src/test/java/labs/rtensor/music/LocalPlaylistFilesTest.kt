package labs.rtensor.music

import labs.rtensor.music.imports.LocalPlaylistFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalPlaylistFilesTest {
    @Test fun parentReferencesAreResolvedAgainstThePlaylistsFolder() {
        assertEquals("Music/Songs/a.mp3", LocalPlaylistFiles.relativeTo("Music/Lists/road.m3u", "../Songs/a.mp3"))
        assertEquals("Music/a.mp3", LocalPlaylistFiles.relativeTo("Music/Lists/Deep/road.m3u", "..\\..\\a.mp3"))
    }

    @Test fun plainReferencesAreLeftForTheLookup() {
        assertEquals("a.mp3", LocalPlaylistFiles.relativeTo("Music/Lists/road.m3u", "a.mp3"))
        assertEquals("Songs/a.mp3", LocalPlaylistFiles.relativeTo("Music/Lists/road.m3u", "Songs/a.mp3"))
    }

    @Test fun referencesThatEscapeTheChosenFolderAreNotResolved() {
        assertEquals("../../a.mp3", LocalPlaylistFiles.relativeTo("Music/road.m3u", "../../a.mp3"))
    }

    @Test fun playlistFilesReportedAsAudioStayPlaylists() {
        assertEquals(LocalPlaylistFiles.Kind.Playlist, LocalPlaylistFiles.kindOf("Road Trip.m3u", "audio/x-mpegurl"))
        assertEquals(LocalPlaylistFiles.Kind.Playlist, LocalPlaylistFiles.kindOf("Chill.m3u8", "audio/mpegurl"))
        assertEquals(LocalPlaylistFiles.Kind.Audio, LocalPlaylistFiles.kindOf("a.flac", "application/octet-stream"))
        assertEquals(LocalPlaylistFiles.Kind.Audio, LocalPlaylistFiles.kindOf("a.bin", "audio/mpeg"))
        assertEquals(LocalPlaylistFiles.Kind.Other, LocalPlaylistFiles.kindOf("cover.jpg", "image/jpeg"))
    }
}
