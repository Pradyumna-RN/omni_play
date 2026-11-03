package labs.rtensor.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import labs.rtensor.music.MusicState
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.SourceProtocol

/** Secrets live only in the form until the ViewModel seals the connection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackSourcesEditor(state: MusicState, onDismiss: () -> Unit,
    onConnect: (MusicProvider, SourceProtocol, String, String) -> Unit, onRemove: (String) -> Unit) {
    var adding by remember { mutableStateOf(state.playbackSources.isEmpty()) }
    var provider by remember { mutableStateOf(MusicProvider.APPLE_MUSIC) }
    var protocol by remember { mutableStateOf(SourceProtocol.GATEWAY) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val count = state.playbackSources.size
    LaunchedEffect(count) { if (count > 0) { adding = false; url = ""; token = "" } }
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recommendations stay with YouTube. Playback checks matching Atmos recordings first, then lossless quality. Stereo plays unchanged.")
            state.playbackSources.forEach { source ->
                Column {
                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                    Text("${source.provider} · ${source.protocol}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRemove(source.id) }, enabled = !state.sourceBusy) { Text("Remove ${source.name}") }
                }
            }
            if (!adding) TextButton(onClick = { adding = true }, enabled = count < 4) { Text("Add a source") }
            else {
                Text("Connection type", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceProtocol.entries.forEach { value ->
                        FilterChip(selected = protocol == value, enabled = !state.sourceBusy, onClick = {
                            protocol = value
                            provider = when (value) { SourceProtocol.TIDAL_HIFI -> MusicProvider.TIDAL; SourceProtocol.ADDON -> MusicProvider.LIBRARY; else -> MusicProvider.APPLE_MUSIC }
                        }, label = { Text(value.label) })
                    }
                }
                if (protocol == SourceProtocol.GATEWAY) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MusicProvider.entries.forEach { value -> FilterChip(selected = provider == value, enabled = !state.sourceBusy,
                        onClick = { provider = value }, label = { Text(value.label) }) }
                }
                Text(when (protocol) {
                    SourceProtocol.GATEWAY -> "Requires a compatible server with access to your provider account. Native Apple Music sign-in is not included."
                    SourceProtocol.ADDON -> "Install an addon by entering its HTTPS server address. Availability and account requirements depend on your addon."
                    SourceProtocol.TIDAL_HIFI -> "Connect your own HiFi API server with a TIDAL account."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS server URL") }, singleLine = true, enabled = !state.sourceBusy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Server access token (optional)") }, singleLine = true, enabled = !state.sourceBusy,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { onConnect(provider, protocol, url, token) }, enabled = url.isNotBlank() && !state.sourceBusy) {
                    Text(if (state.sourceBusy) "Checking…" else "Check and save")
                }
            }
            state.sourceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
}
