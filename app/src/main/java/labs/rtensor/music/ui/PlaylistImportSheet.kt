package labs.rtensor.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.imports.PlaylistImportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistImportSheet(state: PlaylistImportState, actions: SongActionCallbacks) {
    if (!state.visible) return
    var link by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = actions.dismissPlaylistImport,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 24.dp).imePadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Import playlists", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = actions.dismissPlaylistImport, enabled = !state.saving) { Text("Close") }
            }
            Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
                if (state.preview.isNotEmpty()) {
                    itemsIndexed(state.preview, key = { index, playlist -> "$index:${playlist.title}" }) { _, playlist ->
                        Column {
                            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                            Text("${playlist.tracks.size} songs · copy on this device", fontSize = 12.sp)
                            for (track in playlist.tracks.take(6)) Text("${track.title}${track.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    if (state.unavailable > 0) item { Text("${state.unavailable} unavailable entries will be skipped.", fontSize = 13.sp) }
                    item { Button(onClick = actions.confirmPlaylistImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Saving…" else "Import to Music")
                    } }
                    item { TextButton(onClick = actions.showPlaylistImport, enabled = !state.busy) { Text("Choose another source") } }
                } else if (state.needsFolder) {
                    item { Button(onClick = actions.choosePlaylistFolder, enabled = !state.busy) { Text("Choose audio folder") } }
                    item { TextButton(onClick = actions.choosePlaylistFile, enabled = !state.busy) { Text("Choose another playlist file") } }
                } else if (state.choices.isNotEmpty()) {
                    items(state.choices, key = { "${it.provider}:${it.id}" }) { playlist ->
                        TextButton(onClick = { actions.selectPlaylistImport(playlist) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(playlist.title, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item { TextButton(onClick = actions.showPlaylistImport, enabled = !state.busy) { Text("Choose another source") } }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { actions.importPlaylistAccount("YouTube Music") }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("YouTube Music") }
                            OutlinedButton(onClick = { actions.importPlaylistAccount("Spotify") }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Spotify") }
                        }
                    }
                    item {
                        OutlinedTextField(link, { link = it.take(2048) }, label = { Text("Playlist link") }, enabled = !state.busy,
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { actions.importPlaylistLink(link) }, enabled = !state.busy && link.isNotBlank()) { Text("Read playlist link") }
                    }
                    item { OutlinedButton(onClick = actions.choosePlaylistFile, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Choose playlist file") } }
                    item { Text("From other apps: choose an M3U/M3U8, PLS, XSPF, CSV or JSON export. Local audio stays on your device; missing or ambiguous online recordings are reported in the preview.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
