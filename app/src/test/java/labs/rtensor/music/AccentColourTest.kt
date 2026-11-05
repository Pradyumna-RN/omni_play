package labs.rtensor.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.ui.CoverAccent
import labs.rtensor.music.ui.Ink
import labs.rtensor.music.ui.MinMarkContrast
import labs.rtensor.music.ui.MinTextContrast
import labs.rtensor.music.ui.Paper
import labs.rtensor.music.ui.SignalAccent
import labs.rtensor.music.ui.accentColours
import labs.rtensor.music.ui.contrastRatio
import labs.rtensor.music.ui.fixedAccent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentColourTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)
    private val fixed = AccentChoice.entries - AccentChoice.COVER

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun accentChoiceRoundTripsThroughTheSettingsStore() {
        for (choice in AccentChoice.entries) {
            LibraryStore(context).accent = choice
            assertEquals(choice, LibraryStore(context).accent)
            assertEquals(choice.wire, prefs.getString("accent", null))
        }
    }

    @Test fun unknownOrMissingStoredAccentFallsBackToSignal() {
        assertEquals(AccentChoice.SIGNAL, LibraryStore(context).accent)
        prefs.edit().putString("accent", "teal").commit()
        assertEquals(AccentChoice.SIGNAL, LibraryStore(context).accent)
        prefs.edit().putString("accent", "").commit()
        assertEquals(AccentChoice.SIGNAL, LibraryStore(context).accent)
        assertEquals(AccentChoice.SIGNAL, AccentChoice.parse(null))
    }

    @Test fun signalIsUnchangedFromTheOriginalLook() {
        for (dark in listOf(false, true)) {
            val colours = accentColours(AccentChoice.SIGNAL, dark, null)
            assertEquals(Color(0xFFFF5A1F), colours.accent)
            assertEquals(Ink, colours.onAccent)
            assertEquals(Color(0xFFFF5A1F), colours.onDock)
        }
    }

    @Test fun textOnEveryFixedAccentMeetsAaInBothThemes() {
        for (choice in fixed) for (dark in listOf(false, true)) {
            val c = accentColours(choice, dark, null)
            val ratio = contrastRatio(c.accent, c.onAccent)
            assertTrue("$choice dark=$dark on-accent contrast $ratio", ratio >= MinTextContrast)
            assertTrue(c.onAccent == Ink || c.onAccent == Color.White)
        }
    }

    @Test fun everyAccentShowsOnTheBlackDock() {
        for (choice in fixed) for (dark in listOf(false, true)) {
            val c = accentColours(choice, dark, null)
            assertTrue("$choice dark=$dark dock contrast", contrastRatio(c.onDock, Ink) >= MinMarkContrast)
        }
        // Ink on the black dock would vanish: the dock uses paper there.
        assertEquals(Ink, accentColours(AccentChoice.INK, false, null).accent)
        assertEquals(Paper, accentColours(AccentChoice.INK, false, null).onDock)
        assertEquals(Paper, accentColours(AccentChoice.INK, true, null).accent)
        // Oxblood keeps its hue on the dock, only lighter.
        val oxblood = accentColours(AccentChoice.OXBLOOD, false, null)
        assertNotEquals(oxblood.accent, oxblood.onDock)
        assertEquals(hsl(oxblood.accent)[0], hsl(oxblood.onDock)[0], 2f)
    }

    @Test fun coverWithoutColourFallsBackToSignal() {
        assertEquals(SignalAccent, accentColours(AccentChoice.COVER, false, null).accent)
        assertEquals(SignalAccent, accentColours(AccentChoice.COVER, true, Color.Unspecified).accent)
        assertNull("greyscale art has no vivid colour", CoverAccent.extract(solid(0xFF808080.toInt())))
        assertNull("blank art has no colour", CoverAccent.extract(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)))
        assertNull(fixedAccent(AccentChoice.COVER, false))
    }

    @Test fun coverExtractionPicksTheVividColourAndKeepsItsHue() {
        // Mostly grey with a saturated blue block: the blue wins.
        val art = solid(0xFF7A7A7A.toInt()).apply { for (x in 0 until 32) for (y in 0 until 64) setPixel(x, y, 0xFF1030E0.toInt()) }
        val picked = CoverAccent.extract(art)!!
        assertTrue(CoverAccent.fitsBothThemes(picked))
        assertEquals(hsl(Color(0xFF1030E0))[0], hsl(picked)[0], 4f)
    }

    @Test fun coverColoursTooCloseToEitherBackgroundAreAdjusted() {
        for (raw in listOf(0xFFFFF59D, 0xFFFFEB3B, 0xFF0A1450, 0xFF00E5FF, 0xFFFF5A1F, 0xFF3D0000)) {
            val source = Color(raw)
            val adjusted = CoverAccent.adjust(source)!!
            assertTrue("$raw vs paper", contrastRatio(adjusted, Paper) >= MinMarkContrast)
            assertTrue("$raw vs ink", contrastRatio(adjusted, Ink) >= MinMarkContrast)
            val c = accentColours(AccentChoice.COVER, false, adjusted)
            assertTrue("$raw text", contrastRatio(c.accent, c.onAccent) >= MinTextContrast)
            assertEquals("$raw hue", hsl(source)[0], hsl(adjusted)[0], 3f)
        }
        // A pale yellow cover, too close to paper, is extracted darker.
        val pale = CoverAccent.extract(solid(0xFFFFF59D.toInt()))!!
        assertTrue(contrastRatio(pale, Paper) >= MinMarkContrast)
        assertTrue(contrastRatio(Color(0xFFFFF59D), Paper) < MinMarkContrast)
        // A colour that already fits is left alone.
        val fits = CoverAccent.adjust(Color(0xFF2F4BFF))!!
        assertTrue(CoverAccent.fitsBothThemes(fits))
        assertEquals(CoverAccent.adjust(fits), fits)
    }

    private fun solid(argb: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
    private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    private fun assertEquals(expected: Float, actual: Float, degrees: Float) {
        val d = abs(expected - actual).let { min(it, 360 - it) }
        assertTrue("hue $actual differs from $expected", d <= degrees)
    }
    private fun assertEquals(message: String, expected: Float, actual: Float, degrees: Float) {
        val d = abs(expected - actual).let { min(it, 360 - it) }
        assertTrue("$message: hue $actual differs from $expected", d <= degrees)
    }
}
