package labs.rtensor.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import labs.rtensor.music.MusicState
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.UserPlaylist

class SongActionCallbacks(
    val playNext: (Track) -> Unit = {}, val addToQueue: (Track) -> Unit = {},
    val startRadio: (Track) -> Unit = {}, val removeQueued: (Int, Track) -> Unit = { _, _ -> },
    val loadPlaylists: (Track?) -> Unit = {}, val openPlaylist: (UserPlaylist, Boolean) -> Unit = { _, _ -> },
    val closePlaylist: () -> Unit = {},
    val createPlaylist: (String, List<Track>, Boolean) -> Unit = { _, _, _ -> },
    val addToPlaylist: (UserPlaylist, Track) -> Unit = { _, _ -> },
    val renamePlaylist: (UserPlaylist, String) -> Unit = { _, _ -> },
    val deletePlaylist: (UserPlaylist) -> Unit = {},
    val removeFromPlaylist: (UserPlaylist, Track, Int) -> Unit = { _, _, _ -> },
    val rate: (Track, String) -> Unit = { _, _ -> }, val connectYouTube: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

internal data class SongMenuTarget(val track: Track, val queueIndex: Int? = null,
    val playlist: UserPlaylist? = null, val playlistIndex: Int? = null)
private data class PlaylistDraft(val tracks: List<Track>, val fromPicker: Boolean = false)
internal data class SongMenus(val open: (SongMenuTarget) -> Unit, val create: (List<Track>) -> Unit)
internal val LocalSongMenus = staticCompositionLocalOf<SongMenus?> { null }
internal val LocalSongActions = staticCompositionLocalOf { SongActionCallbacks() }

/** Retains the ordinary tap and scroll gestures; long-press also has an accessibility label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.songClickable(track: Track, target: SongMenuTarget = SongMenuTarget(track), onClick: () -> Unit): Modifier {
    val menus = LocalSongMenus.current
    return combinedClickable(role = Role.Button, onClickLabel = "Play", onClick = onClick,
        onLongClickLabel = "Song options", onLongClick = menus?.let { { it.open(target) } })
}

@Composable
internal fun SongMoreButton(track: Track, target: SongMenuTarget = SongMenuTarget(track)) {
    val menus = LocalSongMenus.current ?: return
    IconButton(onClick = { menus.open(target) }, modifier = Modifier.size(48.dp).clip(CircleShape)) {
        Icon(Icons.Outlined.MoreVert, "More options for ${track.title}", Modifier.size(22.dp))
    }
}

/** Shares discovery links, never signed stream URLs or provider account credentials. */
internal fun songLink(track: Track): String? = when {
    track.source == Source.LOCAL -> null
    track.spotifyId.matches(Regex("[A-Za-z0-9]{22}")) -> "https://open.spotify.com/track/${track.spotifyId}"
    else -> labs.rtensor.music.data.YouTubeLink.normalize(track.url)?.takeIf { it.contains("/watch?v=") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongActionsHost(state: MusicState, actions: SongActionCallbacks, onSave: (Track) -> Unit,
    onPlay: (Track) -> Unit, onArtist: (String) -> Unit, content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<SongMenuTarget?>(null) }
    var adding by remember { mutableStateOf<Track?>(null) }
    var creating by remember { mutableStateOf<PlaylistDraft?>(null) }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { message ->
            target = null; adding = null; creating = null
            snack.showSnackbar(message); actions.dismissMessage()
        }
    }
    val scope = rememberCoroutineScope()
    fun external(action: () -> Unit) {
        try { action() } catch (_: Exception) { scope.launch { snack.showSnackbar("No app is available for this action.") } }
    }
    val menus = remember { SongMenus(open = { target = it }, create = { creating = PlaylistDraft(it) }) }
    CompositionLocalProvider(LocalSongMenus provides menus, LocalSongActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        target?.let { selected ->
            val track = selected.track
            val saved = state.saved.any { it.libraryKey() == track.libraryKey() }
            val link = songLink(track)
            ModalBottomSheet(onDismissRequest = { target = null }) {
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(track, Modifier.size(52.dp), size = 52.dp)
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { heading() })
                                Text(track.artist, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                    item { MenuAction("Play now", Icons.Outlined.PlayArrow) { target = null; onPlay(track) } }
                    item { MenuAction("Play next", Glyph.Next) { target = null; actions.playNext(track) } }
                    item { MenuAction("Add to queue", Glyph.Queue) { target = null; actions.addToQueue(track) } }
                    if (track.source != Source.LOCAL) item {
                        MenuAction("Start radio", Glyph.Sparkle) { target = null; actions.startRadio(track) }
                    }
                    item { MenuAction("Add to playlist", Glyph.Library) { target = null; adding = track; actions.loadPlaylists(track) } }
                    item { MenuAction(if (saved) "Remove from Saved" else "Save in Music", if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder) {
                        target = null; onSave(track)
                    } }
                    selected.queueIndex?.takeIf { it > state.queueIndex }?.let { index -> item {
                        MenuAction("Remove from queue", Icons.Outlined.Close) { target = null; actions.removeQueued(index, track) }
                    } }
                    selected.playlist?.let { playlist -> selected.playlistIndex?.let { index -> item {
                        MenuAction("Remove from playlist", Icons.Outlined.Delete, enabled = !state.playlistBusy && (!playlist.remote || track.playlistItemId.isNotBlank())) {
                            target = null; actions.removeFromPlaylist(playlist, track, index)
                        }
                    } } }
                    if (track.artist.isNotBlank() && track.source != Source.LOCAL) item {
                        MenuAction("Search artist", Icons.Outlined.Search) { target = null; onArtist(track.artist) }
                    }
                    item { MenuAction("Share", Icons.Outlined.Share) {
                        target = null
                        external {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                if (track.source == Source.LOCAL) {
                                    type = "audio/*"; putExtra(Intent.EXTRA_STREAM, Uri.parse(track.url)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } else { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link ?: track.title) }
                            }
                            context.startActivity(Intent.createChooser(send, "Share song"))
                        }
                    } }
                    if (link != null) {
                        item { MenuAction("Copy link", Icons.Outlined.Share) {
                            target = null
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(track.title, link))
                        } }
                        item { MenuAction(if (track.spotifyId.isNotBlank()) "Open in Spotify" else "Open in YouTube Music", Glyph.MusicNote) {
                            target = null; external { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (track.spotifyId.isBlank()) link.replace("www.youtube.com", "music.youtube.com") else link))) }
                        } }
                    }
                    if (track.source != Source.LOCAL && state.account.connected) {
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("YouTube Music account", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 12.sp) }
                        item { MenuAction("Like on YouTube Music", Icons.Outlined.FavoriteBorder, !state.playlistBusy) { target = null; actions.rate(track, "LIKE") } }
                        item { MenuAction("Dislike on YouTube Music", Icons.Outlined.Close, !state.playlistBusy) { target = null; actions.rate(track, "DISLIKE") } }
                        item { MenuAction("Remove YouTube rating", Icons.Outlined.Delete, !state.playlistBusy) { target = null; actions.rate(track, "INDIFFERENT") } }
                    }
                }
            }
        }
        adding?.let { track ->
            ModalBottomSheet(onDismissRequest = { adding = null }) {
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { Text("Add to playlist", Modifier.padding(24.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
                    item { MenuAction("New playlist", Icons.Outlined.Add, !state.playlistBusy) { adding = null; creating = PlaylistDraft(listOf(track), fromPicker = true) } }
                    if (state.playlistsBusy || state.playlistBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
                    state.playlistError?.let { error -> item { PlaylistError(error) { actions.loadPlaylists(track) } } }
                    items(state.playlists, key = { it.id }) { playlist ->
                        MenuAction(playlist.title, Glyph.Library, !playlist.contains && !state.playlistBusy,
                            detail = if (playlist.contains) "Already in playlist" else if (playlist.remote) "YouTube Music" else "On this device") {
                            actions.addToPlaylist(playlist, track)
                        }
                    }
                    if (!state.account.connected && track.source != Source.LOCAL) item {
                        MenuAction("Connect YouTube Music", Glyph.MusicNote, detail = "Sync playlists with your account") { adding = null; actions.connectYouTube() }
                    }
                    if (track.source == Source.LOCAL) item {
                        Text("Local files stay in device playlists; they are not uploaded to YouTube.", Modifier.padding(24.dp), fontSize = 13.sp)
                    }
                }
            }
        }
        creating?.let { draft -> NewPlaylistSheet(state, draft.tracks, onDismiss = { creating = null; if (draft.fromPicker) adding = draft.tracks.singleOrNull() }, onCreate = actions.createPlaylist,
            onConnect = { creating = null; adding = null; actions.connectYouTube() }) }
    }
}

@Composable
private fun MenuAction(title: String, image: ImageVector, enabled: Boolean = true, detail: String? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        Icon(image, null, Modifier.size(22.dp)); Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun PlaylistError(error: String, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton(onClick = onRetry) { Text("Retry playlists") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPlaylistSheet(state: MusicState, tracks: List<Track>, onDismiss: () -> Unit,
    onCreate: (String, List<Track>, Boolean) -> Unit, onConnect: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val canSync = tracks.none { it.source == Source.LOCAL }
    var sync by remember { mutableStateOf(canSync) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("New playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(150) }, label = { Text("Playlist name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist name" })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sync, { sync = it }, enabled = canSync && !state.playlistBusy)
                Text("Sync to YouTube Music", fontSize = 14.sp)
            }
            Text(if (sync) "Private on your YouTube Music account" else "Stored on this device", fontSize = 12.sp)
            if (sync && !state.account.connected) TextButton(onClick = onConnect) { Text("Connect YouTube Music") }
            if (state.playlistBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            state.playlistError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onCreate(name.trim(), tracks, sync) }, enabled = name.isNotBlank() && !state.playlistBusy && (!sync || state.account.connected)) { Text("Create playlist") }
            }
        }
    }
}

@Composable
internal fun PlaylistLibrary(state: MusicState, onPlay: (Track, List<Track>) -> Unit, active: Boolean = true) {
    val actions = LocalSongActions.current
    val menus = LocalSongMenus.current
    val selected = state.playlistSelection
    var rename by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleting by remember { mutableStateOf<UserPlaylist?>(null) }
    BackHandler(active && selected != null, onBack = actions.closePlaylist)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) TextButton(onClick = actions.closePlaylist) { Text("All playlists") }
                Text(selected?.title ?: "Your playlists", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (selected == null) IconButton(onClick = { menus?.create?.invoke(emptyList()) }, modifier = Modifier.size(48.dp), enabled = !state.playlistBusy) { Icon(Icons.Outlined.Add, "Create playlist") }
            }
            Text(if (selected?.remote == true) "YouTube Music" else if (selected != null) "On this device" else "Device and YouTube Music playlists", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.playlistsBusy || state.playlistBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        state.playlistError?.let { error -> item { PlaylistError(error) { if (selected == null) actions.loadPlaylists(null) else actions.openPlaylist(selected, false) } } }
        if (selected == null) {
            items(state.playlists, key = { it.id }) { playlist ->
                MenuAction(playlist.title, Glyph.Library, detail = if (playlist.remote) "YouTube Music" else "On this device") { actions.openPlaylist(playlist, false) }
            }
            if (state.playlists.isEmpty() && !state.playlistsBusy) item { Text("Create a playlist or add a song from its options menu.", Modifier.padding(vertical = 24.dp), fontSize = 14.sp) }
            if (!state.account.connected) item { TextButton(onClick = actions.connectYouTube) { Text("Connect YouTube Music") } }
            item { TextButton(onClick = { actions.loadPlaylists(null) }) { Text("Refresh playlists") } }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.playlistTracks.firstOrNull()?.let { onPlay(it, state.playlistTracks) } }, enabled = state.playlistTracks.isNotEmpty()) { Text(if (state.playlistHasMore) "Play loaded songs" else if (state.playlistTracks.size > 100) "Play first 100" else "Play all") }
                    OutlinedButton(onClick = { state.playlistTracks.shuffled().let { if (it.isNotEmpty()) onPlay(it.first(), it) } }, enabled = state.playlistTracks.isNotEmpty()) { Text("Shuffle") }
                }
                Row {
                    TextButton(onClick = { rename = selected }, enabled = !state.playlistBusy) { Text("Rename") }
                    TextButton(onClick = { deleting = selected }, enabled = !state.playlistBusy) { Text("Delete playlist") }
                }
            }
            itemsIndexed(state.playlistTracks, key = { i, t -> "$i:${t.libraryKey()}" }) { i, track ->
                TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(),
                    { onPlay(track, state.playlistTracks) }, null, target = SongMenuTarget(track, playlist = selected, playlistIndex = i))
            }
            if (state.playlistHasMore) item { TextButton(onClick = { actions.openPlaylist(selected, true) }, enabled = !state.playlistBusy) { Text("Load more songs") } }
            if (state.playlistTracks.isEmpty() && !state.playlistBusy) item { Text("Add songs from their options menu.", Modifier.padding(vertical = 24.dp)) }
        }
    }
    rename?.let { playlist ->
        var name by remember(playlist.id) { mutableStateOf(playlist.title) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it.take(150) }, label = { Text("Playlist name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist name" }) },
            confirmButton = { TextButton(onClick = { rename = null; actions.renamePlaylist(playlist, name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    deleting?.let { playlist -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete playlist?") },
        text = { Text("Delete “${playlist.title}” ${if (playlist.remote) "from your YouTube Music account" else "from this device"}? Songs themselves are kept.") },
        confirmButton = { TextButton(onClick = { deleting = null; actions.deletePlaylist(playlist) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
