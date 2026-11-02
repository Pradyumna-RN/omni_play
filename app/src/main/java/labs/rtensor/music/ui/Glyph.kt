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

    private fun solid(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    /** Filled compass: the disc is solid and the needle is cut out with even-odd filling. */
    val CompassFilled = ImageVector.Builder("Explore selected", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
            moveTo(22f, 12f); curveTo(22f, 17.52f, 17.52f, 22f, 12f, 22f); curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
            curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f); curveTo(17.52f, 2f, 22f, 6.48f, 22f, 12f); close()
            moveTo(16f, 8f); lineTo(13.5f, 13.5f); lineTo(8f, 16f); lineTo(10.5f, 10.5f); close()
        }
    }.build()
    /** Library: a stack of records leaning on a shelf. */
    val Library = outline("Library") {
        moveTo(4f, 4f); lineTo(4f, 20f); moveTo(8.5f, 4f); lineTo(8.5f, 20f)
        moveTo(13f, 5f); lineTo(17.5f, 19.5f); moveTo(3f, 20.5f); lineTo(21f, 20.5f)
    }
    val LibraryFilled = solid("Library selected") {
        moveTo(3f, 4f); lineTo(5.5f, 4f); lineTo(5.5f, 19f); lineTo(3f, 19f); close()
        moveTo(7.5f, 4f); lineTo(10f, 4f); lineTo(10f, 19f); lineTo(7.5f, 19f); close()
        moveTo(12f, 5.5f); lineTo(14.4f, 4.8f); lineTo(18.6f, 18.4f); lineTo(16.2f, 19.1f); close()
        moveTo(3f, 20.5f); lineTo(21f, 20.5f)
    }
    val History = outline("History") {
        moveTo(3.5f, 12f); curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f); curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f); curveTo(9.2f, 20.5f, 6.8f, 19.2f, 5.2f, 17.1f)
        moveTo(3.5f, 12f); lineTo(1.8f, 10.2f); moveTo(3.5f, 12f); lineTo(5.3f, 10.3f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val Sparkle = outline("For you") {
        moveTo(12f, 3f); curveTo(12.6f, 8.2f, 15.8f, 11.4f, 21f, 12f); curveTo(15.8f, 12.6f, 12.6f, 15.8f, 12f, 21f)
        curveTo(11.4f, 15.8f, 8.2f, 12.6f, 3f, 12f); curveTo(8.2f, 11.4f, 11.4f, 8.2f, 12f, 3f); close()
    }
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
    val Contrast = outline("Appearance") {
        moveTo(21f, 12f); curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f); curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
        curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f); curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f); close()
        moveTo(12f, 3f); lineTo(12f, 21f); moveTo(12f, 8f); lineTo(19.5f, 8f); moveTo(12f, 12f); lineTo(21f, 12f); moveTo(12f, 16f); lineTo(19.5f, 16f)
    }
    val Shield = outline("Privacy") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); curveTo(19f, 15.5f, 16f, 19f, 12f, 21f)
        curveTo(8f, 19f, 5f, 15.5f, 5f, 11f); lineTo(5f, 6f); close()
        moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f)
    }
}
