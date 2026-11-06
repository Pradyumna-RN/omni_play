package labs.rtensor.music

import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.ui.Ink
import labs.rtensor.music.ui.MinMarkContrast
import labs.rtensor.music.ui.MinTextContrast
import labs.rtensor.music.ui.Paper
import labs.rtensor.music.ui.RoomStage
import labs.rtensor.music.ui.accentColours
import labs.rtensor.music.ui.classicLook
import labs.rtensor.music.ui.contrastRatio
import labs.rtensor.music.ui.lookFor
import labs.rtensor.music.ui.markOn
import labs.rtensor.music.ui.roomLook
import labs.rtensor.music.ui.softLook
import labs.rtensor.music.ui.SoftRaspberry
import labs.rtensor.music.ui.SoftRaspberryNight
import labs.rtensor.music.ui.softContainer
import labs.rtensor.music.ui.softOnPrimary
import labs.rtensor.music.ui.stageLight
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WCAG contrast of the Listening room tokens, dark and light: 4.5:1 for text, 3:1 for marks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LookTokensTest {
    private val fixed = AccentChoice.entries - AccentChoice.COVER

    @Test fun listeningRoomTextMeetsAaOnEveryGroundInBothThemes() {
        for (dark in listOf(true, false)) {
            val look = roomLook(dark)
            val theme = if (dark) "dark" else "light"
            for ((name, ground) in listOf("background" to look.background, "panel" to look.panel)) {
                assertTrue("$theme text on $name", contrastRatio(look.text, ground) >= MinTextContrast)
                assertTrue("$theme muted on $name", contrastRatio(look.muted, ground) >= MinTextContrast)
            }
            // The stage and dock stay dark in both themes.
            for (ground in listOf(look.stage, RoomStage)) {
                assertTrue("$theme stage text", contrastRatio(look.stageText, ground) >= MinTextContrast)
                assertTrue("$theme stage muted", contrastRatio(look.stageMuted, ground) >= MinTextContrast)
            }
        }
    }

    @Test fun listeningRoomMarksMeetThreeToOneForEveryFixedAccent() {
        for (dark in listOf(true, false)) for (choice in fixed) {
            val look = roomLook(dark)
            val accent = accentColours(choice, dark, null)
            val label = "${choice.wire} ${if (dark) "dark" else "light"}"
            // Speaker lights and dock lights on the dark stage.
            assertTrue("$label lights on the stage", contrastRatio(accent.onDock, look.stage) >= MinMarkContrast)
            assertTrue("$label lights on the room stage", contrastRatio(stageLight(accent.onDock), RoomStage) >= MinMarkContrast)
            // Meter bars and pill edges on the page.
            val mark = markOn(accent.accent, look.background, look.text)
            assertTrue("$label mark on the page", contrastRatio(mark, look.background) >= MinMarkContrast)
            assertTrue("$label text on the accent", contrastRatio(accent.onAccent, accent.accent) >= MinTextContrast)
            // Muted icons are marks too.
            assertTrue("$label muted icon", contrastRatio(look.muted, look.background) >= MinMarkContrast)
        }
    }

    @Test fun selectorBoundaryAndChoiceStandOutInBothLooksAndThemes() {
        for (look in listOf(roomLook(false), roomLook(true), softLook(false), softLook(true), classicLook(false), classicLook(true))) {
            val label = "${look.choice} ${if (look.dark) "dark" else "light"}"
            assertTrue("$label outline against the card", contrastRatio(look.selectorEdge, look.cardFill) >= MinMarkContrast)
            assertTrue("$label chosen thumb against the track", contrastRatio(look.selectorThumb, look.selectorTrack) >= MinMarkContrast)
            assertTrue("$label chosen label on the thumb", contrastRatio(look.onSelectorThumb, look.selectorThumb) >= MinTextContrast)
            assertTrue("$label other labels on the track", contrastRatio(look.muted, look.selectorTrack) >= MinTextContrast)
        }
    }

    /** Soft is 0.3.31's palette; only the raspberry moved, in lightness only, to reach 4.5:1 as text. */
    @Test fun softTextAndMarksMeetContrastInBothThemes() {
        for (dark in listOf(false, true)) {
            val look = softLook(dark)
            val theme = if (dark) "dark" else "light"
            val primary = if (dark) SoftRaspberryNight else SoftRaspberry
            val grounds = listOf("background" to look.background, "surface" to look.surface, "panel" to look.panel, "container" to softContainer(dark))
            for ((name, ground) in grounds) {
                assertTrue("$theme text on $name", contrastRatio(look.text, ground) >= MinTextContrast)
                assertTrue("$theme muted on $name", contrastRatio(look.muted, ground) >= MinTextContrast)
                assertTrue("$theme raspberry text on $name", contrastRatio(primary, ground) >= MinTextContrast)
                assertTrue("$theme raspberry mark on $name", contrastRatio(primary, ground) >= MinMarkContrast)
            }
            assertTrue("$theme text on raspberry", contrastRatio(softOnPrimary(dark), primary) >= MinTextContrast)
            assertTrue("$theme dock text", contrastRatio(look.stageText, look.stage) >= MinTextContrast)
            assertTrue("$theme dock muted", contrastRatio(look.stageMuted, look.stage) >= MinTextContrast)
        }
        // The two moved colours stay close to the originals: same hue, lightness changed only a little.
        assertEquals(0xFFC82658.toInt(), SoftRaspberry.toArgb()); assertEquals(0xFFF1688F.toInt(), SoftRaspberryNight.toArgb())
    }

    @Test fun classicKeepsTheSignalPaletteAndThreeLooksExist() {
        assertEquals(Paper, classicLook(false).background); assertEquals(Ink, classicLook(false).text)
        assertEquals(Ink, classicLook(true).background); assertEquals(Paper, classicLook(true).text)
        assertEquals(listOf(LookChoice.HUM, LookChoice.ROOM, LookChoice.SOFT, LookChoice.CLASSIC), LookChoice.entries)
        assertTrue(lookFor(LookChoice.CLASSIC, false).classic)
        assertTrue(lookFor(LookChoice.ROOM, true).room)
        assertEquals("RETRY", classicLook(false).label("Retry")); assertEquals("Retry", roomLook(false).label("Retry"))
        assertEquals("Explore.", classicLook(true).headline("Explore")); assertEquals("Explore", roomLook(true).headline("Explore"))
    }
}
