package labs.rtensor.music

import labs.rtensor.music.data.*
import org.junit.Assert.*
import org.junit.Test

class ExploreShelvesTest {
    private val song = FeedEntry("yt:aaaaaaaaaaa", "Actual song", "Artist", "", "song", "", "aaaaaaaaaaa")
    private val album = FeedEntry("album:one", "Actual album", "Artist", "", "album", "MPREb_one", "")
    private val nav = FeedEntry("nav", "Rock", "", "", "browse", "FEmusic_category", "", params = "ROCK")
    @Test fun emptyExploreUsesExistingProviderContentForBothTabs() {
        val sections = listOf(FeedSection("Provider picks", listOf(song, album)))
        val content = exploreShelves(emptyList(), sections)
        assertEquals(sections, content.discover)
        assertEquals(listOf(album), content.browse.flatMap { it.items })
    }
    @Test fun categoryLinksDoNotHideAlbumsAndPlaylists() {
        val content = exploreShelves(listOf(FeedSection("Categories", listOf(nav))), listOf(FeedSection("Albums", listOf(album))))
        assertEquals(listOf(nav, album), content.browse.flatMap { it.items })
        assertEquals(listOf(album), content.discover.flatMap { it.items })
    }
    @Test fun songOnlyFeedsStillHaveRealBrowsableContent() {
        val actual = listOf(FeedSection("Actual provider shelf", listOf(song)))
        assertEquals(actual, exploreShelves(emptyList(), actual).browse)
    }
    @Test fun differentCategoryParametersAreNotDeduplicated() {
        val pop = nav.copy(title = "Pop", params = "POP")
        val content = exploreShelves(listOf(FeedSection("Categories", listOf(nav, pop, nav))), emptyList())
        assertEquals(listOf(nav, pop), content.browse.flatMap { it.items })
    }
    @Test fun providerOrderAndFirstOccurrenceArePreserved() {
        val content = exploreShelves(listOf(FeedSection("Discovery", listOf(album))), listOf(FeedSection("Home", listOf(album, song))))
        assertEquals(listOf("Discovery", "Home"), content.discover.map { it.title })
        assertEquals(listOf(album, song), content.discover.flatMap { it.items })
    }
    @Test fun spotifyCollectionsAndSongsAreRecognized() {
        val playlist = album.copy(id = "sp:p", kind = "spotify_playlist")
        val track = song.copy(id = "sp:t", kind = "spotify_track", videoId = "")
        val content = exploreShelves(emptyList(), listOf(FeedSection("Spotify", listOf(playlist, track))))
        assertEquals(listOf(playlist), content.browse.flatMap { it.items })
        assertEquals(listOf(playlist, track), content.discover.flatMap { it.items })
    }
    @Test fun noDataProducesNoInventedItems() {
        assertEquals(ExploreShelves(emptyList(), emptyList()), exploreShelves(emptyList(), emptyList()))
    }
}
