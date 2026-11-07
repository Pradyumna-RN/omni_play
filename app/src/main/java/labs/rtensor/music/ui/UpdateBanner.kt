package labs.rtensor.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.updates.UpdateState

/** Whether Home should offer the update: a newer release exists and the person has not chosen Later for it this session. */
internal fun UpdateState.offered(dismissedVersion: String?): Boolean = release != null && release.version != dismissedVersion

/**
 * The install-or-continue prompt on Home. Update downloads the verified APK and then opens the
 * installer on its own; Later hides the prompt for this version until the app is opened again.
 * Settings → About keeps the same update with its release notes.
 */
@Composable
internal fun UpdateBanner(update: UpdateState, onLater: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val release = update.release ?: return
    // Only a tap on Update may open the installer; a download finishing later must not surprise anyone.
    var requested by rememberSaveable(release.version) { mutableStateOf(false) }
    LaunchedEffect(update.apkPath, requested) { if (requested && update.apkPath != null) { requested = false; onInstall() } }
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceVariant, contentColor = scheme.onSurface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Update Hum", Modifier.semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(update.message.ifBlank { "Version ${release.version} is available." }, fontSize = 13.sp, lineHeight = 19.sp)
            // What changes, one tap away, so the choice between updating and continuing is an informed one.
            val notes = remember(release) { parseReleaseNotes(release.version, release.notes) }
            var open by rememberSaveable(release.version) { mutableStateOf(false) }
            if (notes != null && open) Column(Modifier.padding(top = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { ReleaseNotes(notes, showTitle = false) }
            if (update.downloading) {
                LinearProgressIndicator(progress = { update.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp, end = 4.dp))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (notes != null) {
                        TextButton(onClick = { open = !open }) { Text(if (open) "Hide notes" else "What’s new") }
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onLater) { Text("Later") }
                    Button(onClick = { if (update.apkPath != null) onInstall() else { requested = true; onDownload() } }, shape = ButtonShape,
                        enabled = !update.checking) { Text(if (update.apkPath != null) "Install" else "Update") }
                }
            }
        }
    }
}
