package labs.rtensor.music.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import labs.rtensor.music.data.LookChoice
import java.util.Locale

/** Listening room, dark: near-black ground, a lifted panel, warm paper text and a muted grey. */
internal val RoomNight = Color(0xFF0F1012)
internal val RoomPanel = Color(0xFF1A1B1F)
internal val RoomPaper = Color(0xFFF2EFE8)
internal val RoomMuted = Color(0xFF9A978F)
/** The room stage behind the speakers, and the dock: dark in both themes, like a stage. */
internal val RoomStage = Color(0xFF15161A)

/** Listening room, light: the same structure on paper. The stage and the dock stay dark. */
internal val RoomDay = Paper
internal val RoomDayPanel = Color(0xFFE2E0D9)
internal val RoomDayInk = Color(0xFF141518)
internal val RoomDayMuted = Color(0xFF5A5751)

/**
 * Everything that differs between the two looks, in one place: colour tokens, shapes and the
 * few wording rules (Classic labels are uppercase DM Mono and its headlines end in a full stop).
 * Screens read it through [LocalLook] instead of checking the look themselves.
 *
 * [background], [panel], [text] and [muted] are the page tokens; [line] is a hairline (decorative,
 * never the only boundary of a control). [stage], [stageText] and [stageMuted] are the always-dark
 * room stage and dock.
 */
internal data class Look(
    val choice: LookChoice,
    val dark: Boolean,
    val background: Color,
    val panel: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val stage: Color,
    val stageText: Color,
    val stageMuted: Color,
    /** Raised surfaces (cards, the Soft dock). Only Soft separates it from the page. */
    val surface: Color = background,
) {
    val classic: Boolean get() = choice == LookChoice.CLASSIC
    val room: Boolean get() = choice == LookChoice.ROOM || choice == LookChoice.HUM
    val hum: Boolean get() = choice == LookChoice.HUM
    val soft: Boolean get() = choice == LookChoice.SOFT

    /** A small label: uppercase in Classic, sentence case in the Listening room. */
    fun label(text: String): String = if (classic) text.uppercase(Locale.ROOT) else text
    /** A big headline: Classic ends it with a full stop. */
    fun headline(text: String): String = if (classic) "$text." else text

    /** What a settings card is filled with: the page colour in Classic, the lifted panel otherwise. */
    val cardFill: Color get() = when (choice) { LookChoice.CLASSIC -> background; LookChoice.SOFT -> surface; LookChoice.ROOM, LookChoice.HUM -> panel }
    /** A segmented selector's track, inside a card: set apart from the card in every look. */
    val selectorTrack: Color get() = if (room) background else panel
    /** The selector's outline: the muted text colour, so its boundary stands out 3:1 from the card. */
    val selectorEdge: Color get() = muted
    /** The chosen option is filled with the text colour and labelled in the page colour. */
    val selectorThumb: Color get() = text
    val onSelectorThumb: Color get() = background

    /** Filled and outlined buttons: square in Classic, pills in the Listening room. */
    val button: Shape get() = when (choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(12.dp); LookChoice.ROOM, LookChoice.HUM -> RoundedCornerShape(percent = 50) }
    /** Bordered square controls such as Save: square in Classic, softly rounded otherwise. */
    val control: Shape get() = if (classic) RectangleShape else RoundedCornerShape(14.dp)
    /** Cards, banners and settings groups. */
    val card: Shape get() = when (choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(20.dp); LookChoice.ROOM -> RoundedCornerShape(16.dp); LookChoice.HUM -> RoundedCornerShape(22.dp) }
    /** Artwork in rows and cards. */
    val art: Shape get() = when (choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(8.dp); LookChoice.ROOM -> RoundedCornerShape(10.dp); LookChoice.HUM -> RoundedCornerShape(14.dp) }
    /** The player's big cover. */
    val cover: Shape get() = if (classic) RectangleShape else RoundedCornerShape(18.dp)
    /** Small square tiles (setting icons, stats). */
    val tile: Shape get() = if (classic) RectangleShape else RoundedCornerShape(10.dp)
    /** The search field. */
    val field: Shape get() = when (choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(14.dp); LookChoice.ROOM -> RoundedCornerShape(24.dp); LookChoice.HUM -> RoundedCornerShape(percent = 50) }
}

/** Hum, Berry: a plum-black night, a pink-tinted paper day, and one hot pink accent. */
internal val HumNight = Color(0xFF150B14)
internal val HumPanel = Color(0xFF241322)
internal val HumDock = Color(0xFF1B0E1A)
internal val HumPaper = Color(0xFFFBF1F7)
internal val HumDayPanel = Color(0xFFF0DCE9)
internal val HumInk = Color(0xFF1E0F1C)
internal val HumMuted = Color(0xFFB49AAE)
internal val HumDayMuted = Color(0xFF6E4F66)
internal val HumPink = Color(0xFFFF4D9D)
internal val HumPinkDay = Color(0xFFC8176F)

internal fun humLook(dark: Boolean): Look = if (dark) Look(
    LookChoice.HUM, dark = true,
    background = HumNight, panel = HumPanel, text = HumPaper, muted = HumMuted, line = Color(0x1FFFFFFF).compositeOver(HumNight),
    stage = HumDock, stageText = HumPaper, stageMuted = HumMuted,
) else Look(
    LookChoice.HUM, dark = false,
    background = HumPaper, panel = HumDayPanel, text = HumInk, muted = HumDayMuted, line = Color(0xFFE4CBDA),
    stage = HumDock, stageText = HumPaper, stageMuted = HumMuted,
)

internal fun roomLook(dark: Boolean): Look = if (dark) Look(
    LookChoice.ROOM, dark = true,
    background = RoomNight, panel = RoomPanel, text = RoomPaper, muted = RoomMuted, line = Color(0xFF2B2C31),
    stage = RoomNight, stageText = RoomPaper, stageMuted = RoomMuted,
) else Look(
    LookChoice.ROOM, dark = false,
    background = RoomDay, panel = RoomDayPanel, text = RoomDayInk, muted = RoomDayMuted, line = Color(0xFFCDCAC2),
    stage = RoomNight, stageText = RoomPaper, stageMuted = RoomMuted,
)

/** Classic keeps the 0.3.32 Signal palette exactly: paper/ink and the black dock. */
internal fun classicLook(dark: Boolean): Look = if (dark) Look(
    LookChoice.CLASSIC, dark = true,
    background = Ink, panel = Color(0xFF1E1E1C), text = Paper, muted = PaperMuted, line = InkRule,
    stage = Ink, stageText = Paper, stageMuted = PaperMuted,
) else Look(
    LookChoice.CLASSIC, dark = false,
    background = Paper, panel = Color(0xFFDCDCD8), text = Ink, muted = Color(0xFF4A4A46), line = Color(0xFFC9C9C4),
    stage = Ink, stageText = Paper, stageMuted = PaperMuted,
)

/**
 * Soft keeps the 0.3.31 palette: warm off-white and ink, or charcoal, with raspberry. Two
 * colours were moved in lightness only, just enough for 4.5:1 text: raspberry is
 * [SoftRaspberry] (was #D72F62) in light and [SoftRaspberryNight] (was #F05B86) in dark.
 */
internal fun softLook(dark: Boolean): Look = if (dark) Look(
    LookChoice.SOFT, dark = true,
    background = Color(0xFF19191C), panel = Color(0xFF302C32), text = Color(0xFFF2F0E9), muted = Color(0xFFB4AFB6), line = Color(0xFF3A373D),
    stage = Color(0xFF222125), stageText = Color(0xFFF2F0E9), stageMuted = Color(0xFFB4AFB6), surface = Color(0xFF222125),
) else Look(
    LookChoice.SOFT, dark = false,
    background = Color(0xFFF9F8F2), panel = Color(0xFFF1EEE7), text = Color(0xFF161921), muted = Color(0xFF555860), line = Color(0xFFDFDDD6),
    stage = Color(0xFFFFFEFA), stageText = Color(0xFF161921), stageMuted = Color(0xFF555860), surface = Color(0xFFFFFEFA),
)

/** Soft's raspberry, light theme: #D72F62 darkened just enough to read 4.5:1 on every Soft ground. */
internal val SoftRaspberry = Color(0xFFC82658)
/** Soft's raspberry, dark theme: #F05B86 lightened just enough for the same. */
internal val SoftRaspberryNight = Color(0xFFF1688F)
/** Text and icons on Soft's raspberry. */
internal fun softOnPrimary(dark: Boolean): Color = if (dark) Color(0xFF240A13) else White
/** Soft's pink container (selected chips, the dock pill, icon tiles, banners). */
internal fun softContainer(dark: Boolean): Color = if (dark) Color(0xFF432733) else Color(0xFFF9E5EB)

internal fun lookFor(choice: LookChoice, dark: Boolean): Look = when (choice) {
    LookChoice.CLASSIC -> classicLook(dark)
    LookChoice.SOFT -> softLook(dark)
    LookChoice.ROOM -> roomLook(dark)
    LookChoice.HUM -> humLook(dark)
}

internal val LocalLook = staticCompositionLocalOf { roomLook(dark = false) }

/** The look's button shape, for the many call sites that used one fixed shape. */
internal val ButtonShape: Shape
    @Composable @ReadOnlyComposable get() = LocalLook.current.button

/**
 * The accent as a thin mark (a meter bar, a lit pill edge) on [ground]: unchanged when it already
 * stands out by 3:1, otherwise moved in lightness only until it does, or the text colour.
 */
internal fun markOn(accent: Color, ground: Color, fallback: Color): Color =
    closestLightness(accent) { contrastRatio(it, ground) >= MinMarkContrast } ?: fallback

/** A soft glow behind a light: a radial fade of [colour] reaching [spread] times the element's half size. */
internal fun Modifier.glow(colour: Color, spread: Float = 1.5f, strength: Float = .42f): Modifier = drawBehind {
    val r = size.maxDimension / 2 * spread
    drawCircle(Brush.radialGradient(listOf(colour.copy(alpha = strength), Color.Transparent), center, r), r, center)
}

/**
 * A light (speaker or dock light) as drawn on the room stage: the accent's dock form, lifted if
 * needed so it stands out 3:1 from [RoomStage], the lightest stage colour.
 */
internal fun stageLight(onDock: Color): Color = markOn(onDock, RoomStage, RoomPaper)
