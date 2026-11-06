package labs.rtensor.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The press feedback for every tap target: a soft rounded wash instead of Material's square ripple.
 * Rows, chips and cards that are not clipped to a shape used to flash a hard rectangle; this
 * always rounds its own corners, and stays inside any shape the target is clipped to.
 */
internal class RoundedPress(private val colour: Color, private val reduced: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, colour, reduced)
    override fun equals(other: Any?) = other is RoundedPress && other.colour == colour && other.reduced == reduced
    override fun hashCode() = 31 * colour.hashCode() + reduced.hashCode()

    private class Node(private val source: InteractionSource, private val colour: Color, private val reduced: Boolean) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)
        private var presses = 0
        private var focused = false
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> { presses++; alpha.animateTo(PRESSED, tween(if (reduced) 0 else 70)) }
                        is PressInteraction.Release, is PressInteraction.Cancel -> { presses = maxOf(0, presses - 1); settle() }
                        is FocusInteraction.Focus -> { focused = true; settle() }
                        is FocusInteraction.Unfocus -> { focused = false; settle() }
                    }
                }
            }
        }
        private suspend fun settle() {
            alpha.animateTo(if (presses > 0) PRESSED else if (focused) FOCUSED else 0f, tween(if (reduced) 0 else 160))
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            val a = alpha.value
            if (a > 0f) drawRoundRect(colour.copy(alpha = a), cornerRadius = CornerRadius(16.dp.toPx()))
        }
    }

    private companion object { const val PRESSED = .14f; const val FOCUSED = .08f }
}
