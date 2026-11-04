package labs.rtensor.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
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

/** Own composition/flow: meter updates never recompose the surrounding player. */
@Composable
internal fun ChannelMonitor(
    report: DownmixReport?, playing: Boolean, trackId: String,
    levels: StateFlow<AudioLevelFrame?>?, onMetering: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)
                .semantics { contentDescription = if (expanded) "Collapse channel monitor" else "Expand channel monitor" },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.ChannelMonitor, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Channel monitor", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
            }
            if (expanded) MonitorReadings(report, playing, trackId, levels, onMetering)
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
    // ORIGINAL also describes unprocessed bypass. Only the native comparison
    // removes the other channels; an Android bypass retains the original PCM.
    val frontOnly = report?.comparisonAvailable == true && report.listeningMode == ListeningMode.ORIGINAL
    val processed = report?.comparisonAvailable == true && report.processingEnabled
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(when {
            count == 0 -> "Waiting for decoded audio"
            !playing -> "Paused · $count input channels"
            frontOnly -> "Original · front L/R of $count decoded channels"
            processed -> "Processed · $count decoded channels into the renderer"
            else -> "Original audio · $count decoded channels"
        }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source.isNotEmpty()) {
            if (source.all { it.position != null }) {
                SpeakerMap(source, frontOnly)
                Text(if (frontOnly) "Faded: decoded but not played in Original" else "Light follows audio activity",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Dashed: height · Outline: not in source · Lrc/Rrc: rear centres",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(if (output.size == 2) "Stereo output" else "Sink output · ${output.size} channels", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(when { frontOnly -> "Front L/R comparison · shared gain and delay"; processed -> "Renderer stereo downmix"; else -> "Original PCM · no DMR" },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            output.chunked(4).forEachIndexed { row, channels ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    channels.forEachIndexed { i, channel ->
                        val label = channel.position?.let(::speakerLabel) ?: "Ch ${row * 4 + i + 1}"
                        SpeakerNode(label, channel, Modifier.size(40.dp).testTag("output-channel-${row * 4 + i}"), output = true)
                    }
                }
            }
            }
            Text("Measured activity · before device volume", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private val SpeakerPosition.height get() = id >= SpeakerPosition.TFL.id

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

/** Absolute activity threshold; silent channels never light up by normalization. */
internal fun channelGlow(channel: ChannelLevel): Float {
    val level = maxOf(channel.rmsDb, channel.peakDb - 6f)
    return if (level.isFinite()) ((level + 60f) / 60f).coerceIn(0f, 1f) else 0f
}
private fun dbLabel(db: Float) = if (db <= -60f) "below −60" else db.roundToInt().toString()

@Composable
private fun SpeakerNode(label: String, channel: ChannelLevel, modifier: Modifier,
    state: NodeState = NodeState.ACTIVE, dashed: Boolean = false, square: Boolean = false, output: Boolean = false) {
    val color = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val description = when (state) {
        NodeState.ABSENT -> "$label, not in this source"
        NodeState.NOT_PLAYED -> "$label input, not played in Original, RMS ${dbLabel(channel.rmsDb)} dBFS"
        NodeState.ACTIVE -> "$label ${if (output) "output" else "input"}, RMS ${dbLabel(channel.rmsDb)} dBFS, peak ${dbLabel(channel.peakDb)} dBFS"
    }
    val fade = when (state) { NodeState.ACTIVE -> 1f; NodeState.NOT_PLAYED -> .45f; NodeState.ABSENT -> .3f }
    // The geometry stays fixed. Animation only changes opacity/brightness, and
    // reads in the draw/layer scopes so the player isn't recomposed each frame.
    val glow = animateFloatAsState(if (state == NodeState.ACTIVE) channelGlow(channel) else 0f,
        tween(100, easing = LinearEasing), label = "Channel activity")
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 2.dp.toPx()
            val radius = size.minDimension / 2 - inset
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            val corner = CornerRadius(8.dp.toPx())
            val line = Stroke(1.2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
            if (state != NodeState.ABSENT) {
                val fill = color.copy(alpha = .025f + .5f * glow.value)
                if (square) drawRoundRect(fill, Offset(inset, inset), box, corner) else drawCircle(fill, radius)
            }
            val edge = if (glow.value > 0f) color.copy(alpha = .25f + .75f * glow.value)
                else outline.copy(alpha = fade)
            if (square) drawRoundRect(edge, Offset(inset, inset), box, corner, style = line) else drawCircle(edge, radius, style = line)
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink,
            modifier = Modifier.graphicsLayer { alpha = if (state == NodeState.ACTIVE) .5f + .5f * glow.value else fade })
    }
}
