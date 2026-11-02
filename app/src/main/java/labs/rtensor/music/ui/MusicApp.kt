package labs.rtensor.music.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import labs.rtensor.music.MusicState
import labs.rtensor.music.R
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.BuildConfig
import java.util.Locale

private val font = FontFamily(Font(R.font.dm_sans))
private val Light = lightColorScheme(
    primary = Color(0xFFD72F62), onPrimary = Color.White,
    background = Color(0xFFF9F8F2), onBackground = Color(0xFF161921),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF161921),
    surfaceVariant = Color(0xFFF1EEE7), onSurfaceVariant = Color(0xFF77767B),
    outline = Color(0xFFDFDDD6), secondaryContainer = Color(0xFFF9E5EB),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFF05B86), onPrimary = Color(0xFF240A13),
    background = Color(0xFF19191C), onBackground = Color(0xFFF2F0E9),
    surface = Color(0xFF222125), onSurface = Color(0xFFF2F0E9),
    surfaceVariant = Color(0xFF302C32), onSurfaceVariant = Color(0xFFB4AFB6),
    outline = Color(0xFF3A373D), secondaryContainer = Color(0xFF432733),
)

@Composable
fun MusicTheme(theme: String = "light", content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = font),
            bodyMedium = base.bodyMedium.copy(fontFamily = font),
            bodySmall = base.bodySmall.copy(fontFamily = font),
            labelLarge = base.labelLarge.copy(fontFamily = font),
            labelMedium = base.labelMedium.copy(fontFamily = font),
            labelSmall = base.labelSmall.copy(fontFamily = font),
            titleLarge = base.titleLarge.copy(fontFamily = font),
            titleMedium = base.titleMedium.copy(fontFamily = font),
            titleSmall = base.titleSmall.copy(fontFamily = font),
        ), content = content,
    )
}

@Composable
fun MusicApp(
    state: MusicState,
    onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {},
    onPlay: (Track, List<Track>) -> Unit = { _, _ -> }, onToggle: () -> Unit = {},
    onPrevious: () -> Unit = {}, onNext: () -> Unit = {},
    onSeek: (Long) -> Unit = {}, onSave: (Track) -> Unit = {},
    onTheme: (String) -> Unit = {}, onReducedMotion: (Boolean) -> Unit = {},
    onOpenLocal: () -> Unit = {},
    onClearHistory: () -> Unit = {}, onRetry: () -> Unit = {}, onDismissError: () -> Unit = {},
    sharedQuery: String? = null, onShareConsumed: () -> Unit = {},
    initialPage: String = "home",
    onRefreshFeed: () -> Unit = {}, onMoreFeed: () -> Unit = {}, onBrowse: (FeedEntry) -> Unit = {}, onRetryCollection: () -> Unit = {},
    onConnect: () -> Unit = {}, onDisconnect: () -> Unit = {}, onCancelConnect: () -> Unit = {}, onRefreshExplore: () -> Unit = {},
    onCheckUpdates: () -> Unit = {}, onDownloadUpdate: () -> Unit = {}, onInstallUpdate: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var back by rememberSaveable { mutableStateOf("home") }
    var root by rememberSaveable { mutableStateOf(initialPage.takeIf { it in listOf("home", "explore", "saved") } ?: "home") }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun focusSearch() { searchFocus.requestFocus(); keyboard?.show() }
    fun navigate(next: String) {
        if (next in listOf("home", "explore", "saved")) { root = next; onQuery(""); keyboard?.hide() }
        if (page != "now" && next == "now") back = page
        page = next
    }
    fun play(track: Track, list: List<Track>) { onPlay(track, list); navigate("now") }
    fun openEntry(entry: FeedEntry) {
        when {
            entry.videoId.isNotBlank() -> { val t = entry.asTrack(); play(t, listOf(t)) }
            entry.kind in listOf("artist", "library_artist") -> { onQuery(entry.title); onSearch(); page = root }
            else -> { onBrowse(entry); navigate("collection") }
        }
    }
    LaunchedEffect(sharedQuery) {
        sharedQuery?.takeIf { it.isNotBlank() }?.let { onQuery(it); page = root; onSearch(); onShareConsumed() }
    }
    LaunchedEffect(page) { if (page == "explore" && state.explore.isEmpty() && !state.exploreBusy) onRefreshExplore() }
    BackHandler(page != "home" || state.query.isNotEmpty()) {
        if (page in listOf("home", "explore", "saved") && state.query.isNotEmpty()) onQuery("")
        else page = when (page) { "queue" -> "now"; "now" -> back; "settings", "collection" -> root; else -> "home" }
    }
    MusicTheme(state.theme) {
        Scaffold(
            topBar = {
                AppHeader(page, onHome = { navigate("home") }, onSettings = { navigate("settings") }, onBack = {
                    page = when (page) { "queue" -> "now"; "now" -> back; else -> root }
                })
            },
            bottomBar = {
                if (page in listOf("home", "explore", "saved", "collection")) {
                    Column(Modifier.navigationBarsPadding()) {
                        if (state.current != null) MiniPlayer(state, { navigate("now") }, onToggle, onNext)
                        AppNavigation(if (page == "collection") root else page, ::navigate)
                    }
                } else Spacer(Modifier.navigationBarsPadding())
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                if (page in listOf("home", "explore", "saved")) SearchBar(state, onQuery, onSearch, searchFocus)
                if (state.error != null) ErrorBanner(state.error, onDismissError,
                    if (state.searched && page in listOf("home", "explore", "saved")) onSearch else if (page == "collection") onRetryCollection else onRetry,
                    state.errorDiagnostic, state.current?.id.orEmpty())
                Crossfade(page, animationSpec = tween(if (state.reducedMotion) 0 else 180), label = "screen", modifier = Modifier.weight(1f)) { target ->
                    if (target in listOf("home", "explore", "saved") && state.searched && state.query.isNotBlank()) SearchResults(state, ::play, onSave)
                    else when (target) {
                        "explore" -> ProviderHome(state.copy(feed = state.explore, feedBusy = state.exploreBusy, feedError = state.exploreError, feedHasMore = false), ::openEntry, onRefreshExplore, {}, "Explore")
                        "saved" -> SavedScreen(state, ::play, onSave, ::focusSearch)
                        "now" -> NowScreen(state, onToggle, onPrevious, onNext, onSeek, onSave) { navigate("queue") }
                        "queue" -> QueueScreen(state, ::play)
                        "collection" -> CollectionScreen(state, ::play, onSave)
                        "settings" -> SettingsScreen(state, onTheme, onReducedMotion, onOpenLocal, onClearHistory,
                            onConnect, onDisconnect, onCancelConnect, onCheckUpdates, onDownloadUpdate, onInstallUpdate)
                        else -> HomeScreen(state, ::focusSearch, onOpenLocal, ::play, ::openEntry, onRefreshFeed, onMoreFeed)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(page: String, onHome: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (page in listOf("now", "queue", "settings", "collection")) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Caption(when (page) { "now" -> "NOW PLAYING"; "queue" -> "UP NEXT"; "collection" -> "COLLECTION"; else -> "SETTINGS" })
            Spacer(Modifier.width(48.dp))
        } else {
            Column(Modifier.clickable(onClick = onHome).padding(6.dp)) {
                Text("MUSIC", fontFamily = font, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.width(22.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Open settings", Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun AppNavigation(page: String, onSelect: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(Triple("home", "Home", Icons.Outlined.Home), Triple("explore", "Explore", Glyph.Compass), Triple("saved", "Saved", Glyph.Bookmark)).forEach { (id, name, icon) ->
            val selected = page == id
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.widthIn(min = 80.dp).fillMaxHeight().clickable { onSelect(id) }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, name, Modifier.size(22.dp), tint = color)
                Spacer(Modifier.height(6.dp))
                Text(name, fontSize = 11.sp, color = color)
                Spacer(Modifier.weight(1f))
                if (selected) Box(Modifier.width(36.dp).height(2.dp).background(color))
            }
        }
    }
}

@Composable
private fun HomeScreen(state: MusicState, onSearch: () -> Unit, onLocal: () -> Unit, onPlay: (Track, List<Track>) -> Unit,
    onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    if (state.feed.isNotEmpty()) { ProviderHome(state, onEntry, onRefresh, onMore); return }
    val recent = state.history.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { PageTitle(if (recent == null) "Room to listen" else "Back to your music") }
        if (state.feedBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
        if (state.feedError != null) item {
            Column {
                Text(state.feedError, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh) { Text("Retry feed", fontSize = 12.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 265.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.46f)) {
                    Caption(if (recent == null) "START WITH A SONG" else "LAST PLAYED")
                    Spacer(Modifier.height(17.dp))
                    Text(recent?.title ?: "Find your\nnext repeat.", fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 31.sp, lineHeight = 34.sp, letterSpacing = (-1.2).sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Text(recent?.artist ?: "A song, an artist,\na familiar feeling.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(27.dp))
                    TextButton(onClick = { if (recent != null) onPlay(recent, state.history) else onSearch() }, contentPadding = PaddingValues(0.dp)) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (recent == null) Icons.Outlined.Search else Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(if (recent == null) "Find music" else "Play", fontSize = 13.sp)
                    }
                }
                Artwork(recent, Modifier.weight(.54f).height(270.dp).clip(RoundedCornerShape(10.dp)))
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().clickable(onClick = onLocal).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.AudioFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Already yours", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Open an audio file from your device", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open audio file", Modifier.size(19.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        }
        if (state.history.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.history.take(12), key = { it.id }) { track ->
                        Column(Modifier.width(142.dp).clickable { onPlay(track, state.history) }) {
                            Artwork(track, Modifier.size(142.dp).clip(RoundedCornerShape(9.dp)))
                            Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            Text(track.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHome(state: MusicState, onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, title: String = "For your ears") {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            PageTitle(title)
            if (title == "Explore") Text("Fresh releases, charts and discoveries from YouTube Music.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = onRefresh, enabled = !state.feedBusy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh", fontSize = 11.sp) }
        }
        itemsIndexed(state.feed, key = { i, section -> "$i:${section.title}" }) { _, section ->
            Column {
                SectionTitle(section.title)
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(section.items, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
                        Column(Modifier.width(156.dp).clickable { onEntry(entry) }) {
                            Artwork(Track(entry.id, entry.title, entry.subtitle, "", entry.artwork), Modifier.size(156.dp).clip(RoundedCornerShape(10.dp)))
                            Text(entry.title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            Text(entry.subtitle, fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (state.feedBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
        if (state.feedError != null) item { Text(state.feedError, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.feed.isEmpty() && !state.feedBusy && state.feedError == null) item { EmptyState("More music awaits", "Tap Refresh to load discoveries from YouTube Music.", Glyph.Compass) }
        if (state.feedHasMore) item { OutlinedButton(onClick = onMore, enabled = !state.feedBusy, modifier = Modifier.fillMaxWidth()) { Text("More from your feed") } }
    }
}

@Composable
private fun CollectionScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(state.collectionEntry?.title.orEmpty(), fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.collectionEntry?.subtitle.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        if (state.collectionBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else if (state.collection.isEmpty() && state.error == null) EmptyState("Nothing to play here", "This collection has no available recordings.", Glyph.MusicNote)
        else {
            Button(onClick = { state.collection.firstOrNull()?.let { onPlay(it, state.collection) } }, enabled = state.collection.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.PlayArrow, null); Text("Play all") }
            Spacer(Modifier.height(14.dp))
            LazyColumn {
                itemsIndexed(state.collection, key = { i, track -> "$i:${track.id}" }) { _, track ->
                    TrackRow(track, state.saved.any { it.id == track.id }, state.current?.id == track.id, { onPlay(track, state.collection) }, { onSave(track) })
                }
            }
        }
    }
}

@Composable
private fun SearchBar(state: MusicState, onQuery: (String) -> Unit, onSearch: () -> Unit, focus: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSearch() }
    OutlinedTextField(
        value = state.query, onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(focus).semantics { contentDescription = "Search music" }, singleLine = true,
        placeholder = { Text("Songs, artists or a YouTube link", fontSize = 12.sp) },
        leadingIcon = { IconButton(onClick = submit, enabled = state.query.isNotBlank() && !state.searching) { Icon(Icons.Outlined.Search, "Search YouTube", Modifier.size(20.dp)) } },
        trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery(""); keyboard?.hide() }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) } },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SearchResults(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        when {
            state.searching -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outline)
                Text("Looking on YouTube…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            }
            state.results.isNotEmpty() -> {
                Caption("${state.results.size} RESULTS")
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(state.results, key = { index, track -> "$index:${track.id}" }) { _, track ->
                        TrackRow(track, state.saved.any { it.id == track.id }, state.current?.id == track.id, { onPlay(track, state.results) }, { onSave(track) })
                    }
                }
            }
            state.searched && state.error == null -> EmptyState("Nothing found this time", "Try another title, artist, or a direct YouTube link.", Icons.Outlined.Search)
            !state.searched -> EmptyState("Follow a little curiosity", "Search YouTube or paste a video or playlist link. You can also share a link here from another app.", Glyph.MusicNote)
        }
    }
}

@Composable
private fun SavedScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(10.dp)); PageTitle("Worth keeping")
        Text("The songs you always come back to.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 13.dp, bottom = 25.dp))
        if (state.saved.isEmpty()) {
            EmptyState("Keep a good thing close", "Tap the heart on a recording to save it here.", Icons.Outlined.FavoriteBorder)
            TextButton(onClick = onSearch, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Find music") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("${state.saved.size} SAVED")
                Button(onClick = { onPlay(state.saved.first(), state.saved) }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Play all", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.saved, key = { it.id }) { track -> TrackRow(track, true, state.current?.id == track.id, { onPlay(track, state.saved) }, { onSave(track) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowScreen(state: MusicState, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onSave: (Track) -> Unit, onQueue: () -> Unit) {
    val track = state.loadingTrack ?: state.current
    if (track == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Choose a recording to start listening.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val loading = state.resolving || state.buffering
    val pending = state.loadingTrack != null
    val duration = if (pending) 0 else state.durationMs
    val position = if (pending) 0 else state.positionMs
    val queueIndex = state.queueIndex
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)) {
        Artwork(track, Modifier.fillMaxWidth().aspectRatio(1.02f).clip(RoundedCornerShape(13.dp)))
        Spacer(Modifier.height(16.dp))
        ProgressLine(position, duration, state.playing && !pending, state.reducedMotion, onSeek)
        Spacer(Modifier.height(24.dp))
        Text(track.title, fontFamily = font, fontSize = 40.sp, lineHeight = 43.sp, letterSpacing = (-1.6).sp, fontWeight = FontWeight.Medium)
        Text(track.artist, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp))
        if (loading) Text(if (state.resolving) "Finding audio…" else "Buffering…", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(25.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            LabelledControl("Previous", Glyph.Previous, onPrevious, enabled = !pending && !loading)
            Button(onClick = onToggle, enabled = !loading, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(112.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                else Icon(if (state.playing && !pending) Glyph.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (loading) "Loading" else if (state.playing && !pending) "Pause" else "Play", fontSize = 12.sp)
            }
            LabelledControl("Next", Glyph.Next, onNext, enabled = !pending && !loading && queueIndex >= 0 && queueIndex < state.queue.lastIndex)
            val saved = state.saved.any { it.id == track.id }
            LabelledControl(if (saved) "Saved" else "Save", if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, { onSave(track) }, highlighted = saved)
        }
        Spacer(Modifier.height(26.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onQueue) { Text("Up next", fontSize = 11.sp); Spacer(Modifier.width(8.dp)); Icon(Glyph.Queue, null, Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressLine(position: Long, duration: Long, playing: Boolean, reduced: Boolean, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val actual = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val smooth by animateFloatAsState(actual, tween(if (reduced) 0 else 200, easing = LinearEasing), label = "audio progress")
    val fraction = drag ?: smooth
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    var shimmer = 0f
    if (playing && !reduced) {
        val transition = rememberInfiniteTransition(label = "playing line")
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "shimmer")
        shimmer = phase
    }
    Slider(
        value = fraction, onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
        enabled = duration > 0, valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "Playback position" },
        thumb = { Box(Modifier.size(12.dp).background(accent, CircleShape)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                val y = size.height / 2f - 2.dp.toPx()
                val h = 4.dp.toPx()
                drawRoundRect(line, Offset(0f, y), Size(size.width, h), CornerRadius(h))
                val played = (size.width * fraction).coerceAtLeast(.01f)
                drawRoundRect(accent, Offset(0f, y), Size(played, h), CornerRadius(h))
                if (playing && !reduced && played > 0) {
                    val centre = (played + 100.dp.toPx()) * shimmer - 50.dp.toPx()
                    val brush = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent), Offset(centre - 25.dp.toPx(), 0f), Offset(centre + 25.dp.toPx(), 0f))
                    drawRoundRect(brush, Offset(0f, y), Size(played, h), CornerRadius(h))
                }
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(((drag ?: actual) * duration).toLong()), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .5.sp)
        Text(if (duration > 0) clock(duration) else "—:—", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .5.sp)
    }
}

@Composable
private fun QueueScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(10.dp)); PageTitle("Keep it going")
        Spacer(Modifier.height(20.dp))
        if (state.queue.isEmpty()) EmptyState("A little quiet here", "Your play queue will appear when you choose a recording.", Glyph.Queue)
        else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { index, track -> "$index:${track.id}" }) { index, track -> TrackRow(track, false, index == state.queueIndex, { onPlay(track, state.queue) }, null) }
        }
    }
}

@Composable
private fun SettingsScreen(state: MusicState, onTheme: (String) -> Unit, onReduced: (Boolean) -> Unit, onLocal: () -> Unit, onClearHistory: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onCancelConnect: () -> Unit,
    onCheckUpdates: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notices = remember { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageTitle("Make yourself at home")
        Caption("LISTENING")
        SettingRow("Music source", "YouTube Music · search, links and provider feed")
        SettingRow("Recommendations", "Automatic · the provider’s order is preserved")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your YouTube account", fontSize = 14.sp)
            if (state.account.connected) {
                Text("${state.account.name.ifBlank { "YouTube" }} · your feed is personalised by YouTube", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDisconnect) { Text("Sign out", fontSize = 12.sp) }
                TextButton(onClick = onConnect) { Text("Change account", fontSize = 12.sp) }
            } else if (state.account.connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Finish sign-in on YouTube’s page.", fontSize = 12.sp)
                TextButton(onClick = onCancelConnect) { Text("Cancel sign-in", fontSize = 12.sp) }
            } else {
                Text("Sign in on Google’s own page to bring your YouTube recommendations here.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onConnect, shape = RoundedCornerShape(12.dp)) { Text("Sign in to YouTube", fontSize = 12.sp) }
            }
            state.account.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 18.sp) }
        }
        SettingRow("Audio processing", "Off · original audio")
        OutlinedButton(onClick = onLocal, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Glyph.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Open audio from device", fontSize = 12.sp) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Caption("LOOK & FEEL")
        Text("Appearance", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Device", "light" to "Light", "dark" to "Dark").forEach { (id, name) ->
                FilterChip(selected = state.theme == id, onClick = { onTheme(id) }, label = { Text(name, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Reduce motion", fontSize = 14.sp); Text("A quieter player animation", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            Switch(checked = state.reducedMotion, onCheckedChange = onReduced, modifier = Modifier.semantics { contentDescription = "Reduce motion" })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Caption("APP UPDATES")
        SettingRow("Music ${BuildConfig.VERSION_NAME}", "Preview channel · GitHub Releases")
        if (state.update.message.isNotBlank()) Text(state.update.message, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.update.downloading) {
            LinearProgressIndicator(progress = { state.update.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${state.update.progress}%", fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckUpdates, enabled = !state.update.checking && !state.update.downloading) { Text("Check updates", fontSize = 12.sp) }
            if (state.update.apkPath != null) Button(onClick = onInstallUpdate) { Text("Install update", fontSize = 12.sp) }
            else if (state.update.release != null) Button(onClick = onDownloadUpdate, enabled = !state.update.downloading && !state.update.checking) { Text("Download", fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Caption("ON THIS DEVICE")
        Text("Saved recordings, history and your encrypted YouTube session stay on this device. YouTube receives your provider requests; GitHub receives update checks.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirmClear = true }, enabled = state.history.isNotEmpty()) { Text("Clear listening history", fontSize = 12.sp) }
        TextButton(onClick = { showLicences = true }) { Text("Open-source licences", fontSize = 12.sp) }
        Text("Music · RTensorLabs\nEarly build ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("Your saved recordings will stay here.") },
        confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } },
    )
    if (showLicences) AlertDialog(
        onDismissRequest = { showLicences = false },
        title = { Text("Open-source licences") },
        text = { Text(notices, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } },
    )
}

@Composable
private fun MiniPlayer(state: MusicState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val track = state.current ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).border(.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        IconButton(onClick = onToggle, enabled = !state.resolving, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (state.playing) Glyph.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
        val i = state.queueIndex
        IconButton(onClick = onNext, enabled = i >= 0 && i < state.queue.lastIndex, modifier = Modifier.size(48.dp)) { Icon(Glyph.Next, "Next recording", Modifier.size(21.dp)) }
    }
}

@Composable
private fun TrackRow(track: Track, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: (() -> Unit)?) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onPlay).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            if (track.durationMs > 0) Text(clock(track.durationMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (onSave != null) IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Unsave ${track.title}" else "Save ${track.title}", Modifier.size(19.dp), tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Artwork(track: Track?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = track?.artwork?.takeIf { it.isNotBlank() }, contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.art_main), error = painterResource(R.drawable.art_main), fallback = painterResource(R.drawable.art_main),
    )
}

@Composable
private fun LabelledControl(label: String, image: ImageVector, onClick: () -> Unit, enabled: Boolean = true, highlighted: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(48.dp)) {
            Icon(image, label, Modifier.size(21.dp), tint = if (highlighted) MaterialTheme.colorScheme.primary else if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        }
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PageTitle(text: String) {
    val title = buildAnnotatedString { append(text); withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append('.') } }
    Text(title, fontFamily = font, fontSize = 35.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp)
}

@Composable
private fun SectionTitle(text: String) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp) }

@Composable
private fun Caption(text: String) { Text(text, fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun SettingRow(title: String, detail: String) {
    Column { Text(title, fontSize = 14.sp); Text(detail, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp)) }
}

@Composable
private fun EmptyState(title: String, detail: String, image: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Text(detail, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 9.dp, start = 12.dp, end = 12.dp))
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onRetry: () -> Unit, diagnostic: String = "", videoId: String = "") {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp)) {
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                if (diagnostic.isNotBlank()) TextButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Playback details", "Music ${BuildConfig.VERSION_NAME} · $videoId\n$diagnostic"))
                }) { Text("Copy details", fontSize = 11.sp) }
                TextButton(onClick = onDismiss) { Text("Dismiss", fontSize = 11.sp) }
                TextButton(onClick = onRetry) { Text("Retry", fontSize = 11.sp) }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
