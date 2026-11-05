package labs.rtensor.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var adding by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(MusicProvider.APPLE_MUSIC) }
    var protocol by remember { mutableStateOf(SourceProtocol.GATEWAY) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val count = state.playbackSources.size
    LaunchedEffect(count) { if (count > 0) { adding = false; url = ""; token = "" } }
    LaunchedEffect(state.tidalAccount.connected) { if (state.tidalAccount.connected) adding = false }
    val scheme = MaterialTheme.colorScheme
    val busy = state.sourceBusy
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recommendations stay with YouTube. Playback checks matching Atmos recordings first, then lossless quality. Stereo plays unchanged.",
            fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant)
        Surface(shape = RectangleShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Apple Music · experimental", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(when {
                    state.appleAccount.connected && state.appleAccount.native -> "Native account connected · checks multichannel, then lossless, then AAC"
                    state.appleAccount.connected -> "Account connected · AAC stereo"
                    else -> "Use your subscription inside Music. AAC uses Apple’s web sign-in; lossless and multichannel use an imported native runtime."
                }, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
                if (state.appleAccount.connecting || state.appleAccount.needsCode) TextButton(onClick = onCancelApple) { Text("Cancel Apple sign-in") }
                else {
                    OutlinedButton(onClick = onConnectApple, shape = RectangleShape) { Text("Sign in · AAC stereo") }
                    if (labs.rtensor.music.BuildConfig.APPLE_NATIVE_INCLUDED) OutlinedButton(onClick = onNativeApple, shape = RectangleShape) { Text("Set up lossless & multichannel") }
                }
                if (state.appleAccount.connected) TextButton(onClick = onDisconnectApple) { Text("Disconnect Apple Music") }
                state.appleAccount.error?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurface) }
            }
        }
        Surface(shape = RectangleShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TIDAL · experimental", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (state.tidalAccount.connected) "Signed in · checks matching recordings when you play"
                    else "Sign in with your subscription. No music server needed.", fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.tidalAccount.connecting) TextButton(onClick = onCancelTidal) { Text("Cancel TIDAL sign-in") }
                    else OutlinedButton(onClick = onConnectTidal, shape = RectangleShape) {
                        Text(if (state.tidalAccount.connected) "Reconnect TIDAL" else "Sign in to TIDAL")
                    }
                    if (state.tidalAccount.connected) TextButton(onClick = onDisconnectTidal) { Text("Disconnect TIDAL") }
                }
                state.tidalAccount.error?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurface) }
            }
        }
        state.playbackSources.forEach { source ->
            Surface(shape = RectangleShape, color = scheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Saved is not the same as verified: each song is checked before playback.
                        Text("${source.provider} · ${source.protocol} · saved", fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onRemove(source.id) }, enabled = !busy) { Text("Remove ${source.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) }
                }
            }
        }
        if (!adding) {
            OutlinedButton(onClick = { adding = true }, enabled = count < 4, shape = RectangleShape) { Text("Add a source") }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Done") }
            if (adding) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConnect(provider, protocol, url, token) }, enabled = url.isNotBlank() && !busy, shape = RectangleShape) {
                    Text(if (busy) "Checking…" else "Check and save")
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
