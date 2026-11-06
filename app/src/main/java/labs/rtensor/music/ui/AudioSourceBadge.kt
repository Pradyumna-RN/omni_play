package labs.rtensor.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The selected audio source, independent of the discovery/recommendation account. */
@Composable
internal fun RouteLabel(label: String) {
    if (label.isBlank()) return
    val value = label
    val provider = value.substringBefore(" · ")
    val detail = value.substringAfter(" · ", "")
    Column(Modifier.padding(top = 14.dp).semantics(mergeDescendants = true) {
        contentDescription = "Audio source: $value"
    }) {
        Text(provider, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
