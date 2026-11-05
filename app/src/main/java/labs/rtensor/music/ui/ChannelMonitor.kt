package labs.rtensor.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import labs.rtensor.dsp.AudioLevelFrame
import labs.rtensor.dsp.ChannelLevel
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.dsp.SpeakerPosition
import kotlin.math.roundToInt

/** Faded meters and rules on the black Signal panel. */
private val PanelFaded = Color(0xFF3A3A37)

/** Own composition/flow: meter updates never recompose the surrounding player. */
@Composable
internal fun ChannelMonitor(
    report: DownmixReport?, playing: Boolean, trackId: String,
    levels: StateFlow<AudioLevelFrame?>?, onMetering: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The monitor is always the black Signal panel, in light and dark themes alike: lit
    // channels in the accent, faded ones in dark grey, text in paper.
    val panel = MaterialTheme.colorScheme.copy(
        primary = SignalAccent, onPrimary = Ink, surface = Ink, onSurface = Paper,
        onSurfaceVariant = PaperMuted, outline = PanelFaded, outlineVariant = InkRule,
    )
    MaterialTheme(colorScheme = panel, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = Ink, contentColor = Paper) {
            Column {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = if (expanded) "Collapse channel monitor" else "Expand channel monitor" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 6.dp, height = 18.dp).background(SignalAccent))
                    Text("Channel monitor", Modifier.weight(1f).padding(start = 10.dp), style = MonoLabel, color = PaperMuted)
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Paper)
                }
                if (expanded) MonitorReadings(report, playing, trackId, levels, onMetering)
            }
        }
    }
}

@Composable
private fun MonitorReadings(report: DownmixReport?, playing: Boolean, trackId: String,
    levels: StateFlow<AudioLevelFrame?>?, onMetering: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val observe by rememberUpdatedState(onMetering)
    DisposableEffect(owner, playing, trackId) {
        fun update() { observe(playing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val listener = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(listener); update()
        onDispose { owner.lifecycle.removeObserver(listener); observe(false) }
    }
    val received = levels?.collectAsStateWithLifecycle()?.value
    var fresh by remember { mutableStateOf(false) }
    LaunchedEffect(received, playing, trackId) {
        fresh = playing && received != null && System.nanoTime() - received.measuredAtNs in 0..500_000_000L
        if (fresh) { delay(500); fresh = false }
    }
    val frame = received?.takeIf { fresh && playing }
    val positions = report?.inputPositions.orEmpty()
    val count = report?.sourceChannels ?: 0
    val source = if (frame?.input?.size == count) frame.input else List(count.coerceIn(0, 16)) {
        ChannelLevel(positions.getOrNull(it), -120f, -120f)
    }
    val original = report?.listeningMode == ListeningMode.ORIGINAL
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(when {
            count == 0 -> "Waiting for decoded audio"
            !playing -> "Paused · $count input channels"
            original -> "Original · front L/R of $count decoded channels"
            else -> "Processed · $count decoded channels into the renderer"
        }, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source.isNotEmpty()) {
            if (source.all { it.position != null }) {
                SpeakerMap(source, original)
                Text(if (original) "Faded: decoded but not played in Original" else "Lit: channels the renderer is mixing",
                    style = MonoSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Dashed: height · Outline: not in source · Lrc/Rrc: rear centres",
                    style = MonoSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Speaker positions unavailable", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                source.chunked(4).forEachIndexed { row, channels ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        channels.forEachIndexed { i, ch -> SpeakerNode("Ch ${row * 4 + i + 1}", ch, Modifier.size(44.dp)) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
            val output = frame?.output ?: List(report?.outputChannels?.coerceIn(0, 16) ?: 0) {
                ChannelLevel(if (report?.outputChannels == 2) listOf(SpeakerPosition.FL, SpeakerPosition.FR)[it] else null, -120f, -120f)
            }
            Text(if (output.size == 2) "Stereo output" else "Sink output · ${output.size} channels", style = MonoLabel)
            Text(if (original) "Source front L/R, unprocessed" else "Renderer stereo downmix",
                style = MonoSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            output.forEachIndexed { i, channel -> OutputMeter(channel.position?.let(::speakerLabel) ?: "Ch ${i + 1}", channel) }
            Text("−60 to 0 dBFS · before device volume", style = MonoSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

internal fun speakerLabel(p: SpeakerPosition): String = when (p) {
    SpeakerPosition.FL -> "L"; SpeakerPosition.FR -> "R"; SpeakerPosition.FC -> "C"
    SpeakerPosition.LFE -> "LFE"; SpeakerPosition.SL -> "Ls"; SpeakerPosition.SR -> "Rs"
    SpeakerPosition.BL -> "Lrs"; SpeakerPosition.BR -> "Rrs"
    SpeakerPosition.BLC -> "Lrc"; SpeakerPosition.BRC -> "Rrc"
    SpeakerPosition.TFL -> "Ltf"; SpeakerPosition.TFR -> "Rtf"
    SpeakerPosition.TSL -> "Ltm"; SpeakerPosition.TSR -> "Rtm"
    SpeakerPosition.TBL -> "Ltr"; SpeakerPosition.TBR -> "Rtr"
}

/** 9.1.6-style plan view: front at the top, listener in the middle. Physical left is always on the left. */
private fun location(p: SpeakerPosition): Pair<Float, Float> = when (p) {
    SpeakerPosition.FC -> .5f to 0f
    SpeakerPosition.FL -> .2f to .08f; SpeakerPosition.FR -> .8f to .08f
    SpeakerPosition.LFE -> .5f to .2f
    SpeakerPosition.TFL -> .3f to .25f; SpeakerPosition.TFR -> .7f to .25f
    SpeakerPosition.SL -> 0f to .47f; SpeakerPosition.SR -> 1f to .47f
    SpeakerPosition.TSL -> .27f to .47f; SpeakerPosition.TSR -> .73f to .47f
    SpeakerPosition.TBL -> .3f to .7f; SpeakerPosition.TBR -> .7f to .7f
    SpeakerPosition.BL -> .12f to .9f; SpeakerPosition.BR -> .88f to .9f
    SpeakerPosition.BLC -> .38f to 1f; SpeakerPosition.BRC -> .62f to 1f
}

private val SpeakerPosition.height get() = this in setOf(SpeakerPosition.TFL, SpeakerPosition.TFR,
    SpeakerPosition.TSL, SpeakerPosition.TSR, SpeakerPosition.TBL, SpeakerPosition.TBR)

/** How a position takes part in what the listener hears right now. */
private enum class NodeState { ACTIVE, NOT_PLAYED, ABSENT }

@Composable
private fun SpeakerMap(channels: List<ChannelLevel>, original: Boolean) {
    val byPosition = channels.associateBy { it.position }
    BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp).padding(top = 14.dp, bottom = 10.dp),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        Icon(Glyph.Listener, "Listener position", Modifier.size(28.dp).align(Alignment.Center),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
        SpeakerPosition.entries.forEach { p ->
            val (x, y) = location(p)
            val channel = byPosition[p]
            val state = when {
                channel == null -> NodeState.ABSENT
                original && p != SpeakerPosition.FL && p != SpeakerPosition.FR -> NodeState.NOT_PLAYED
                else -> NodeState.ACTIVE
            }
            val tag = if (channel != null) "input-channel-${p.name}" else "absent-channel-${p.name}"
            SpeakerNode(speakerLabel(p), channel ?: ChannelLevel(p, -120f, -120f),
                Modifier.absoluteOffset((maxWidth - 40.dp) * x, (maxHeight - 40.dp) * y).size(40.dp).testTag(tag),
                state = state, dashed = p.height, square = p == SpeakerPosition.LFE)
        }
    }
}

private fun strength(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f)
private fun dbLabel(db: Float) = if (db <= -60f) "below −60" else db.roundToInt().toString()

@Composable
private fun SpeakerNode(label: String, channel: ChannelLevel, modifier: Modifier,
    state: NodeState = NodeState.ACTIVE, dashed: Boolean = false, square: Boolean = false) {
    val color = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val description = when (state) {
        NodeState.ABSENT -> "$label, not in this source"
        NodeState.NOT_PLAYED -> "$label input, not played in Original, RMS ${dbLabel(channel.rmsDb)} dBFS"
        NodeState.ACTIVE -> "$label input, RMS ${dbLabel(channel.rmsDb)} dBFS, peak ${dbLabel(channel.peakDb)} dBFS"
    }
    val fade = when (state) { NodeState.ACTIVE -> 1f; NodeState.NOT_PLAYED -> .45f; NodeState.ABSENT -> .3f }
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 2.dp.toPx()
            val radius = size.minDimension / 2 - inset
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            val corner = CornerRadius.Zero
            val line = Stroke(1.2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
            if (state != NodeState.ABSENT) {
                val fill = (if (state == NodeState.ACTIVE) color else outline).copy(alpha = .06f + .4f * strength(channel.rmsDb))
                if (square) drawRoundRect(fill, Offset(inset, inset), box, corner) else drawCircle(fill, radius)
            }
            val edge = (if (state == NodeState.ACTIVE) ink else outline).copy(alpha = fade)
            if (square) drawRoundRect(edge, Offset(inset, inset), box, corner, style = line) else drawCircle(edge, radius, style = line)
            if (state == NodeState.ACTIVE && channel.peakDb > -60f) {
                if (square) drawRoundRect(color, Offset(inset, inset), box, corner, style = Stroke(2.dp.toPx()), alpha = strength(channel.peakDb))
                else drawArc(color, -90f, 360f * strength(channel.peakDb), false,
                    topLeft = Offset(inset, inset), size = Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx()))
            }
        }
        Text(label, style = MonoSmall, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = fade))
    }
}

@Composable
private fun OutputMeter(label: String, channel: ChannelLevel) {
    val color = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics {
        contentDescription = "$label output, RMS ${dbLabel(channel.rmsDb)} dBFS, peak ${dbLabel(channel.peakDb)} dBFS"
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(30.dp), style = MonoSmall, fontSize = 11.sp)
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRect(background)
            drawRect(color, size = androidx.compose.ui.geometry.Size(size.width * strength(channel.rmsDb), size.height))
            if (channel.peakDb > -60f) {
                val x = (size.width * strength(channel.peakDb)).coerceAtMost(size.width - 1.dp.toPx())
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
        }
        Text(if (channel.peakDb <= -60f) "−∞" else "${channel.peakDb.roundToInt()}", Modifier.width(34.dp).padding(start = 8.dp), style = MonoSmall, fontSize = 11.sp)
    }
}
