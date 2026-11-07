package labs.rtensor.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.MusicState
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.SourceProtocol

/**
 * Inline editor under "Playback sources". It grows inside the settings card rather than
 * scrolling on its own, so there is only ever one scroll container on the page.
 * Secrets live only in the form until the ViewModel seals the connection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackSourcesEditor(state: MusicState, onDismiss: () -> Unit,
    onConnect: (MusicProvider, SourceProtocol, String, String) -> Unit, onRemove: (String) -> Unit,
    onConnectTidal: () -> Unit = {}, onDisconnectTidal: () -> Unit = {}, onCancelTidal: () -> Unit = {},
    onConnectApple: () -> Unit = {}, onNativeApple: () -> Unit = {}, onDisconnectApple: () -> Unit = {}, onCancelApple: () -> Unit = {}) {
    // The form survives rotation; the access token deliberately stays in memory only.
    var adding by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf(MusicProvider.APPLE_MUSIC) }
    var protocol by rememberSaveable { mutableStateOf(SourceProtocol.GATEWAY) }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val count = state.playbackSources.size
    LaunchedEffect(count) { if (count > 0) { adding = false; url = ""; token = "" } }
    LaunchedEffect(state.tidalAccount.connected) { if (state.tidalAccount.connected) adding = false }
    val scheme = MaterialTheme.colorScheme
    val busy = state.sourceBusy
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recommendations use your selected discovery service. The audio source is shown separately in the player. Playback checks matching surround and lossless recordings; stereo plays unchanged.",
            fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant)
        Surface(shape = PanelShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Apple Music · experimental", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(when {
                    state.appleAccount.connected && state.appleAccount.native -> "Advanced account connected · format availability is checked per song. The player shows the actual audio source and codec."
                    state.appleAccount.connected -> "AAC account connected · compressed stereo. This sign-in does not enable lossless or multichannel."
                    else -> "Sign in for Apple Music AAC stereo. Lossless and surround require separate supported playback access; they are not enabled by an AAC sign-in."
                }, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
                if (state.appleAccount.connecting || state.appleAccount.needsCode) TextButton(onClick = onCancelApple, modifier = Modifier.fillMaxWidth()) { Text("Cancel Apple sign-in") }
                else {
                    OutlinedButton(onClick = onConnectApple, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Sign in · AAC stereo") }
                    if (labs.rtensor.music.BuildConfig.APPLE_NATIVE_INCLUDED) OutlinedButton(onClick = onNativeApple, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Set up lossless & multichannel") }
                }
                if (state.appleAccount.connected) TextButton(onClick = onDisconnectApple, modifier = Modifier.fillMaxWidth()) { Text("Disconnect Apple Music") }
                state.appleAccount.error?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurface) }
            }
        }
        Surface(shape = PanelShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TIDAL · experimental", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (state.tidalAccount.connected) "Signed in · checks matching recordings when you play"
                    else "Sign in with your subscription. No music server needed.", fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
                if (state.tidalAccount.connecting) TextButton(onClick = onCancelTidal, modifier = Modifier.fillMaxWidth()) { Text("Cancel TIDAL sign-in") }
                else OutlinedButton(onClick = onConnectTidal, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.tidalAccount.connected) "Reconnect TIDAL" else "Sign in to TIDAL")
                }
                if (state.tidalAccount.connected) TextButton(onClick = onDisconnectTidal, modifier = Modifier.fillMaxWidth()) { Text("Disconnect TIDAL") }
                state.tidalAccount.error?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurface) }
            }
        }
        state.playbackSources.forEach { source ->
            Surface(shape = PanelShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Saved is not the same as verified: each song is checked before playback.
                        Text("${source.provider} · ${source.protocol} · saved", fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onRemove(source.id) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Remove ${source.name}" }) { Text("Remove") }
                }
            }
        }
        if (!adding) {
            OutlinedButton(onClick = { adding = true }, enabled = count < 4, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Add a source") }
        } else {
            FieldLabel("Connection type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceProtocol.entries.forEach { value ->
                    FilterChip(selected = protocol == value, enabled = !busy, onClick = {
                        protocol = value
                        provider = when (value) { SourceProtocol.TIDAL_HIFI -> MusicProvider.TIDAL; SourceProtocol.ADDON -> MusicProvider.LIBRARY; else -> MusicProvider.APPLE_MUSIC }
                    }, label = { Text(value.label) })
                }
            }
            if (protocol == SourceProtocol.GATEWAY) {
                FieldLabel("Provider")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MusicProvider.entries.forEach { value -> FilterChip(selected = provider == value, enabled = !busy,
                        onClick = { provider = value }, label = { Text(value.label) }) }
                }
            }
            Text(when (protocol) {
                SourceProtocol.GATEWAY -> "Requires a compatible server with access to your provider account. Apple Music account playback can also be set up above."
                SourceProtocol.ADDON -> "Install an addon by entering its HTTPS server address. Availability and account requirements depend on your addon."
                SourceProtocol.TIDAL_HIFI -> "Connect your own HiFi API server with a TIDAL account."
            }, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS server URL") }, singleLine = true, enabled = !busy,
                shape = RectangleShape, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Server access token (optional)") }, singleLine = true, enabled = !busy,
                shape = RectangleShape, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        }
        state.sourceMessage?.let {
            Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth().background(scheme.secondaryContainer, RectangleShape).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Done") }
            if (adding) Button(onClick = { onConnect(provider, protocol, url, token) }, enabled = url.isNotBlank() && !busy, shape = ButtonShape, modifier = Modifier.weight(1f)) {
                Text(if (busy) "Checking…" else "Check and save")
            }
        }
    }
}

private val PanelShape = RoundedCornerShape(14.dp)

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
