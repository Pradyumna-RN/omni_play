package labs.rtensor.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(when {
            count == 0 -> "Waiting for decoded audio"
            !playing -> "Paused · $count input channels"
            else -> "Decoded input · $count channels"
        }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source.isNotEmpty()) {
            if (source.all { it.position != null }) SpeakerMap(source)
            else {
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
            output.forEachIndexed { i, channel -> OutputMeter(channel.position?.let(::speakerLabel) ?: "Ch ${i + 1}", channel) }
            Text("−60 to 0 dBFS · before device volume", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun location(p: SpeakerPosition): Pair<Float, Float> = when (p) {
    SpeakerPosition.FL -> .22f to .14f; SpeakerPosition.FR -> .78f to .14f
    SpeakerPosition.FC -> .5f to .04f; SpeakerPosition.LFE -> .5f to .29f
    SpeakerPosition.SL -> .03f to .48f; SpeakerPosition.SR -> .97f to .48f
    SpeakerPosition.BL -> .22f to .88f; SpeakerPosition.BR -> .78f to .88f
    SpeakerPosition.BLC -> .4f to .98f; SpeakerPosition.BRC -> .6f to .98f
    SpeakerPosition.TFL -> .35f to .34f; SpeakerPosition.TFR -> .65f to .34f
    SpeakerPosition.TSL -> .3f to .58f; SpeakerPosition.TSR -> .7f to .58f
    SpeakerPosition.TBL -> .35f to .76f; SpeakerPosition.TBR -> .65f to .76f
}

@Composable
private fun SpeakerMap(channels: List<ChannelLevel>) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (channels.size <= 2) 148.dp else 260.dp).padding(top = 14.dp, bottom = 8.dp),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        Icon(Glyph.Listener, "Listener position", Modifier.size(28.dp).align(Alignment.Center),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
        channels.forEach { channel ->
            val p = channel.position ?: return@forEach
            val (x, y) = location(p)
            SpeakerNode(speakerLabel(p), channel, Modifier.absoluteOffset((maxWidth - 42.dp) * x, (maxHeight - 42.dp) * y)
                .size(42.dp).testTag("input-channel-${p.name}"))
        }
    }
}

private fun strength(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f)
private fun dbLabel(db: Float) = if (db <= -60f) "below −60" else db.roundToInt().toString()

@Composable
private fun SpeakerNode(label: String, channel: ChannelLevel, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(modifier.semantics { contentDescription = "$label input, RMS ${dbLabel(channel.rmsDb)} dBFS, peak ${dbLabel(channel.peakDb)} dBFS" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2 - 2.dp.toPx()
            drawCircle(color.copy(alpha = .04f + .38f * strength(channel.rmsDb)), radius)
            drawCircle(outline, radius, style = Stroke(1.dp.toPx()))
            if (channel.peakDb > -60f) drawArc(color, -90f, 360f * strength(channel.peakDb), false,
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx()))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutputMeter(label: String, channel: ChannelLevel) {
    val color = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics {
        contentDescription = "$label output, RMS ${dbLabel(channel.rmsDb)} dBFS, peak ${dbLabel(channel.peakDb)} dBFS"
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(30.dp), fontSize = 11.sp)
        Canvas(Modifier.weight(1f).height(5.dp).clip(CircleShape)) {
            drawRect(background)
            drawRect(color, size = androidx.compose.ui.geometry.Size(size.width * strength(channel.rmsDb), size.height))
            if (channel.peakDb > -60f) {
                val x = (size.width * strength(channel.peakDb)).coerceAtMost(size.width - 1.dp.toPx())
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
        }
        Text(if (channel.peakDb <= -60f) "−∞" else "${channel.peakDb.roundToInt()}", Modifier.width(34.dp).padding(start = 8.dp), fontSize = 11.sp)
    }
}
