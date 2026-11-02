package labs.rtensor.music.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import labs.rtensor.music.MusicState
import labs.rtensor.music.R
import labs.rtensor.music.data.ArtworkUrl
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.BuildConfig
import java.util.Locale
import kotlin.math.abs

private val font = FontFamily(Font(R.font.dm_sans))
private val Light = lightColorScheme(
    primary = Color(0xFFD72F62), onPrimary = Color.White,
    background = Color(0xFFF9F8F2), onBackground = Color(0xFF161921),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF161921),
    surfaceVariant = Color(0xFFF1EEE7), onSurfaceVariant = Color(0xFF555860),
    outline = Color(0xFFDFDDD6), secondaryContainer = Color(0xFFF9E5EB),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFF05B86), onPrimary = Color(0xFF240A13),
    background = Color(0xFF19191C), onBackground = Color(0xFFF2F0E9),
    surface = Color(0xFF222125), onSurface = Color(0xFFF2F0E9),
    surfaceVariant = Color(0xFF302C32), onSurfaceVariant = Color(0xFFB4AFB6),
    outline = Color(0xFF3A373D), secondaryContainer = Color(0xFF432733),
)

/** Material's emphasized curves: quick to respond, gentle to settle. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

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

private val rootPages = listOf("home", "explore", "saved")

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
    onGoogleSignIn: () -> Unit = {}, onGoogleSignOut: () -> Unit = {}, onCancelGoogle: () -> Unit = {},
    onCheckUpdates: () -> Unit = {}, onDownloadUpdate: () -> Unit = {}, onInstallUpdate: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var back by rememberSaveable { mutableStateOf("home") }
    var root by rememberSaveable { mutableStateOf(initialPage.takeIf { it in rootPages } ?: "home") }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = state.reducedMotion
    fun focusSearch() { searchFocus.requestFocus(); keyboard?.show() }
    fun navigate(next: String) {
        if (next in rootPages) { root = next; onQuery(""); keyboard?.hide() }
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
        if (page in rootPages && state.query.isNotEmpty()) onQuery("")
        else page = when (page) { "queue" -> "now"; "now" -> back; "settings", "collection" -> root; else -> "home" }
    }
    // Keep the last track while the mini player animates away.
    var lastTrack by remember { mutableStateOf(state.current) }
    if (state.current != null) lastTrack = state.current
    MusicTheme(state.theme) {
        Scaffold(
            topBar = {
                AppHeader(page, onHome = { navigate("home") }, onSettings = { navigate("settings") }, onBack = {
                    page = when (page) { "queue" -> "now"; "now" -> back; else -> root }
                })
            },
            bottomBar = {
                if (page in rootPages + "collection") {
                    Column(Modifier.navigationBarsPadding()) {
                        AnimatedVisibility(
                            visible = state.current != null,
                            enter = if (reduced) EnterTransition.None else slideInVertically(tween(360, easing = EmphasizedDecelerate)) { it } + fadeIn(tween(240)),
                            exit = if (reduced) ExitTransition.None else slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { it } + fadeOut(tween(160)),
                        ) {
                            lastTrack?.let { MiniPlayer(state.copy(current = it), { navigate("now") }, onToggle, onNext) }
                        }
                        AppNavigation(if (page == "collection") root else page, reduced, ::navigate)
                    }
                } else Spacer(Modifier.navigationBarsPadding())
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                if (page in rootPages) SearchBar(state, onQuery, onSearch, searchFocus)
                var lastError by remember { mutableStateOf(state.error) }
                if (state.error != null) lastError = state.error
                AnimatedVisibility(
                    visible = state.error != null,
                    enter = if (reduced) EnterTransition.None else expandVertically(tween(260, easing = Standard)) + fadeIn(tween(200, 60)),
                    exit = if (reduced) ExitTransition.None else shrinkVertically(tween(220, easing = Standard)) + fadeOut(tween(120)),
                ) {
                    ErrorBanner(lastError.orEmpty(), onDismissError,
                        if (state.searched && page in rootPages) onSearch else if (page == "collection") onRetryCollection else onRetry,
                        state.errorDiagnostic, state.current?.id.orEmpty())
                }
                val showResults = page in rootPages && state.searched && state.query.isNotBlank()
                AnimatedContent(
                    targetState = if (showResults) "search" else page,
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else {
                            val forward = targetState in listOf("now", "queue", "settings", "collection", "search")
                            val enter = fadeIn(tween(220, delayMillis = 70, easing = LinearOutSlowInEasing)) +
                                slideInVertically(tween(340, easing = EmphasizedDecelerate)) { h -> if (forward) h / 18 else -h / 40 } +
                                scaleIn(tween(340, easing = EmphasizedDecelerate), initialScale = if (forward) .985f else 1.01f)
                            val exit = fadeOut(tween(90, easing = FastOutLinearInEasing))
                            (enter togetherWith exit).using(SizeTransform(clip = false))
                        }
                    },
                    label = "screen", modifier = Modifier.weight(1f),
                ) { target ->
                    when (target) {
                        "search" -> SearchResults(state, ::play, onSave)
                        "explore" -> ProviderHome(state.copy(feed = state.explore, feedBusy = state.exploreBusy, feedError = state.exploreError, feedHasMore = false), ::openEntry, onRefreshExplore, {}, "Explore")
                        "saved" -> SavedScreen(state, ::play, onSave, ::focusSearch)
                        "now" -> NowScreen(state, onToggle, onPrevious, onNext, onSeek, onSave) { navigate("queue") }
                        "queue" -> QueueScreen(state, ::play)
                        "collection" -> CollectionScreen(state, ::play, onSave)
                        "settings" -> SettingsScreen(state, onTheme, onReducedMotion, onOpenLocal, onClearHistory,
                            onConnect, onDisconnect, onCancelConnect, onGoogleSignIn, onGoogleSignOut, onCancelGoogle, onCheckUpdates, onDownloadUpdate, onInstallUpdate)
                        else -> HomeScreen(state, ::focusSearch, onOpenLocal, ::play, ::openEntry, onRefreshFeed, onMoreFeed, onConnect)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(page: String, onHome: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (page in listOf("now", "queue", "settings", "collection")) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Caption(when (page) { "now" -> "NOW PLAYING"; "queue" -> "UP NEXT"; "collection" -> "COLLECTION"; else -> "SETTINGS" })
            Spacer(Modifier.width(48.dp))
        } else {
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHome).padding(6.dp)) {
                Text("MUSIC", fontFamily = font, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.width(22.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings, "Open settings", Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun AppNavigation(page: String, reduced: Boolean, onSelect: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("home", "Home", Icons.Outlined.Home), Triple("explore", "Explore", Glyph.Compass), Triple("saved", "Saved", Glyph.Bookmark)).forEach { (id, name, icon) ->
            val selected = page == id
            val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (reduced) 0 else 220), label = "nav colour")
            val pill by animateFloatAsState(if (selected) 1f else 0f,
                if (reduced) snap<Float>() else spring<Float>(dampingRatio = .75f, stiffness = Spring.StiffnessMediumLow), label = "nav pill")
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = source, indication = null) { onSelect(id) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(width = 60.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().graphicsLayer { scaleX = .45f + .55f * pill; alpha = pill }
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)))
                    Icon(icon, name, Modifier.size(22.dp), tint = color)
                }
                Spacer(Modifier.height(4.dp))
                Text(name, fontSize = 11.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: MusicState, onSearch: () -> Unit, onLocal: () -> Unit, onPlay: (Track, List<Track>) -> Unit,
    onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onConnect: () -> Unit) {
    val recent = state.history.firstOrNull()
    val hasFeed = state.feed.isNotEmpty()
    // The "Back to your music" hero stays at the top of Home, also after the provider feed has loaded.
    val showHero = recent != null || !hasFeed
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(key = "title") {
            Column {
                PageTitle(if (recent != null) "Back to your music" else if (hasFeed) "For your ears" else "Room to listen")
                Spacer(Modifier.height(18.dp))
                LoadingLine(state.feedBusy, state.reducedMotion)
            }
        }
        val google = state.googleProfile
        if (google != null && !state.account.connected && !state.account.connecting) item(key = "sync") {
            SyncBanner(google.email, onConnect, Modifier.animateItem())
        }
        if (state.feedError != null) item(key = "feed-error") {
            Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.feedError, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Retry", fontSize = 12.sp) }
            }
        }
        if (showHero) item(key = "hero") {
            Row(Modifier.fillMaxWidth().heightIn(min = 265.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.46f)) {
                    Caption(if (recent == null) "START WITH A SONG" else "LAST PLAYED")
                    Spacer(Modifier.height(17.dp))
                    Text(recent?.title ?: "Find your\nnext repeat.", fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 31.sp, lineHeight = 34.sp, letterSpacing = (-1.2).sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Text(recent?.artist ?: "A song, an artist,\na familiar feeling.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(27.dp))
                    Row(Modifier.clip(RoundedCornerShape(24.dp)).pressable { if (recent != null) onPlay(recent, state.history) else onSearch() }.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (recent == null) Icons.Outlined.Search else Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(if (recent == null) "Find music" else "Play", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
                Artwork(recent, Modifier.weight(.54f).height(270.dp).clip(RoundedCornerShape(14.dp)))
            }
        }
        item(key = "local") {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
                Row(Modifier.fillMaxWidth().clickable(onClick = onLocal).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Glyph.AudioFile)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Already yours", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Open an audio file from your device", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open audio file", Modifier.size(19.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
            }
        }
        if (state.history.isNotEmpty()) item(key = "recent") {
            Column {
                SectionTitle("Recently played")
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.history.take(12), key = { it.id }) { track ->
                        MediaCard(track.title, track.artist, track, 142.dp, Modifier.animateItem()) { onPlay(track, state.history) }
                    }
                }
            }
        }
        if (hasFeed) {
            item(key = "feed-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption("FROM YOUTUBE MUSIC", Modifier.weight(1f))
                    IconButton(onClick = onRefresh, enabled = !state.feedBusy) { Icon(Icons.Outlined.Refresh, "Refresh feed", Modifier.size(18.dp)) }
                }
            }
            feedSections(state.feed, onEntry)
            if (state.feedHasMore) item(key = "more") {
                OutlinedButton(onClick = onMore, enabled = !state.feedBusy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("More from your feed") }
            }
        }
    }
}

@Composable
private fun SyncBanner(email: String, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Finish syncing YouTube Music", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("$email is chosen. Connect it to YouTube Music to bring your feed here.", fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onConnect, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Connect", fontSize = 13.sp) }
        }
    }
}

/** Reserves its height so content below never jumps when loading starts or stops. */
@Composable
private fun LoadingLine(busy: Boolean, reduced: Boolean) {
    val alpha by animateFloatAsState(if (busy) 1f else 0f, tween(if (reduced) 0 else 300), label = "loading line")
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        if (busy || alpha > .01f) LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp).graphicsLayer { this.alpha = alpha },
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f), strokeCap = StrokeCap.Round,
        )
    }
}

private fun LazyListScope.feedSections(sections: List<FeedSection>, onEntry: (FeedEntry) -> Unit) {
    itemsIndexed(sections, key = { i, section -> "feed:$i:${section.title}" }) { _, section ->
        Column(Modifier.animateItem()) {
            SectionTitle(section.title)
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(section.items, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
                    MediaCard(entry.title, entry.subtitle, Track(entry.id, entry.title, entry.subtitle, "", entry.artwork), 156.dp) { onEntry(entry) }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(title: String, subtitle: String, art: Track, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(size).clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick)) {
        Artwork(art, Modifier.size(size).clip(RoundedCornerShape(12.dp)))
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp, bottom = 4.dp))
    }
}

@Composable
private fun ProviderHome(state: MusicState, onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, title: String = "For your ears") {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(key = "title") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PageTitle(title) }
                    IconButton(onClick = onRefresh, enabled = !state.feedBusy) { Icon(Icons.Outlined.Refresh, "Refresh", Modifier.size(20.dp)) }
                }
                if (title == "Explore") Text("Fresh releases, charts and discoveries from YouTube Music.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(16.dp))
                LoadingLine(state.feedBusy, state.reducedMotion)
            }
        }
        feedSections(state.feed, onEntry)
        if (state.feedError != null) item(key = "error") { Text(state.feedError, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.feed.isEmpty() && !state.feedBusy && state.feedError == null) item(key = "empty") { EmptyState("More music awaits", "Tap Refresh to load discoveries from YouTube Music.", Glyph.Compass) }
        if (state.feedHasMore) item(key = "more") { OutlinedButton(onClick = onMore, enabled = !state.feedBusy, modifier = Modifier.fillMaxWidth()) { Text("More from your feed") } }
    }
}

@Composable
private fun CollectionScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(state.collectionEntry?.title.orEmpty(), fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.collectionEntry?.subtitle.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        if (state.collectionBusy) LoadingLine(true, state.reducedMotion)
        else if (state.collection.isEmpty() && state.error == null) EmptyState("Nothing to play here", "This collection has no available recordings.", Glyph.MusicNote)
        else {
            Button(onClick = { state.collection.firstOrNull()?.let { onPlay(it, state.collection) } }, enabled = state.collection.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.PlayArrow, null); Text("Play all") }
            Spacer(Modifier.height(14.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
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
        placeholder = { Text("Songs, artists or a YouTube link", fontSize = 13.sp) },
        leadingIcon = { IconButton(onClick = submit, enabled = state.query.isNotBlank() && !state.searching) { Icon(Icons.Outlined.Search, "Search YouTube", Modifier.size(20.dp)) } },
        trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery(""); keyboard?.hide() }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) } },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SearchResults(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        when {
            state.searching -> {
                LoadingLine(true, state.reducedMotion)
                Text("Looking on YouTube…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            }
            state.results.isNotEmpty() -> {
                Caption("${state.results.size} RESULTS")
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(state.results, key = { index, track -> "$index:${track.id}" }) { _, track ->
                        TrackRow(track, state.saved.any { it.id == track.id }, state.current?.id == track.id, { onPlay(track, state.results) }, { onSave(track) }, Modifier.animateItem())
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
                items(state.saved, key = { it.id }) { track -> TrackRow(track, true, state.current?.id == track.id, { onPlay(track, state.saved) }, { onSave(track) }, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun NowScreen(state: MusicState, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onSave: (Track) -> Unit, onQueue: () -> Unit) {
    val track = state.loadingTrack ?: state.current
    if (track == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Choose a recording to start listening.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val reduced = state.reducedMotion
    val loading = state.resolving || state.buffering
    val pending = state.loadingTrack != null
    val playing = state.playing && !pending
    val duration = if (pending) 0 else state.durationMs
    val position = if (pending) 0 else state.positionMs
    val queueIndex = state.queueIndex
    // Artwork settles back slightly while paused, then springs forward on play.
    val artScale by animateFloatAsState(if (playing || reduced) 1f else .93f,
        if (reduced) snap<Float>() else spring<Float>(dampingRatio = .7f, stiffness = Spring.StiffnessLow), label = "artwork scale")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)) {
        AnimatedContent(
            targetState = track, contentKey = { it.id }, label = "now playing",
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(260, 60)) + slideInHorizontally(tween(380, easing = EmphasizedDecelerate)) { it / 6 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(220, easing = EmphasizedAccelerate)) { -it / 8 })
            },
        ) { shown ->
            Column {
                Artwork(shown, Modifier.fillMaxWidth().aspectRatio(1.02f).graphicsLayer { scaleX = artScale; scaleY = artScale }.clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.height(16.dp))
                ProgressLine(position, duration, playing, reduced, onSeek)
                Spacer(Modifier.height(22.dp))
                Text(shown.title, fontFamily = font, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(shown.artist, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(loading, enter = fadeIn(tween(if (reduced) 0 else 200)), exit = fadeOut(tween(if (reduced) 0 else 200))) {
            Text(if (state.resolving) "Finding audio…" else "Buffering…", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(25.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            LabelledControl("Previous", Glyph.Previous, onPrevious, enabled = !pending && !loading)
            Button(onClick = onToggle, enabled = !loading, shape = RoundedCornerShape(14.dp), modifier = Modifier.width(116.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                AnimatedContent(targetState = if (loading) 0 else if (playing) 1 else 2, label = "play button",
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(180)) + scaleIn(tween(220, easing = EmphasizedDecelerate), initialScale = .7f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(120), targetScale = .7f))
                    }) { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (mode) {
                            0 -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = LocalContentColor.current)
                            1 -> Icon(Glyph.Pause, null, Modifier.size(20.dp))
                            else -> Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(when (mode) { 0 -> "Loading"; 1 -> "Pause"; else -> "Play" }, fontSize = 12.sp)
                    }
                }
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

/**
 * The player reports its position a few times a second. Between reports, advance the
 * shown position with the display's frame clock so progress moves continuously instead
 * of in steps; small differences are blended in and large ones (seeks) are applied at once.
 */
@Composable
private fun rememberSmoothPosition(position: Long, duration: Long, animate: Boolean): Long {
    var shown by remember { mutableLongStateOf(position) }
    LaunchedEffect(position, duration, animate) {
        val drift = position - shown
        shown = if (!animate || abs(drift) > 750) position else shown + drift / 3
        if (!animate) return@LaunchedEffect
        val base = shown
        val limit = if (duration > 0) duration else Long.MAX_VALUE
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> shown = (base + (now - start) / 1_000_000).coerceAtMost(limit) }
    }
    return shown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressLine(position: Long, duration: Long, playing: Boolean, reduced: Boolean, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val smoothPosition = rememberSmoothPosition(position, duration, playing && !reduced && drag == null)
    val actual = if (duration > 0) (smoothPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = drag ?: actual
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    val thickness by animateDpAsState(if (drag != null) 6.dp else 4.dp, tween(if (reduced) 0 else 160), label = "track thickness")
    val thumb by animateDpAsState(if (drag != null) 18.dp else if (playing) 12.dp else 10.dp, if (reduced) snap<Dp>() else spring<Dp>(dampingRatio = .6f), label = "thumb size")
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
        thumb = { Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(thumb).background(accent, CircleShape)) } },
        track = {
            Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                val h = thickness.toPx()
                val y = size.height / 2f - h / 2f
                drawRoundRect(line, Offset(0f, y), Size(size.width, h), CornerRadius(h))
                val played = (size.width * fraction).coerceAtLeast(.01f)
                drawRoundRect(accent, Offset(0f, y), Size(played, h), CornerRadius(h))
                if (playing && !reduced && played > 0) {
                    val centre = (played + 100.dp.toPx()) * shimmer - 50.dp.toPx()
                    val brush = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .45f), Color.Transparent), Offset(centre - 25.dp.toPx(), 0f), Offset(centre + 25.dp.toPx(), 0f))
                    drawRoundRect(brush, Offset(0f, y), Size(played, h), CornerRadius(h))
                }
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock((fraction * duration).toLong()), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .5.sp)
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
            itemsIndexed(state.queue, key = { index, track -> "$index:${track.id}" }) { index, track -> TrackRow(track, false, index == state.queueIndex, { onPlay(track, state.queue) }, null, Modifier.animateItem()) }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Settings: grouped cards with icon headers and a jump bar, so each option is easy to find.
// ---------------------------------------------------------------------------------------

private data class SettingsSection(val id: String, val label: String, val icon: ImageVector)
private val settingsSections = listOf(
    SettingsSection("account", "Account", Icons.Outlined.AccountCircle),
    SettingsSection("playback", "Playback", Glyph.MusicNote),
    SettingsSection("appearance", "Appearance", Glyph.Contrast),
    SettingsSection("updates", "Updates", Icons.Outlined.Refresh),
    SettingsSection("privacy", "Privacy", Glyph.Shield),
)

@Composable
private fun SettingsScreen(state: MusicState, onTheme: (String) -> Unit, onReduced: (Boolean) -> Unit, onLocal: () -> Unit, onClearHistory: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onCancelConnect: () -> Unit,
    onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit, onCancelGoogle: () -> Unit,
    onCheckUpdates: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notices = remember { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    fun Modifier.anchor(id: String) = onGloballyPositioned { anchors[id] = it.positionInParent().y.toInt() }
    Column(Modifier.fillMaxSize()) {
        // Jump bar stays visible while the page scrolls.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settingsSections.forEach { section ->
                AssistChip(
                    onClick = { scope.launch { scroll.animateScrollTo(((anchors[section.id] ?: 0) - 8).coerceAtLeast(0), tween(if (state.reducedMotion) 0 else 450, easing = Standard)) } },
                    label = { Text(section.label, fontSize = 13.sp) },
                    leadingIcon = { Icon(section.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageTitle("Settings")

            SettingsGroupHeader(settingsSections[0], Modifier.anchor("account"))
            AccountCard(state, onGoogleSignIn, onGoogleSignOut, onCancelGoogle, onConnect, onDisconnect, onCancelConnect)

            SettingsGroupHeader(settingsSections[1], Modifier.anchor("playback"))
            SettingsCard {
                SettingItem("Music source", "YouTube Music · search, links and your feed", Icons.Outlined.Search)
                CardDivider()
                SettingItem("Ad-free playback", "Audio streams play directly; ads are never requested", Icons.Outlined.CheckCircle)
                CardDivider()
                SettingItem("Background play", "Keeps playing with the screen off or in another app", Icons.Outlined.PlayArrow)
                CardDivider()
                SettingItem("Recommendations", "Automatic · the provider’s order is preserved", Icons.Outlined.Star)
                CardDivider()
                SettingItem("Audio processing", "Off · original audio", Glyph.MusicNote)
                CardDivider()
                SettingItem("Open audio from device", "Play a file that is already on this phone", Glyph.AudioFile, onClick = onLocal)
            }

            SettingsGroupHeader(settingsSections[2], Modifier.anchor("appearance"))
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Theme", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    ThemeSelector(state.theme, state.reducedMotion, onTheme)
                }
                CardDivider(start = 16.dp)
                SettingItem("Reduce motion", "Calmer transitions and a still progress line", Glyph.Contrast, onClick = { onReduced(!state.reducedMotion) }) {
                    Switch(checked = state.reducedMotion, onCheckedChange = onReduced, modifier = Modifier.semantics { contentDescription = "Reduce motion" })
                }
            }

            SettingsGroupHeader(settingsSections[3], Modifier.anchor("updates"))
            SettingsCard {
                SettingItem("Music ${BuildConfig.VERSION_NAME}", "Preview channel · GitHub Releases", Icons.Outlined.Info)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.update.message.isNotBlank()) Text(state.update.message, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.update.downloading) {
                        val progress by animateFloatAsState(state.update.progress / 100f, tween(300), label = "update progress")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                        Text("${state.update.progress}%", fontSize = 14.sp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheckUpdates, enabled = !state.update.checking && !state.update.downloading, shape = RoundedCornerShape(12.dp)) { Text("Check updates", fontSize = 14.sp) }
                        if (state.update.apkPath != null) Button(onClick = onInstallUpdate, shape = RoundedCornerShape(12.dp)) { Text("Install update", fontSize = 14.sp) }
                        else if (state.update.release != null) Button(onClick = onDownloadUpdate, enabled = !state.update.downloading && !state.update.checking, shape = RoundedCornerShape(12.dp)) { Text("Download", fontSize = 14.sp) }
                    }
                }
            }

            SettingsGroupHeader(settingsSections[4], Modifier.anchor("privacy"))
            SettingsCard {
                Text("Saved recordings, history and your encrypted YouTube session stay on this device. YouTube receives your provider requests; GitHub receives update checks.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                CardDivider(start = 16.dp)
                SettingItem("Clear listening history", if (state.history.isEmpty()) "Nothing to clear" else "${state.history.size} recent recordings", Icons.Outlined.Delete,
                    onClick = if (state.history.isNotEmpty()) ({ confirmClear = true }) else null)
                CardDivider()
                SettingItem("Open-source licences", "Libraries and fonts used by Music", Icons.Outlined.Info, onClick = { showLicences = true })
            }
            Text("Music · RTensorLabs · Early build ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 28.dp))
        }
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
        text = { Text(notices, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } },
    )
}

/**
 * One account card for both sign-ins. Choosing a Google account immediately continues to
 * YouTube Music with that email preselected, and signing out clears both together.
 */
@Composable
private fun AccountCard(state: MusicState, onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit, onCancelGoogle: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onCancelConnect: () -> Unit) {
    val google = state.googleProfile
    val youtube = state.account
    val displayName = google?.name?.ifBlank { google.email } ?: youtube.name.takeIf { youtube.connected && it.isNotBlank() } ?: "Not signed in"
    val detail = google?.email ?: if (youtube.connected) "YouTube Music account" else "Sign in once to sync Google and YouTube Music."
    SettingsCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(if (google != null || youtube.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                if (google != null || youtube.connected) Text(displayName.trim().take(1).uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Outlined.AccountCircle, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        CardDivider(start = 16.dp)
        StatusLine("Google account", google != null, if (google != null) "Chosen on this device" else "Not chosen")
        StatusLine("YouTube Music", youtube.connected, when {
            youtube.connected -> "Connected" + youtube.name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            youtube.connecting -> "Finishing sign-in…"
            else -> "Not connected"
        })
        AnimatedVisibility(state.googleBusy || youtube.connecting, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LoadingLine(true, state.reducedMotion)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (state.googleError ?: youtube.error)?.let { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.primary) }
            val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            when {
                state.googleBusy -> OutlinedButton(onClick = onCancelGoogle, modifier = wide, shape = RoundedCornerShape(14.dp)) { Text("Cancel Google sign-in", fontSize = 15.sp) }
                youtube.connecting -> {
                    Text("Finish sign-in on YouTube’s page.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCancelConnect, modifier = wide, shape = RoundedCornerShape(14.dp)) { Text("Cancel sign-in", fontSize = 15.sp) }
                }
                google == null && !youtube.connected -> {
                    Button(onClick = onGoogleSignIn, modifier = wide, shape = RoundedCornerShape(14.dp)) { Text("Sign in with Google", fontSize = 15.sp) }
                    Text("Choose an account on this phone. YouTube Music opens next with it preselected, so both stay in sync.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onConnect) { Text("Sign in to YouTube", fontSize = 15.sp) }
                }
                google != null && !youtube.connected -> {
                    Button(onClick = onConnect, modifier = wide, shape = RoundedCornerShape(14.dp)) { Text("Connect YouTube Music", fontSize = 15.sp) }
                    Text("Finishes syncing ${google.email}. You’ll return here automatically.", fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow {
                        TextButton(onClick = onGoogleSignIn) { Text("Change Google account", fontSize = 15.sp) }
                        TextButton(onClick = onGoogleSignOut) { Text("Sign out", fontSize = 15.sp) }
                    }
                }
                else -> {
                    OutlinedButton(onClick = if (google != null) onGoogleSignIn else onConnect, modifier = wide, shape = RoundedCornerShape(14.dp)) {
                        Text(if (google != null) "Change Google account" else "Change account", fontSize = 15.sp)
                    }
                    FlowRow {
                        TextButton(onClick = if (google != null) onGoogleSignOut else onDisconnect) { Text("Sign out", fontSize = 15.sp) }
                        if (google == null) TextButton(onClick = onGoogleSignIn) { Text("Link Google account", fontSize = 15.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val tint by animateColorAsState(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "status")
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }
}

@Composable
private fun ThemeSelector(theme: String, reduced: Boolean, onTheme: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("system" to "Device", "light" to "Light", "dark" to "Dark").forEach { (id, name) ->
            val selected = theme == id
            val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, tween(if (reduced) 0 else 220), label = "theme bg")
            val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(if (reduced) 0 else 220), label = "theme fg")
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(bg).clickable { onTheme(id) }.semantics { contentDescription = "$name theme" },
                contentAlignment = Alignment.Center) {
                Text(name, fontSize = 14.sp, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(section: SettingsSection, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(section.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(section.label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp).animateContentSize(), content = content)
    }
}

@Composable
private fun CardDivider(start: Dp = 66.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
}

@Composable
private fun SettingItem(title: String, detail: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

// ---------------------------------------------------------------------------------------
// Mini player
// ---------------------------------------------------------------------------------------

@Composable
private fun MiniPlayer(state: MusicState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val track = state.current ?: return
    val reduced = state.reducedMotion
    val loading = state.resolving || state.buffering
    val position = rememberSmoothPosition(state.positionMs, state.durationMs, state.playing && !reduced)
    val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = track, contentKey = { it.id }, label = "mini track", modifier = Modifier.weight(1f),
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(220, 60)) + slideInVertically(tween(300, easing = EmphasizedDecelerate)) { it / 2 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { -it / 2 })
                    },
                ) { shown ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(shown, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(shown.title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shown.artist, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                PlayPauseCircle(state.playing, loading, reduced, enabled = !state.resolving, onClick = onToggle)
                val i = state.queueIndex
                IconButton(onClick = onNext, enabled = i >= 0 && i < state.queue.lastIndex, modifier = Modifier.size(44.dp)) { Icon(Glyph.Next, "Next recording", Modifier.size(20.dp)) }
            }
            // Progress runs along the bottom edge of the card.
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .6f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun PlayPauseCircle(playing: Boolean, loading: Boolean, reduced: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .88f else 1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "play press")
    Box(
        Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = if (loading) 0 else if (playing) 1 else 2, label = "mini play",
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(160)) + scaleIn(tween(220, easing = EmphasizedDecelerate), initialScale = .6f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(120), targetScale = .6f))
            }) { mode ->
            when (mode) {
                0 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                1 -> Icon(Glyph.Pause, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                // The play triangle is optically off-centre; nudge it right by 1dp.
                else -> Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp).offset(x = 1.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------

/** Clickable with a soft spring "press-in", used for cards and primary tap targets. */
private fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .6f, stiffness = Spring.StiffnessMediumLow), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
}

@Composable
private fun TrackRow(track: Track, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onPlay).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val titleColor by animateColorAsState(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, label = "row title")
                    Text(track.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                if (track.durationMs > 0) Text(clock(track.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (onSave != null) IconButton(onClick = onSave) {
                Crossfade(saved, label = "save icon") { isSaved ->
                    Icon(if (isSaved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (isSaved) "Unsave ${track.title}" else "Save ${track.title}", Modifier.size(20.dp), tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Artwork(track: Track?, modifier: Modifier = Modifier) {
    val source = track?.artwork?.takeIf { it.isNotBlank() }
    var useFallback by remember(source) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(source, useFallback) {
        ImageRequest.Builder(context).data(if (useFallback) source else source?.let(ArtworkUrl::original)).crossfade(220).build()
    }
    AsyncImage(
        model = request, contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        onError = { useFallback = true },
        placeholder = painterResource(R.drawable.art_main), error = painterResource(R.drawable.art_main), fallback = painterResource(R.drawable.art_main),
    )
}

@Composable
private fun LabelledControl(label: String, image: ImageVector, onClick: () -> Unit, enabled: Boolean = true, highlighted: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(48.dp)) {
            val tint by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.primary else if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), label = "control tint")
            Icon(image, label, Modifier.size(21.dp), tint = tint)
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
private fun Caption(text: String, modifier: Modifier = Modifier) { Text(text, fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier) }

@Composable
private fun EmptyState(title: String, detail: String, image: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Text(detail, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp, start = 12.dp, end = 12.dp))
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onRetry: () -> Unit, diagnostic: String = "", videoId: String = "") {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
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
