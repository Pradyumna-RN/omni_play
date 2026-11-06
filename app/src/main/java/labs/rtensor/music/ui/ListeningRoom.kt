package labs.rtensor.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import labs.rtensor.dsp.AudioLevelFrame
import labs.rtensor.dsp.ChannelLevel
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.SpeakerPosition
import labs.rtensor.music.MusicState
import java.util.Locale

/**
 * What the room on Home draws: the source's own speakers while processing renders them, and
 * otherwise (processing off, unavailable, plain stereo, nothing decoded yet) the plain stereo pair.
 * [processed] says whether what you hear is the processed render.
 */
internal data class RoomLayout(val positions: List<SpeakerPosition>, val name: String, val processed: Boolean)

private val StereoPair = listOf(SpeakerPosition.FL, SpeakerPosition.FR)

internal fun roomLayout(state: MusicState): RoomLayout {
    val audio = state.audioProcessing
    val report = audio.report
    val processed = audio.available && state.loadingTrack == null && report != null && report.comparisonAvailable && report.processingEnabled
    return if (processed && report!!.inputPositions.size > 2) RoomLayout(report.inputPositions, layoutName(report) ?: "${report.inputPositions.size} ch", true)
    else RoomLayout(StereoPair, "stereo", processed)
}

/** A level is fresh for half a second; older frames (a stalled meter) are not shown as live. */
private fun AudioLevelFrame.fresh(): Boolean = System.nanoTime() - measuredAtNs in 0..500_000_000L

private fun AudioLevelFrame.levelAt(p: SpeakerPosition): ChannelLevel? =
    input.firstOrNull { it.position == p } ?: output.firstOrNull { it.position == p }

/**
 * Ask for live levels while [active], and only while the app is in front; let go when leaving.
 * The request goes through [claims], shared with the channel monitor.
 */
@Composable
internal fun LiveMetering(owner: String, active: Boolean, claims: MeteringClaims) {
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, active, claims) {
        fun update() { claims.claim(owner, active && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val listener = LifecycleEventObserver { _, _ -> update() }
        lifecycle.lifecycle.addObserver(listener); update()
        onDispose { lifecycle.lifecycle.removeObserver(listener); claims.claim(owner, false) }
    }
}

/**
 * The room at the top of the Listening room's Home: a dark stage, glowing from the bottom in the
 * accent, with the speakers of what you are hearing placed as in the channel monitor's plan view.
 * While a song plays they are lit in the accent; with live levels (when metering runs) each
 * speaker's brightness follows its channel, otherwise it stays steady, and reduced motion keeps
 * it steady too. The song at the bottom opens the player. With nothing playing the stage is calm
 * and says what to do.
 */
@Composable
internal fun ListeningRoom(state: MusicState, levels: StateFlow<AudioLevelFrame?>?, metering: MeteringClaims?,
    onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val light = stageLight(LocalAccent.current.onDock)
    val track = state.current
    val layout = roomLayout(state)
    val active = track != null
    val live = active && state.playing && state.loadingTrack == null && !state.reducedMotion && state.developerOptions && levels != null
    // Metering only runs with Developer options (see MusicViewModel.setMetering), so only ask then.
    if (metering != null && state.developerOptions) LiveMetering("room", live, metering)
    val frame: State<AudioLevelFrame?>? = levels?.collectAsStateWithLifecycle()
    val description = if (!active) "Your room, quiet" else "Your room: ${layout.positions.size} speakers, ${layout.name}, lit by the playing song"
    Box(
        modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(22.dp))
            .drawBehind {
                drawRect(RoomStage)
                // The stage glows up from the floor in the accent; quietly when nothing plays.
                val floor = roomFloor(light, active)
                drawRect(Brush.radialGradient(0f to floor, .72f to RoomStage, center = Offset(size.width / 2, size.height), radius = size.width * .78f))
            }
            .semantics { contentDescription = description },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 16.dp).height(108.dp)) {
            val node = 14.dp
            layout.positions.forEach { p ->
                val (x, y) = speakerLocation(p)
                RoomSpeaker(p, active, live, frame, light,
                    Modifier.absoluteOffset((maxWidth - node) * x, (maxHeight - node) * y).size(node).testTag("room-speaker-${p.name}"))
            }
            // Paper, not grey: the label can sit on the glow, where muted grey would be too faint.
            Text(if (active) "you · ${layout.name}" else "you", Modifier.align(Alignment.Center),
                fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = look.stageText)
        }
        if (track != null) {
            val detail = listOf(track.artist, if (layout.processed) "processed" else "original").filter { it.isNotBlank() }.joinToString(" · ")
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(14.dp))
                    // A dark glass under the song keeps its text readable over the brightest glow.
                    .background(RoomStage.copy(alpha = RoomScrim))
                    .songClickable(track, onClick = onOpen)
                    .semantics { contentDescription = "Open the player: ${track.title}, $detail" }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track, Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)), size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // The song is read once, from the row's description above.
                    Text(track.title.uppercase(Locale.getDefault()), Modifier.clearAndSetSemantics {}, style = RoomTitleStyle, color = look.stageText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, Modifier.padding(top = 2.dp).clearAndSetSemantics {}, fontSize = 12.sp, lineHeight = 16.sp, color = look.stageMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(22.dp), tint = look.stageMuted)
            }
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                Text("Pick something to fill the room", fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = look.stageText)
                Text("Search above, or start a song below.", fontSize = 12.sp, lineHeight = 17.sp, color = look.stageMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** How strongly the stage glows from the floor: the accent over the stage, quiet when nothing plays. */
internal fun roomFloor(light: Color, active: Boolean): Color = light.copy(alpha = if (active) .2f else .07f).compositeOver(RoomStage)

/** The dark glass under the now-playing row. */
internal const val RoomScrim = .72f

/** The condensed title in the room's now-playing row. */
private val RoomTitleStyle: TextStyle
    @Composable get() = TextStyle(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp, lineHeight = 20.sp)

/**
 * One speaker light. Front, side and rear speakers are dots, height speakers a ring around a
 * smaller dot, the LFE a rounded square. Brightness is read while drawing, so live levels never
 * recompose Home.
 */
@Composable
private fun RoomSpeaker(p: SpeakerPosition, active: Boolean, live: Boolean, frame: State<AudioLevelFrame?>?, light: Color, modifier: Modifier) {
    val height = p.id >= SpeakerPosition.TFL.id
    val muted = LocalLook.current.stageMuted
    val isLive by rememberUpdatedState(live)
    Canvas(modifier.clearAndSetSemantics {}) {
        val level = if (isLive) frame?.value?.takeIf { it.fresh() }?.levelAt(p) else null
        val bright = when {
            !active -> 0f
            level != null -> .3f + .7f * channelGlow(level)
            height -> .7f
            else -> .95f
        }
        val r = size.minDimension / 2
        if (!active) {
            // Nothing playing: the speakers wait, dimmed.
            drawCircle(muted.copy(alpha = .55f), r * .8f, center, style = Stroke(1.2.dp.toPx()))
            return@Canvas
        }
        val halo = r * 3.6f
        drawCircle(Brush.radialGradient(listOf(light.copy(alpha = .55f * bright), Color.Transparent), center, halo), halo, center)
        val fill = light.copy(alpha = bright)
        when {
            p == SpeakerPosition.LFE -> drawRoundRect(fill, Offset(center.x - r * .8f, center.y - r * .8f), Size(r * 1.6f, r * 1.6f), CornerRadius(r * .35f))
            height -> { drawCircle(fill, r * .55f, center); drawCircle(fill, r * .95f, center, style = Stroke(1.2.dp.toPx())) }
            else -> drawCircle(fill, r, center)
        }
    }
}

/**
 * The player's small level meter: twelve bars across the room from left to right, each
 * following the channels nearest to it. It moves only with live levels; paused, with reduced
 * motion or without metering it rests at an even low line.
 */
@Composable
internal fun LevelMeter(report: DownmixReport?, frame: State<AudioLevelFrame?>?, live: Boolean, colour: Color, modifier: Modifier = Modifier) {
    val isLive by rememberUpdatedState(live)
    val order = remember(report) { report?.inputPositions.orEmpty() }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val bars = 12
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val current = if (isLive) frame?.value?.takeIf { it.fresh() } else null
        // Channels sorted across the room, left to right; the bars interpolate between them.
        val glows = current?.input?.mapIndexed { i, ch -> (ch.position ?: order.getOrNull(i))?.let { speakerLocation(it).first } to channelGlow(ch) }
            ?.sortedBy { it.first ?: .5f }?.map { it.second }.orEmpty()
        for (i in 0 until bars) {
            val value = if (glows.isEmpty()) .22f else {
                val t = i / (bars - 1f) * (glows.size - 1)
                val a = glows[t.toInt().coerceIn(0, glows.lastIndex)]
                val b = glows[(t.toInt() + 1).coerceIn(0, glows.lastIndex)]
                (a + (b - a) * (t - t.toInt())).coerceIn(.08f, 1f)
            }
            val h = size.height * value
            drawRoundRect(colour.copy(alpha = if (current != null) .9f else .45f), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }
    }
}
