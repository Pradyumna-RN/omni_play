package labs.rtensor.music

import labs.rtensor.music.data.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeMetadataTest {
    private val metadata = YouTubeMetadata("jNQXAC9IVRw", "YouTube title", "YouTube artist", "https://i.ytimg.com/fixture.jpg", 12000)
    private val track = Track("yt:jNQXAC9IVRw", "Saved title", "Unknown artist", "https://www.youtube.com/watch?v=jNQXAC9IVRw")
    @Test fun youtubeRepairsMissingSavedArtistWithoutGuessingFromTitle() {
        val enriched = metadata.enrich(track)
        assertEquals("YouTube artist", enriched.artist)
        assertEquals("Saved title", enriched.title)
        assertEquals(track.id, enriched.id)
        assertEquals(track.url, enriched.url)
        assertEquals(12000L, enriched.durationMs)
    }
    @Test fun existingProviderArtistAndRecordingIdentityArePreserved() {
        assertEquals("Existing artist", metadata.enrich(track.copy(artist = "Existing artist")).artist)
        val other = track.copy(id = "yt:aaaaaaaaaaa")
        assertSame(other, metadata.enrich(other))
    }
    @Test fun youtubeMetadataCannotRelabelLocalFiles() {
        val local = track.copy(source = Source.LOCAL)
        assertSame(local, metadata.enrich(local))
    }
}
