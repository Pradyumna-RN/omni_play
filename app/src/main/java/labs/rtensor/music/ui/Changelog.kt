package labs.rtensor.music.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One group of changes in a release, such as New, Fixed or Known. */
internal data class ChangelogSection(val title: String, val items: List<String>)

/** One release: `0.4.0-beta.2 (versionCode 46)` and its sections. */
internal data class ChangelogRelease(val title: String, val sections: List<ChangelogSection>)

/**
 * Reads CHANGELOG.md: `## release` starts a release, `### group` a section and `- item` an entry.
 * A wrapped line is joined to the entry above it. Anything before the first release is ignored.
 */
internal fun parseChangelog(text: String): List<ChangelogRelease> {
    val releases = mutableListOf<ChangelogRelease>()
    var title: String? = null
    var sections = mutableListOf<ChangelogSection>()
    var group: String? = null
    var items = mutableListOf<String>()
    fun closeGroup() {
        val name = group
        if (name != null && items.isNotEmpty()) sections += ChangelogSection(name, items.toList())
        group = null; items = mutableListOf()
    }
    fun closeRelease() {
        closeGroup()
        val name = title
        if (name != null) releases += ChangelogRelease(name, sections.toList())
        title = null; sections = mutableListOf()
    }
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        when {
            line.startsWith("## ") -> { closeRelease(); title = line.removePrefix("## ").trim() }
            title == null -> Unit
            line.startsWith("### ") -> { closeGroup(); group = line.removePrefix("### ").trim() }
            line.startsWith("- ") -> if (group != null) items += line.removePrefix("- ").trim()
            line.isNotBlank() && group != null && items.isNotEmpty() && raw.startsWith(" ") ->
                items[items.lastIndex] = items.last() + " " + line.trim()
        }
    }
    closeRelease()
    return releases
}

/** The changelog bundled with this build; empty if it is missing, so the screen shows a quiet message instead of failing. */
internal fun loadChangelog(context: Context): List<ChangelogRelease> =
    try { parseChangelog(context.assets.open("changelog.md").bufferedReader().use { it.readText() }) } catch (_: Exception) { emptyList() }

/** The release notes GitHub holds for a release, as the same groups the in-app changelog shows. A body with no `## ` title gets the version as its title. */
internal fun parseReleaseNotes(version: String, notes: String): ChangelogRelease? {
    val text = if (notes.lines().any { it.startsWith("## ") }) notes else "## $version\n$notes"
    return parseChangelog(text).firstOrNull()?.takeIf { it.sections.isNotEmpty() }
}

/** One release: its title, then each group with its entries. */
@Composable
internal fun ReleaseNotes(release: ChangelogRelease, showTitle: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(release.title, Modifier.semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.primary)
        release.sections.forEach { section ->
            Text(section.title, Modifier.padding(top = 4.dp).semantics { heading() }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
            section.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)
                    Text(item, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface)
                }
            }
        }
    }
}

/** What changed in each release, newest first, readable offline. */
@Composable
internal fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val releases = remember { loadChangelog(context) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What’s new") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (releases.isEmpty()) Text("The changelog isn’t available in this build.", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                releases.forEach { release -> ReleaseNotes(release) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
