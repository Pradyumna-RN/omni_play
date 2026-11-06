package labs.rtensor.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Play and pause as one shape: the two pause bars lean into the halves of the play triangle,
 * so the button morphs instead of swapping icons.
 */
@Composable
internal fun PlayPauseMorph(playing: Boolean, tint: Color, size: Dp, reduced: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(if (playing) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = .75f, stiffness = 600f), label = "play pause morph")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        // Play: a right-pointing triangle split down the middle. Pause: two upright bars.
        val left = Path().apply {
            moveTo(mix(.22f, .20f) * w, mix(.14f, .14f) * h)
            lineTo(mix(.52f, .42f) * w, mix(.31f, .14f) * h)
            lineTo(mix(.52f, .42f) * w, mix(.69f, .86f) * h)
            lineTo(mix(.22f, .20f) * w, mix(.86f, .86f) * h)
            close()
        }
        val right = Path().apply {
            moveTo(mix(.52f, .58f) * w, mix(.31f, .14f) * h)
            lineTo(mix(.84f, .80f) * w, mix(.50f, .14f) * h)
            lineTo(mix(.84f, .80f) * w, mix(.50f, .86f) * h)
            lineTo(mix(.52f, .58f) * w, mix(.69f, .86f) * h)
            close()
        }
        drawPath(left, tint)
        drawPath(right, tint)
    }
}

/**
 * A heart that pops when it turns on: it overshoots on a spring while a ring of small
 * sparks flies out and fades.
 */
@Composable
internal fun LikeBurst(on: Boolean, reduced: Boolean, color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(on) {
        if (first) { first = false; return@LaunchedEffect }
        if (on && !reduced) {
            pop.snapTo(.6f)
            burst.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch { burst.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
                pop.animateTo(1f, spring(dampingRatio = .35f, stiffness = Spring.StiffnessMedium))
            }
        } else { pop.snapTo(1f); burst.snapTo(1f) }
    }
    Box(modifier.drawBehind {
        val t = burst.value
        if (t < 1f) {
            val centre = Offset(size.width / 2, size.height / 2)
            val reach = size.minDimension * (.34f + .30f * t)
            for (i in 0 until 8) {
                val a = i * (2 * PI / 8)
                val dot = size.minDimension * .045f * (1f - t)
                drawCircle(color.copy(alpha = (1f - t) * .9f), dot.coerceAtLeast(.5f),
                    Offset(centre.x + (cos(a) * reach).toFloat(), centre.y + (sin(a) * reach).toFloat()))
            }
        }
    }.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) { content() }
}

/**
 * Content cascade: each item fades up a few pixels, a beat after the one before it, the first
 * time it appears. [index] caps at 8 so long lists never wait.
 */
internal fun Modifier.cascade(index: Int, reduced: Boolean): Modifier = composed {
    if (reduced) return@composed this
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, delayMillis = index.coerceIn(0, 8) * 45, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = appear.value
        translationY = (1f - appear.value) * 14.dp.toPx()
    }
}
