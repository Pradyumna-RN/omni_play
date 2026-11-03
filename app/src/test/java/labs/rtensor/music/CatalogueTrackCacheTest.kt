package labs.rtensor.music

import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.CatalogueTrackCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogueTrackCacheTest {
    private val track = Track("yt:aaaaaaaaaaa", "Afterimage", "Serein", "https://www.youtube.com/watch?v=aaaaaaaaaaa")
    @Test fun unchangedProgressTicksReuseTheDecodedCatalogueTrack() {
        val cache = CatalogueTrackCache(); val json = track.json().toString()
        val first = cache.read(json)
        assertNotNull(first)
        repeat(1000) { assertSame(first, cache.read(json)) }
    }
    @Test fun artistEditsToTheSameSongInvalidateTheCache() {
        val cache = CatalogueTrackCache()
        val first = cache.read(track.json().toString())
        val edited = cache.read(track.copy(artist = "New artist").json().toString())
        assertNotSame(first, edited); assertEquals("New artist", edited!!.artist)
        assertEquals(first!!.id, edited.id)
    }
    @Test fun emptyOrMalformedControllerExtrasNeverReuseThePreviousSong() {
        val cache = CatalogueTrackCache()
        assertNotNull(cache.read(track.json().toString())); assertNull(cache.read(null))
        assertNotNull(cache.read(track.json().toString())); assertNull(cache.read("{"))
        assertNotNull(cache.read(track.json().toString()))
    }
}
