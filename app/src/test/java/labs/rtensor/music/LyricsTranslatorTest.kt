package labs.rtensor.music

import labs.rtensor.music.data.LyricsTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsTranslatorTest {
    @Test fun parseJoinsSegmentsAndReadsTheDetectedLanguage() {
        val (text, language) = LyricsTranslator.parse("""[[["Hello\n","नमस्ते\n",null,null,10],["World","दुनिया",null,null,10]],null,"hi"]""")
        assertEquals("Hello\nWorld", text)
        assertEquals("hi", language)
    }

    @Test fun parseRejectsAnEmptyAnswer() {
        val failed = runCatching { LyricsTranslator.parse("""[[["","",null]],null,"hi"]""") }.isFailure
        assertTrue(failed)
    }

    @Test fun chunksBreakAtLineEndsAndKeepEveryLine() {
        val lines = (1..40).map { "line number $it" }
        val parts = LyricsTranslator.chunks(lines.joinToString("\n"), limit = 100)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 120 })
        assertEquals(lines, parts.joinToString("").trimEnd().lines())
    }

    @Test fun languageNamesAreEnglish() {
        assertEquals("Hindi", LyricsTranslator.languageName("hi"))
        assertEquals("", LyricsTranslator.languageName(""))
    }

    @Test fun romanisationIsReadFromTheTransliterationSegment() {
        val body = """[[["I love","ನಾನು ಪ್ರೀತಿ",null,null,10],[null,null,null,"Nānu\nprīti"]],null,"kn"]"""
        assertEquals("Nānu\nprīti", LyricsTranslator.parseRomanisation(body))
        assertNull(LyricsTranslator.parseRomanisation("""[[["Hello","Hello",null,null,10]],null,"en"]"""))
    }

    @Test fun plainLettersDropAccentsAndCapitaliseEachLine() {
        assertEquals("Nanu\nPriti", LyricsTranslator.plainLetters("nānu\nprīti"))
    }
}
