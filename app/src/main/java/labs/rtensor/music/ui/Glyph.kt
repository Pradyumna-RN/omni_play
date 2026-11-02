package labs.rtensor.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A few app-specific line icons, avoiding a bundle of thousands of unused glyphs. */
internal object Glyph {
    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val Compass = outline("Explore") {
        moveTo(22f, 12f); curveTo(22f, 17.52f, 17.52f, 22f, 12f, 22f); curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
        curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f); curveTo(17.52f, 2f, 22f, 6.48f, 22f, 12f); close()
        moveTo(16f, 8f); lineTo(13.5f, 13.5f); lineTo(8f, 16f); lineTo(10.5f, 10.5f); close()
    }
    val Bookmark = outline("Bookmark") {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }
    val Pause = outline("Pause") {
        moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f)
    }
    val Previous = outline("Previous") {
        moveTo(18f, 5f); lineTo(8f, 12f); lineTo(18f, 19f); close(); moveTo(4f, 5f); lineTo(4f, 19f)
    }
    val Next = outline("Next") {
        moveTo(6f, 5f); lineTo(16f, 12f); lineTo(6f, 19f); close(); moveTo(20f, 5f); lineTo(20f, 19f)
    }
    val Queue = outline("Queue") {
        moveTo(3f, 5f); lineTo(20f, 5f); moveTo(3f, 10f); lineTo(20f, 10f)
        moveTo(3f, 15f); lineTo(10f, 15f); moveTo(3f, 20f); lineTo(10f, 20f)
        moveTo(15f, 14f); lineTo(21f, 17f); lineTo(15f, 20f); close()
    }
    val MusicNote = outline("Music note") {
        moveTo(10f, 17f); lineTo(10f, 4f); lineTo(20f, 2f); lineTo(20f, 15f)
        moveTo(10f, 17f); curveTo(10f, 13f, 3f, 14f, 3f, 18f); curveTo(3f, 22f, 10f, 21f, 10f, 17f)
        moveTo(20f, 15f); curveTo(20f, 11f, 13f, 12f, 13f, 16f); curveTo(13f, 20f, 20f, 19f, 20f, 15f)
    }
    val AudioFile = outline("Audio file") {
        moveTo(5f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
        moveTo(12f, 16f); lineTo(12f, 11f); lineTo(15f, 10f)
        moveTo(12f, 16f); curveTo(12f, 13f, 8f, 14f, 8f, 17f); curveTo(8f, 19f, 12f, 18f, 12f, 16f)
    }
}
