package labs.rtensor.music

import labs.rtensor.music.playback.PlaybackPaths
import org.junit.Assert.*
import org.junit.Test

class PlaybackPathsTest {
    @Test fun rejectionsBelongToAQueueItemAndDoNotRepeat() {
        val paths = PlaybackPaths()
        paths.record("song-one", "VISIONOS"); paths.record("song-two", "WEB")
        assertTrue(paths.rejectCurrent("song-one")); assertFalse(paths.rejectCurrent("song-one"))
        assertEquals(setOf("VISIONOS"), paths.rejected("song-one"))
        assertTrue(paths.rejected("song-two").isEmpty())
        paths.record("song-one", "VISIONOS_MUSIC"); assertTrue(paths.rejectCurrent("song-one"))
        assertEquals(setOf("VISIONOS", "VISIONOS_MUSIC"), paths.rejected("song-one"))
    }
    @Test fun callerSnapshotsCannotChangeRejectionsOrOutliveAccountCleanup() {
        val paths = PlaybackPaths(); paths.record("one", "VISIONOS"); paths.rejectCurrent("one")
        val snapshot = paths.rejected("one"); paths.record("one", "WEB"); paths.rejectCurrent("one")
        assertEquals(setOf("VISIONOS"), snapshot)
        paths.clear(); assertNull(paths.current("one")); assertTrue(paths.rejected("one").isEmpty())
    }
    @Test fun arbitraryClientNamesCannotBecomeRetryInstructions() {
        assertTrue(runCatching { PlaybackPaths().record("one", "https://example.test") }.isFailure)
    }
}
