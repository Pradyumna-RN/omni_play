package labs.rtensor.music

import labs.rtensor.music.data.LyricLine
import labs.rtensor.music.data.SyncedLyricsSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedLyricsTest {
    @Test fun `parse reads stamps in any precision, sorts and drops blank lines`() {
        val lines = SyncedLyricsSource.parse("[ar:Someone]\n[00:12.5] second\n[00:01.00] first\n[00:20.123]\n[01:02:03] third")
        assertEquals(listOf(LyricLine(1_000, "first"), LyricLine(12_500, "second"), LyricLine(62_030, "third")), lines)
    }

    @Test fun `a line with several stamps repeats at each time`() {
        val lines = SyncedLyricsSource.parse("[00:10.00][00:30.00] chorus")
        assertEquals(listOf(LyricLine(10_000, "chorus"), LyricLine(30_000, "chorus")), lines)
    }

    @Test fun `active line is the last one that has started`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, SyncedLyricsSource.activeIndex(lines, 500))
        assertEquals(0, SyncedLyricsSource.activeIndex(lines, 1_000))
        assertEquals(1, SyncedLyricsSource.activeIndex(lines, 8_999))
        assertEquals(2, SyncedLyricsSource.activeIndex(lines, 600_000))
        assertEquals(-1, SyncedLyricsSource.activeIndex(emptyList(), 1_000))
    }

    @Test fun `titles lose video and lyric suffixes`() {
        assertEquals("Song", SyncedLyricsSource.cleanTitle("Song (Official Video) [4K]"))
        assertEquals("Song (feat. Other)", SyncedLyricsSource.cleanTitle("Song (feat. Other)"))
    }
}
