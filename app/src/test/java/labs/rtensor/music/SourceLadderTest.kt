package labs.rtensor.music

import labs.rtensor.music.data.Master
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Track
import org.junit.Assert.*
import org.junit.Test

class SourceLadderTest {
    private val song = Track("yt:abc", "Blinding Lights (Official Audio)", "The Weeknd - Topic", "https://music.youtube.com/watch?v=abc", durationMs = 200_000)
    private fun master(uri: String, channels: Int, rate: Int, depth: Int = 24, mime: String = "audio/flac", atmos: Boolean = false, title: String = "Blinding Lights", duration: Long = 201_500) =
        Master(uri, title, "The Weeknd", duration, channels, rate, depth, mime, atmos)

    @Test fun atmosOutranksEveryStereoMaster() {
        val route = PlaybackRoute.choose(song, listOf(
            master("hires", 2, 192_000), master("atmos", 8, 48_000, mime = "audio/eac3-joc", atmos = true), master("cd", 2, 44_100, 16),
        ))
        assertEquals("atmos", route.master?.uri)
        assertEquals(PlaybackRoute.Kind.ATMOS, route.kind)
        assertEquals("Atmos · 7.1 bed → DMR", route.label)
    }

    @Test fun losslessPicksTheHighestSampleRateThenBitDepth() {
        val route = PlaybackRoute.choose(song, listOf(master("96", 2, 96_000), master("192", 2, 192_000), master("cd", 2, 44_100, 16)))
        assertEquals("192", route.master?.uri)
        assertEquals("Lossless 24/192 · FLAC · stereo", route.label)
    }

    @Test fun lossyFilesAndOtherSongsFallBackToYouTube() {
        assertNull(PlaybackRoute.choose(song, listOf(master("mp3", 2, 44_100, mime = "audio/mpeg"))).master)
        assertNull(PlaybackRoute.choose(song, listOf(master("other", 2, 96_000, title = "Save Your Tears"))).master)
        assertNull(PlaybackRoute.choose(song, listOf(master("extended", 2, 96_000, duration = 260_000))).master)
        assertEquals("YouTube", PlaybackRoute.choose(song, emptyList()).label)
    }

    @Test fun multichannelAtRatesTheRendererCannotTakeIsSkipped() {
        val route = PlaybackRoute.choose(song, listOf(master("51-441", 6, 44_100), master("stereo", 2, 96_000)))
        assertEquals("stereo", route.master?.uri)
    }

    @Test fun wrongArtistAndEditionNeverReplaceTheSelectedRecording() {
        assertNull(PlaybackRoute.choose(song, listOf(master("remaster", 2, 192000, title = "Blinding Lights (2025 Remaster)"))).master)
        assertNull(PlaybackRoute.choose(song, listOf(master("live", 2, 192000, title = "Blinding Lights (Live Audio)"))).master)
        val different = master("different", 2, 192000).copy(artist = "The Other Artist")
        assertNull(PlaybackRoute.choose(song, listOf(different)).master)
        assertNull(PlaybackRoute.choose(song, listOf(different.copy(artist = ""))).master)
    }

    @Test fun titlesAreComparedWithoutVideoNoiseOrFeaturedArtists() {
        assertEquals(PlaybackRoute.clean("Blinding Lights"), PlaybackRoute.clean("Blinding Lights (Official Video) [4K]"))
        assertEquals(PlaybackRoute.clean("Beyoncé"), PlaybackRoute.clean("Beyonce"))
        assertEquals(PlaybackRoute.clean("Peaches"), PlaybackRoute.clean("Peaches feat. Daniel Caesar"))
    }
}
