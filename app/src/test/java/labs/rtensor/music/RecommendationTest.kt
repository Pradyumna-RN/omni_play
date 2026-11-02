package labs.rtensor.music

import labs.rtensor.music.data.ArtworkUrl
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.data.Recommender
import labs.rtensor.music.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecommendationTest {
    private val now = 1_800_000_000_000L
    private val day = ListeningHistory.DAY
    private fun t(id: String, artist: String) = Track(id, "Song $id", artist, "https://music.youtube.com/watch?v=$id")
    private fun play(track: Track, daysAgo: Double, skipped: Boolean = false) =
        PlayEvent(track, now - (daysAgo * day).toLong(), if (skipped) 8_000 else 180_000, skipped)

    @Test fun listenCountsAfterThirtySecondsOrHalfAShortSong() {
        assertTrue(ListeningHistory.counts(30_000, 240_000))
        assertFalse(ListeningHistory.counts(12_000, 240_000))
        assertTrue(ListeningHistory.counts(25_000, 45_000))
    }

    @Test fun seedsFavourFrequentRecentSongsByDifferentArtists() {
        val a = t("aaaaaaaaaaa", "Alpha"); val a2 = t("aaaaaaaaaab", "Alpha"); val b = t("bbbbbbbbbbb", "Beta"); val c = t("ccccccccccc", "Gamma")
        val events = listOf(play(a, 0.5), play(a, 1.0), play(a, 2.0), play(a2, 1.0), play(a2, 1.5), play(b, 3.0), play(c, 1.0, skipped = true))
        val seeds = Recommender.seeds(events, now = now)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), seeds.map { it.id })
    }

    @Test fun mixDropsRecentAndSkippedSongsCapsArtistsAndSpreadsThem() {
        val liked = t("lllllllllll", "Loved")
        val recent = t("rrrrrrrrrrr", "Other")
        val skipped = t("sssssssssss", "Other")
        val events = listOf(play(liked, 1.0), play(liked, 2.0), play(recent, 1.0), play(skipped, 10.0, skipped = true))
        val pool1 = listOf(recent, skipped, t("x1xxxxxxxxx", "Loved"), t("x2xxxxxxxxx", "Loved"), t("x3xxxxxxxxx", "Loved"), t("y1yyyyyyyyy", "New"))
        val pool2 = listOf(t("y1yyyyyyyyy", "New"), t("z1zzzzzzzzz", "Fresh"))
        val mix = Recommender.mix(listOf(pool1, pool2), events, now = now)
        val ids = mix.map { it.id }
        assertFalse("recently played is left out", "rrrrrrrrrrr" in ids)
        assertFalse("skipped is left out", "sssssssssss" in ids)
        assertEquals("at most two per artist", 2, mix.count { it.artist == "Loved" })
        assertTrue("songs both radios lead to are included", "y1yyyyyyyyy" in ids)
        assertEquals("y1yyyyyyyyy", ids.first())
        assertTrue("same artist never twice in a row here", mix.zipWithNext().none { (x, y) -> x.artist == y.artist })
    }

    @Test fun historyPersistsGroupsAndRemoves() {
        val file = File.createTempFile("history", ".json").apply { delete() }
        val store = ListeningHistory(file)
        val e1 = play(t("aaaaaaaaaaa", "Alpha"), 0.0); val e2 = play(t("bbbbbbbbbbb", "Beta"), 0.1)
        store.add(e2); store.add(e1)
        val reloaded = ListeningHistory(file)
        assertEquals(listOf(e1, e2), reloaded.events)
        reloaded.remove(e2.startedAt)
        assertEquals(listOf(e1), ListeningHistory(file).events)
        val stats = ListeningHistory.stats(listOf(e1, e2), now)
        assertEquals(2, stats.playsThisWeek); assertEquals(6L, stats.minutesThisWeek)
        file.delete()
    }

    @Test fun thumbnailsAreRequestedAtTheSizeTheyAreDrawn() {
        assertEquals("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", ArtworkUrl.sized("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", 96))
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg", ArtworkUrl.sized("https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg", 144))
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", ArtworkUrl.safe("https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg"))
    }
}
