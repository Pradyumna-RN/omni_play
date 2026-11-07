package labs.rtensor.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hum's loading mark: four rounded bars that rise and fall in a travelling wave, like a level meter.
 * It replaces the Material ring wherever the app waits. Reduced motion holds the bars still.
 */
@Composable
internal fun HumSpinner(modifier: Modifier = Modifier.size(22.dp), color: Color = MaterialTheme.colorScheme.primary) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "hum spinner")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val bars = 4
        val gap = size.width * .14f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val wave = if (reduced) .6f else .5f + .5f * sin(2.0 * PI * (phase - i * .17f)).toFloat()
            val height = size.height * (.3f + .7f * wave)
            drawRoundRect(color, Offset(i * (barWidth + gap), (size.height - height) / 2f), Size(barWidth, height), CornerRadius(barWidth / 2f))
        }
    }
}
