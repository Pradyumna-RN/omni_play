package labs.rtensor.music

import labs.rtensor.music.data.*
import labs.rtensor.music.ui.homeRecommendationGroups
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationGroupsTest {
    private fun song(id: String, artist: String = "Familiar artist") = Track(id, "Song $id", artist,
        "https://music.youtube.com/watch?v=$id", durationMs = 180000)

    @Test fun newSongsByFamiliarArtistsAreStillUnheard() {
        val heard = song("aaaaaaaaaaa")
        val next = song("bbbbbbbbbbb")
        val result = homeRecommendationGroups(MusicState(history = listOf(heard), forYou = listOf(next)))
        assertEquals(listOf(next), result.newToYou)
    }
    @Test fun playedAliasesAndTheCurrentTrackCannotAppearAsUnheard() {
        val heard = song("yt:aaaaaaaaaaa");val current = song("bbbbbbbbbbb");val fresh = song("ccccccccccc")
        val result = homeRecommendationGroups(MusicState(playEvents = listOf(PlayEvent(heard, 1, 10000, false)),
            current = current, forYou = listOf(heard.copy(id = "aaaaaaaaaaa"), current, fresh)))
        assertEquals(listOf(fresh), result.newToYou)
    }
    @Test fun savingWithoutPlayingDoesNotMakeARecordingHeard() {
        val saved = song("aaaaaaaaaaa")
        assertEquals(listOf(saved), homeRecommendationGroups(MusicState(saved = listOf(saved), forYou = listOf(saved))).newToYou)
    }
    @Test fun feedAddsSongsButNeverGenreCollectionsOrLocalImports() {
        val entry = FeedEntry("aaaaaaaaaaa", "A provider song", "Artist", "", "song", "", "aaaaaaaaaaa", 180000, "Artist")
        val playlist = FeedEntry("playlist", "Genre collection", "", "", "playlist", "browse", "")
        val local = song("local").copy(source = Source.LOCAL, url = "content://owned/song")
        val groups = homeRecommendationGroups(MusicState(forYou = listOf(local), feed = listOf(FeedSection("Feed", listOf(entry, playlist)))))
        assertEquals(listOf(entry.asTrack()), groups.newToYou);assertTrue(groups.fromListening.isEmpty())
    }
    @Test fun displayedGroupsDoNotRepeatRecordingsAcrossSections() {
        val pool=(0..11).map { song("recording-$it") }
        val groups=homeRecommendationGroups(MusicState(forYou=pool+pool))
        assertEquals(6,groups.fromListening.size);assertEquals(6,groups.newToYou.size)
        assertEquals(pool,(groups.fromListening+groups.newToYou))
    }
    @Test fun historyAloneNeverBecomesARecommendationList() {
        val groups=homeRecommendationGroups(MusicState(history=listOf(song("aaaaaaaaaaa"))))
        assertTrue(groups.fromListening.isEmpty());assertTrue(groups.newToYou.isEmpty())
    }
}
