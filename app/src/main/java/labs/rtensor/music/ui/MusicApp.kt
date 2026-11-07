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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.RectangleShape
import labs.rtensor.music.data.Recommender
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.music.ExploreShelves
import labs.rtensor.music.exploreShelves
import labs.rtensor.music.MusicState
import labs.rtensor.music.R
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LookChoice
import labs.rtensor.music.data.ArtworkUrl
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.BuildConfig
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.SourceProtocol
import java.util.Locale
import kotlin.math.abs

/** Signal type: Archivo for reading, condensed Archivo for display, DM Mono for labels and numbers. */
private val ArchivoFont = FontFamily(
    Font(R.font.archivo_regular),
    Font(R.font.archivo_semibold, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_semibold, FontWeight.Bold),
)
private val Display = FontFamily(Font(R.font.archivo_condensed_extrabold, FontWeight.ExtraBold))
/** Soft's one face: DM Sans for everything, as in 0.3.31. */
private val SoftFont = FontFamily(Font(R.font.dm_sans))
/** Hum's headline face: Bricolage Grotesque (variable), set heavy. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val HumDisplay = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.SemiBold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(600))),
    Font(R.font.bricolage_grotesque, FontWeight.Bold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))),
    Font(R.font.bricolage_grotesque, FontWeight.ExtraBold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(800))),
)
/** The reading face of the current look: Archivo, or DM Sans in Soft. */
private val font: FontFamily
    @Composable @ReadOnlyComposable get() = if (LocalLook.current.soft || LocalLook.current.hum) SoftFont else ArchivoFont
internal val Mono = FontFamily(Font(R.font.dm_mono_regular), Font(R.font.dm_mono_medium, FontWeight.Medium))
internal val Ink = Color(0xFF111111)
internal val Paper = Color(0xFFEDEDEA)
internal val PaperMuted = Color(0xFFB8B8B2)
internal val InkRule = Color(0xFF333330)
private val Light = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFDCDCD8), onSurfaceVariant = Color(0xFF4A4A46),
    outline = Color(0xFFC9C9C4), secondaryContainer = SignalAccent, onSecondaryContainer = Ink,
    primaryContainer = SignalAccent, onPrimaryContainer = Ink,
)
private val Dark = darkColorScheme(
    primary = Paper, onPrimary = Ink,
    background = Ink, onBackground = Paper,
    surface = Ink, onSurface = Paper,
    surfaceVariant = Color(0xFF1E1E1C), onSurfaceVariant = PaperMuted,
    outline = InkRule, secondaryContainer = SignalAccent, onSecondaryContainer = Ink,
    primaryContainer = SignalAccent, onPrimaryContainer = Ink,
)
/** Sentence-case labels and small headings. Monospace is kept for technical facts only. */
internal val LabelStyle = TextStyle(fontFamily = ArchivoFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = .01.em)
/** The look's label style: DM Mono in Classic, sentence-case Archivo in the Listening room. */
internal val LabelText: TextStyle
    @Composable @ReadOnlyComposable get() = when {
        LocalLook.current.classic -> MonoLabel
        LocalLook.current.soft || LocalLook.current.hum -> TextStyle(fontFamily = SoftFont, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        else -> LabelStyle
    }
internal val MonoLabel = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = .06.em)
internal val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = .04.em)
private val DisplayStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.01).em)
/** Listening room poster type: the condensed face, set uppercase on screen. */
private val PosterStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, letterSpacing = .01.em)

/** Soft's Material roles: the 0.3.31 scheme, with the raspberry adjusted for contrast (see [softLook]). */
private fun softScheme(look: Look) = if (look.dark) darkColorScheme(
    primary = SoftRaspberryNight, onPrimary = softOnPrimary(true),
    background = look.background, onBackground = look.text, surface = look.surface, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted, outline = look.line, outlineVariant = look.line,
    secondaryContainer = softContainer(true), onSecondaryContainer = look.text,
    primaryContainer = softContainer(true), onPrimaryContainer = look.text,
    surfaceContainerLowest = look.background, surfaceContainerLow = look.surface, surfaceContainer = look.surface,
    surfaceContainerHigh = look.surface, surfaceContainerHighest = look.panel,
) else lightColorScheme(
    primary = SoftRaspberry, onPrimary = softOnPrimary(false),
    background = look.background, onBackground = look.text, surface = look.surface, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted, outline = look.line, outlineVariant = look.line,
    secondaryContainer = softContainer(false), onSecondaryContainer = look.text,
    primaryContainer = softContainer(false), onPrimaryContainer = look.text,
    surfaceContainerLowest = look.surface, surfaceContainerLow = look.surface, surfaceContainer = look.surface,
    surfaceContainerHigh = look.surface, surfaceContainerHighest = look.panel,
)

/** Hum's Material roles: pink accent, plum surfaces. */
private fun humScheme(look: Look, a: AccentColours) = if (look.dark) darkColorScheme(
    primary = a.accent, onPrimary = a.onAccent,
    background = look.background, onBackground = look.text, surface = look.background, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted,
    surfaceContainerLowest = look.background, surfaceContainerLow = look.panel, surfaceContainer = look.panel,
    surfaceContainerHigh = look.panel, surfaceContainerHighest = look.panel,
    outline = look.line, outlineVariant = look.line,
    secondaryContainer = a.accent, onSecondaryContainer = a.onAccent, primaryContainer = a.accent, onPrimaryContainer = a.onAccent,
) else lightColorScheme(
    primary = a.accent, onPrimary = a.onAccent,
    background = look.background, onBackground = look.text, surface = look.background, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted,
    surfaceContainerLowest = look.background, surfaceContainerLow = look.panel, surfaceContainer = look.panel,
    surfaceContainerHigh = look.panel, surfaceContainerHighest = look.panel,
    outline = look.line, outlineVariant = look.line,
    secondaryContainer = a.accent, onSecondaryContainer = a.onAccent, primaryContainer = a.accent, onPrimaryContainer = a.onAccent,
)

/** The Listening room's Material roles, built from its tokens. */
private fun roomScheme(look: Look) = if (look.dark) darkColorScheme(
    primary = look.text, onPrimary = look.background,
    background = look.background, onBackground = look.text,
    surface = look.background, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted,
    surfaceContainerLowest = look.background, surfaceContainerLow = look.panel, surfaceContainer = look.panel,
    surfaceContainerHigh = look.panel, surfaceContainerHighest = look.panel,
    outline = look.line, outlineVariant = look.line,
    secondaryContainer = SignalAccent, onSecondaryContainer = Ink, primaryContainer = SignalAccent, onPrimaryContainer = Ink,
) else lightColorScheme(
    primary = look.text, onPrimary = look.background,
    background = look.background, onBackground = look.text,
    surface = look.background, onSurface = look.text,
    surfaceVariant = look.panel, onSurfaceVariant = look.muted,
    surfaceContainerLowest = look.background, surfaceContainerLow = look.panel, surfaceContainer = look.panel,
    surfaceContainerHigh = look.panel, surfaceContainerHighest = look.panel,
    outline = look.line, outlineVariant = look.line,
    secondaryContainer = SignalAccent, onSecondaryContainer = Ink, primaryContainer = SignalAccent, onPrimaryContainer = Ink,
)

/** Material's emphasized curves: quick to respond, gentle to settle. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
/** Springs settle naturally and can be interrupted mid-flight without a jump. */
private val PageSpring = spring<IntOffset>(dampingRatio = .88f, stiffness = 420f)
/** Durations: 240 ms to arrive, 180 ms to go back. Small changes use [QuickMs]. */
private const val EnterMs = 240
private const val ExitMs = 180
/** Presses, toggles and indicators settle critically damped: quick, interruptible, no bounce. */
private val PressSpring = spring<Float>(dampingRatio = 1f, stiffness = 900f)
private val IndicatorSpring = spring<Float>(dampingRatio = 1f, stiffness = 520f)
/** The dock slides on a spring, so a quick open/close never snaps mid-flight. */
private val DockSpring = spring<IntOffset>(dampingRatio = 1f, stiffness = 480f)

/** Accent changes (a new choice, or the next cover) blend over this long. */
private const val AccentMs = 240

/**
 * The Signal theme. [accent] picks the one strong colour; with [AccentChoice.COVER] the
 * colour comes from [coverAccent], or Signal when there is none. Read it through [LocalAccent]:
 * the accent fill, the text colour on it, and its form on the black dock.
 */
@Composable
fun MusicTheme(theme: String = "light", darkNavigationBar: Boolean = false, accent: AccentChoice = AccentChoice.SIGNAL,
    coverAccent: Color? = null, darkStatusBar: Boolean = false, look: LookChoice = LookChoice.DEFAULT, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val tokens = remember(look, dark) { lookFor(look, dark) }
    // Classic is the 0.3.32 Signal look: always the Signal orange, never a cover colour. Soft is
    // the 0.3.31 design: always its own raspberry.
    val chosen = if (tokens.classic) AccentChoice.SIGNAL else accent
    val target = remember(chosen, dark, coverAccent, tokens) {
        if (tokens.soft) (if (dark) SoftRaspberryNight else SoftRaspberry).let { AccentColours(it, softOnPrimary(dark), it) }
        else if (tokens.hum && (chosen == AccentChoice.SIGNAL || (chosen == AccentChoice.COVER && coverAccent == null))) (if (dark) HumPink else HumPinkDay).let { AccentColours(it, onAccentFor(it), visibleOnDock(it)) }
        else accentColours(chosen, dark, coverAccent.takeIf { tokens.room })
    }
    // Respect both the app's reduced-motion setting and the system animator duration scale.
    val instant = LocalReducedMotion.current || !android.animation.ValueAnimator.areAnimatorsEnabled()
    val blend = tween<Color>(if (instant) 0 else AccentMs, easing = Standard)
    val fill by animateColorAsState(target.accent, blend, label = "accent")
    val onFill by animateColorAsState(target.onAccent, blend, label = "on accent")
    val onDock by animateColorAsState(target.onDock, blend, label = "dock accent")
    // Material components that use the container roles (selected chips) follow the accent too.
    val scheme = remember(tokens, target) {
        if (tokens.soft) softScheme(tokens)
        else if (tokens.hum) humScheme(tokens, target)
        else (if (tokens.room) roomScheme(tokens) else if (dark) Dark else Light).copy(primaryContainer = target.accent, onPrimaryContainer = target.onAccent,
            secondaryContainer = target.accent, onSecondaryContainer = target.onAccent)
    }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark && !darkStatusBar
                isAppearanceLightNavigationBars = !dark && !darkNavigationBar
            }
        }
    }
    val base = Typography()
    val press = remember(scheme.onSurface, instant) { RoundedPress(scheme.onSurface, instant) }
    val reading = if (tokens.soft || tokens.hum) SoftFont else ArchivoFont
    CompositionLocalProvider(LocalAccent provides AccentColours(fill, onFill, onDock), LocalLook provides tokens) { MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = reading),
            bodyMedium = base.bodyMedium.copy(fontFamily = reading),
            bodySmall = base.bodySmall.copy(fontFamily = reading),
            labelLarge = base.labelLarge.copy(fontFamily = reading),
            labelMedium = base.labelMedium.copy(fontFamily = reading),
            labelSmall = base.labelSmall.copy(fontFamily = reading),
            titleLarge = base.titleLarge.copy(fontFamily = reading),
            titleMedium = base.titleMedium.copy(fontFamily = reading),
            titleSmall = base.titleSmall.copy(fontFamily = reading),
            headlineLarge = if (tokens.soft) base.headlineLarge.copy(fontFamily = SoftFont) else if (tokens.hum) base.headlineLarge.copy(fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.02).em) else base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
            headlineMedium = if (tokens.soft) base.headlineMedium.copy(fontFamily = SoftFont) else if (tokens.hum) base.headlineMedium.copy(fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.02).em) else base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
            headlineSmall = if (tokens.soft) base.headlineSmall.copy(fontFamily = SoftFont) else if (tokens.hum) base.headlineSmall.copy(fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.02).em) else base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        ),
        // Classic is square: menus, dialogs, sheets and fields lose their corners too. The
        // Listening room is rounded throughout; Soft keeps Material's own rounded shapes.
        shapes = if (tokens.soft) Shapes() else if (tokens.classic) Shapes(
            extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp), extraLarge = RoundedCornerShape(0.dp),
        ) else Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
        ),
        content = { CompositionLocalProvider(LocalIndication provides press, content = content) },
    ) }
}

/**
 * [MusicTheme] with the saved Look, Theme and Accent, for screens outside [MusicApp] (the sign-in
 * pages). They have no song playing, so "From the cover" shows its Signal fallback there.
 */
@Composable
fun SavedMusicTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { labs.rtensor.music.data.LibraryStore(context.applicationContext) }
    MusicTheme(store.theme, accent = store.accent, look = store.look, content = content)
}

internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
private fun LazyItemScope.motionModifier(): Modifier = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()

private val rootPages = listOf("home", "explore", "library")

@Composable
fun MusicApp(
    state: MusicState,
    onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {},
    onPlay: (Track, List<Track>) -> Unit = { _, _ -> }, onToggle: () -> Unit = {},
    onPrevious: () -> Unit = {}, onNext: () -> Unit = {}, onSkipTo: (Int) -> Unit = {},
    onSeek: (Long) -> Unit = {}, onSave: (Track) -> Unit = {},
    onTheme: (String) -> Unit = {},
    onAccent: (AccentChoice) -> Unit = {},
    onOpenLocal: () -> Unit = {},
    onChooseMasters: () -> Unit = {},
    onClearMasters: () -> Unit = {},
    onConnectSource: (MusicProvider, SourceProtocol, String, String) -> Unit = { _, _, _, _ -> },
    onRemoveSource: (String) -> Unit = {},
    onConnectTidal: () -> Unit = {}, onDisconnectTidal: () -> Unit = {}, onCancelTidal: () -> Unit = {},
    onConnectApple: () -> Unit = {}, onNativeApple: () -> Unit = {}, onDisconnectApple: () -> Unit = {}, onCancelApple: () -> Unit = {},
    onClearHistory: () -> Unit = {}, onRetry: () -> Unit = {}, onDismissError: () -> Unit = {},
    sharedQuery: String? = null, onShareConsumed: () -> Unit = {},
    initialPage: String = "home",
    onRefreshFeed: () -> Unit = {}, onMoreFeed: () -> Unit = {}, onBrowse: (FeedEntry) -> Unit = {}, onRetryCollection: () -> Unit = {},
    onConnect: () -> Unit = {}, onWebSignIn: () -> Unit = {}, onDisconnect: () -> Unit = {}, onCancelConnect: () -> Unit = {}, onRefreshExplore: () -> Unit = {},
    onGoogleSignIn: () -> Unit = {}, onGoogleSignOut: () -> Unit = {}, onCancelGoogle: () -> Unit = {},
    onCheckUpdates: () -> Unit = {}, onDownloadUpdate: () -> Unit = {}, onInstallUpdate: () -> Unit = {},
    onAutoplay: (Boolean) -> Unit = {}, onTrackHistory: (Boolean) -> Unit = {}, onRemovePlay: (PlayEvent) -> Unit = {}, onRefreshForYou: () -> Unit = {},
    onRetryRadio: () -> Unit = {}, onLyrics: suspend (Track) -> labs.rtensor.music.data.Lyrics? = { null },
    onTranslateLyrics: suspend (String, String) -> labs.rtensor.music.data.TranslatedLyrics? = { _, _ -> null },
    onSyncedLyrics: suspend (Track) -> List<labs.rtensor.music.data.LyricLine> = { emptyList() },
    onProcessing: (Boolean) -> Unit = {},
    playbackPosition: StateFlow<Long>? = null,
    audioLevels: StateFlow<labs.rtensor.dsp.AudioLevelFrame?>? = null,
    onMetering: (Boolean) -> Unit = {},
    onDeveloperOptions: (Boolean) -> Unit = {},
    onRecommendationEngine: (labs.rtensor.music.recommendations.RecommendationEngine) -> Unit = {},
    onConnectSpotify: () -> Unit = {}, onDisconnectSpotify: () -> Unit = {},
    onMoreCollection: () -> Unit = {},
    songActions: SongActionCallbacks = SongActionCallbacks(),
    onSuggestion: (String) -> Unit = {},
    onHomeFilter: (String?) -> Unit = {},
    onBrowseNavigation: (FeedEntry, Boolean) -> Unit = { entry, _ -> onBrowse(entry) },
    onCollectionBack: () -> Boolean = { false },
) {
    val startPage = if (initialPage == "saved") "library" else initialPage
    var page by rememberSaveable { mutableStateOf(startPage) }
    var expandedSectionTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedFromExplore by rememberSaveable { mutableStateOf(false) }
    var expandedFromCollection by rememberSaveable { mutableStateOf(false) }
    var expandedExploreTab by rememberSaveable { mutableStateOf("discover") }
    var exploreTab by rememberSaveable(state.recommendationEngine) { mutableStateOf("for-you") }
    var back by rememberSaveable { mutableStateOf("home") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var root by rememberSaveable { mutableStateOf(startPage.takeIf { it in rootPages } ?: "home") }
    val browserStates = rememberSaveableStateHolder()
    val collectionStates = rememberSaveableStateHolder()
    // Opening the player must not repartition the shelves when the selected song enters history.
    val homeRecommendations = remember(state.feed, state.forYou, state.trackHistory) { homeRecommendationGroups(state) }
    val exploreContent = remember(state.explore, state.feed, state.feedContinuations) { exploreShelves(state.explore, state.feed + state.feedContinuations) }
    val homeScroll = rememberLazyListState()
    val exploreScroll = rememberLazyListState()
    val explorePersonalScroll = rememberLazyListState()
    val exploreBrowseScroll = rememberLazyListState()
    val providerScroll = rememberLazyListState()
    val sectionScroll = key(expandedSectionTitle, expandedFromExplore, expandedFromCollection, expandedExploreTab) { rememberLazyListState() }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = state.reducedMotion
    fun focusSearch() { searchFocus.requestFocus(); keyboard?.show() }
    fun navigate(next: String) {
        // Changing tabs closes an open search. Only clear when there is one: a blank query also
        // clears the shared error, which would silently drop a playback error and its Retry.
        if (next in rootPages) { root = next; searchOpen = false; if (state.query.isNotEmpty()) onQuery(""); keyboard?.hide() }
        if (page != "now" && next == "now") back = page
        page = next
    }
    fun play(track: Track, list: List<Track>) { onPlay(track, list); navigate("now") }
    fun openEntry(entry: FeedEntry) {
        when {
            entry.videoId.isNotBlank() || entry.kind == "spotify_track" -> { val t = entry.asTrack(); play(t, listOf(t)) }
            entry.kind in listOf("artist", "library_artist") -> { onQuery(entry.title); onSearch(); page = root }
            else -> { onBrowseNavigation(entry, page == "collection"); navigate("collection") }
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) { searchFocus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(sharedQuery) {
        sharedQuery?.takeIf { it.isNotBlank() }?.let { onQuery(it); page = root; onSearch(); onShareConsumed() }
    }
    LaunchedEffect(page) { if (page == "explore" && state.explore.isEmpty() && !state.exploreBusy) onRefreshExplore() }
    // One back for the header arrow and the system gesture, so both land on the same page.
    // Leaving a tab for Home goes through navigate(), which also makes Home the root again.
    fun goBack() {
        when (page) {
            "now" -> page = back
            "collection" -> if (!onCollectionBack()) page = root
            "feed-section" -> page = if (expandedFromCollection) "collection" else root
            "settings", "provider-feed" -> page = root
            else -> navigate("home")
        }
    }
    BackHandler(page != "home" || state.query.isNotEmpty() || searchOpen) {
        if (searchOpen) { searchOpen = false; if (state.query.isNotEmpty()) onQuery(""); keyboard?.hide() }
        else if (page in rootPages && state.query.isNotEmpty()) onQuery("") else goBack()
    }
    // Keep the last track while the mini player animates away.
    var lastTrack by remember { mutableStateOf(state.current) }
    if (state.current != null) lastTrack = state.current
    // Only "From the cover" reads the artwork; other choices never load it for colour. Classic
    // always uses the Signal orange, so it never reads the artwork either.
    val cover = if (state.accent == AccentChoice.COVER && (state.look == LookChoice.ROOM || state.look == LookChoice.HUM)) rememberCoverColours(state.current?.artwork) else null
    // The channel monitor, the room and the level meter can all want live levels; metering runs
    // while any of them does, so one leaving the screen never switches it off for another.
    val metering = remember { MeteringClaims() }
    metering.sink = onMetering
    // The player takes a deep tone from the cover; every other page stays paper/ink.
    val tones = remember(cover) { cover?.let { playerTones(it.deep) } }?.takeIf { page == "now" }
    CompositionLocalProvider(LocalReducedMotion provides reduced, LocalPlayerTones provides tones,
        LocalHomeFilterSelect provides onHomeFilter, LocalPlaySongs provides ::play, LocalLyricsTranslate provides onTranslateLyrics, LocalSyncedLyrics provides onSyncedLyrics,
        LocalFeedSectionOpen provides { section ->
            if (page != "feed-section") {
                expandedFromExplore = page == "explore" && exploreTab != "for-you"
                expandedFromCollection = page == "collection"
                if (expandedFromExplore) expandedExploreTab = exploreTab
            }
            expandedSectionTitle = section?.title
            page = if (section == null) "provider-feed" else "feed-section"
        }) {
        // Soft's dock is its light surface; the other looks' dock is always dark.
        MusicTheme(state.theme, darkNavigationBar = (state.look != LookChoice.SOFT && page in rootPages + listOf("collection", "provider-feed", "feed-section")) || tones != null, accent = state.accent,
            coverAccent = cover?.accent, darkStatusBar = tones != null, look = state.look) {
    SongActionsHost(state, songActions, onSave, onPlay = { play(it, listOf(it)) },
        onArtist = { onQuery(it); onSearch(); page = root }) {
            Scaffold(
                topBar = {
                    OnPlayerTones(Modifier.background(tones?.top ?: Color.Transparent)) {
                        AppHeader(page, searchOpen, onHome = { navigate("home") }, onCloseSearch = { searchOpen = false; if (state.query.isNotEmpty()) onQuery(""); keyboard?.hide() }, onSettings = { navigate("settings") }, onBack = ::goBack) { SearchBar(state, onQuery, onSearch, searchFocus, Modifier.weight(1f)) }
                    }
                },
                bottomBar = {
                    if (page in rootPages + listOf("collection", "provider-feed", "feed-section")) {
                        // The dock is always dark, in light and dark themes alike.
                        val hum = LocalLook.current.hum
                        Column(Modifier.background(if (hum) MaterialTheme.colorScheme.background else LocalLook.current.stage).navigationBarsPadding()) {
                            if (LocalLook.current.soft) HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outline)
                            AnimatedVisibility(
                                visible = state.current != null,
                                enter = if (reduced) EnterTransition.None else slideInVertically(DockSpring) { it } + fadeIn(tween(EnterMs)),
                                exit = if (reduced) ExitTransition.None else slideOutVertically(DockSpring) { it } + fadeOut(tween(QuickMs)),
                            ) {
                                lastTrack?.let { MiniPlayer(state.copy(current = it), { navigate("now") }, onToggle, onNext, onPrevious, playbackPosition) }
                            }
                            when (LocalLook.current.choice) {
                                LookChoice.CLASSIC -> ClassicNavigation(if (page !in rootPages) root else page, reduced, ::navigate)
                                LookChoice.SOFT -> SoftNavigation(if (page !in rootPages) root else page, reduced, ::navigate)
                                LookChoice.ROOM -> AppNavigation(if (page !in rootPages) root else page, reduced, ::navigate)
                                // While searching, the pill rises to the top as the search field; closing brings it back.
                                LookChoice.HUM -> AnimatedVisibility(!searchOpen,
                                    enter = if (reduced) EnterTransition.None else slideInVertically(tween(300, easing = EmphasizedDecelerate)) { it } + fadeIn(tween(EnterMs)),
                                    exit = if (reduced) ExitTransition.None else slideOutVertically(tween(220, easing = EmphasizedAccelerate)) { it } + fadeOut(tween(QuickMs))) {
                                    HumNavigation(if (page !in rootPages) root else page, reduced, searchOpen, ::navigate) { if (page !in rootPages) navigate(root); searchOpen = true }
                                }
                            }
                        }
                    } else Spacer(Modifier.navigationBarsPadding())
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = tones?.bottom ?: MaterialTheme.colorScheme.background,
            ) { inset ->
                Column(Modifier.fillMaxSize().padding(inset)) {
                    var lastError by remember { mutableStateOf(state.error) }
                    if (state.error != null) lastError = state.error
                    AnimatedVisibility(
                        visible = state.error != null,
                        enter = if (reduced) EnterTransition.None else expandVertically(tween(EnterMs, easing = EmphasizedDecelerate)) + fadeIn(tween(EnterMs, 60)),
                        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(ExitMs, easing = EmphasizedAccelerate)) + fadeOut(tween(120)),
                    ) {
                        // The error is shared, so Retry repeats what actually failed. The ViewModel
                        // names the origin; without one, a search or collection that came back empty
                        // is retried, otherwise the recording that is playing.
                        val retry = when {
                            state.errorOrigin == labs.rtensor.music.ErrorOrigin.SEARCH -> onSearch
                            state.errorOrigin == labs.rtensor.music.ErrorOrigin.COLLECTION -> onRetryCollection
                            state.errorOrigin == labs.rtensor.music.ErrorOrigin.PLAYBACK -> onRetry
                            page in rootPages && state.searched && state.query.isNotBlank() && (state.results.isEmpty() || state.current == null) -> onSearch
                            page == "collection" && (state.collection.isEmpty() || state.current == null) -> onRetryCollection
                            else -> onRetry
                        }
                        ErrorBanner(lastError.orEmpty(), onDismissError, retry, state.errorDiagnostic, state.current?.id.orEmpty())
                    }
                    val showResults = page in rootPages && (state.query.isNotBlank() || (state.look == LookChoice.HUM && searchOpen))
                    AnimatedContent(
                        targetState = if (showResults) "search" else page,
                        transitionSpec = {
                            if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            else screenTransition(initialState, targetState)
                        },
                        label = "screen", modifier = Modifier.weight(1f),
                    ) { target ->
                        RetainedBrowserScreen(browserStates, target) {
                        when (target) {
                            "search" -> if (!state.searched) SearchSuggestionList(state, onSuggestion, { play(it, listOf(it)) }) else SearchResults(state, ::play, onSave)
                            "explore" -> ExploreScreen(state, exploreContent, exploreTab, { exploreTab = it }, ::openEntry,
                                onRefreshFeed, onRefreshExplore, exploreScroll, exploreBrowseScroll,
                                active = page == "explore" && !showResults) {
                                PersonalScreen(state, ::focusSearch, ::play, onToggle, ::openEntry, { onRefreshFeed(); onRefreshForYou() }, onMoreFeed, onConnect,
                                    onOpenPlayer = { navigate("now") }, audioLevels = audioLevels, metering = metering, listState = explorePersonalScroll,
                                    active = page == "explore" && exploreTab == "for-you" && !showResults, recommendations = homeRecommendations)
                            }
                            "library" -> LibraryScreen(state, ::play, onSave, ::focusSearch, onRemovePlay)
                            "now" -> NowScreen(state, onToggle, onPrevious, onNext, onSeek, onSave, onSkipTo, onProcessing, playbackPosition, onRetryRadio, onLyrics, audioLevels, metering)
                            "provider-feed", "feed-section" -> {
                                val exploreSection = target == "feed-section" && expandedFromExplore
                                val sections = when { expandedFromCollection && target == "feed-section" -> state.collectionSections
                                    exploreSection -> if (expandedExploreTab == "browse") exploreContent.browse else exploreContent.discover
                                    else -> state.feed + state.feedContinuations }
                                ProviderHome(state.copy(feed = if (target == "provider-feed") sections else sections.filter { it.title == expandedSectionTitle },
                                    feedBusy = when { expandedFromCollection && target == "feed-section" -> state.collectionBusy; exploreSection -> state.exploreBusy; else -> state.feedBusy },
                                    feedError = when { expandedFromCollection && target == "feed-section" -> state.collectionBrowseError; exploreSection -> state.exploreError; else -> state.feedError },
                                    feedHasMore = target == "provider-feed" && state.feedHasMore,
                                    feedEnded = target == "provider-feed" && state.feedEnded,
                                    homeFilters = if (target == "provider-feed") state.homeFilters else emptyList(), homeFilter = if (target == "provider-feed") state.homeFilter else null),
                                    ::openEntry, when { expandedFromCollection && target == "feed-section" -> onRetryCollection; exploreSection -> onRefreshExplore; else -> onRefreshFeed }, onMoreFeed,
                                    if (target == "provider-feed") "Your ${state.recommendationEngine.label} feed" else expandedSectionTitle.orEmpty(),
                                    listState = if (target == "provider-feed") providerScroll else sectionScroll, active = page == target, expanded = target == "feed-section")
                            }
                            "collection" -> collectionStates.SaveableStateProvider(state.collectionEntry?.id ?: "pending") {
                                if (state.collectionEntry?.kind == "browse") ProviderHome(state.copy(feed = state.collectionSections,
                                    feedBusy = state.collectionBusy, feedAppending = state.collectionBusy && state.collectionSections.isNotEmpty(),
                                    feedHasMore = state.collectionHasMore, feedError = state.collectionBrowseError, feedEnded = false,
                                    homeFilters = emptyList(), homeFilter = null), ::openEntry, onRetryCollection, onMoreCollection,
                                    state.collectionEntry.title, active = page == "collection")
                                else CollectionScreen(state, ::play, onSave, onMoreCollection)
                            }
                            "settings" -> SettingsScreen(state, onTheme, onAccent, onOpenLocal, onChooseMasters, onClearMasters, onClearHistory,
                                onConnect, onWebSignIn, onDisconnect, onCancelConnect, onGoogleSignIn, onGoogleSignOut, onCancelGoogle, onCheckUpdates, onDownloadUpdate, onInstallUpdate,
                                onAutoplay, onTrackHistory, onConnectSource, onRemoveSource, onConnectTidal, onDisconnectTidal, onCancelTidal,
                                onConnectApple, onNativeApple, onDisconnectApple, onCancelApple, onDeveloperOptions,
                                onRecommendationEngine, onConnectSpotify, onDisconnectSpotify)
                            else -> ProviderHome(state.copy(feed = state.feed + state.feedContinuations), ::openEntry,
                                onRefreshFeed, onMoreFeed, listState = homeScroll, active = page == "home" && !showResults)
                        }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun AppHeader(page: String, searchOpen: Boolean, onCloseSearch: () -> Unit, onHome: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit, search: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (page in listOf("now", "settings", "collection", "provider-feed", "feed-section")) {
            val look = LocalLook.current
            // Soft: a plain back arrow and a small spaced caption, as in 0.3.31.
            if (look.soft) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            else OutlinedIconButton(onClick = onBack, shape = ButtonShape, border = BorderStroke(if (look.classic) 2.dp else 1.dp,
                if (look.classic) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(20.dp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val caption = when (page) { "now" -> "Now playing"; "collection" -> "Collection"; "provider-feed" -> "Your feed"; "feed-section" -> "Browse music"; else -> "Settings" }
                if (look.classic || look.soft) Caption(caption.uppercase(Locale.ROOT))
                // In the Listening room the caption is the page's name (Settings has no second title).
                else Text(caption, Modifier.semantics { heading() }, fontFamily = font, fontSize = 14.sp,
                    lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(48.dp))
        } else {
            // A small monogram stands in for the old wordmark so search can share the row. Like
            // the Home tab, it reports itself as selected while Home is already showing.
            val humSearch = LocalLook.current.hum
            val calm = LocalReducedMotion.current
            @Composable fun Wordmark() = Column(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RectangleShape).clickable(onClick = onHome).padding(horizontal = 6.dp, vertical = 8.dp)
                .semantics { contentDescription = "Music, go home"; selected = page == "home" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (LocalLook.current.hum) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("hum", fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 22.sp, letterSpacing = (-.03).em)
                        Box(Modifier.padding(start = 1.dp, bottom = 3.dp).size(6.dp).background(LocalAccent.current.accent, CircleShape))
                    }
                } else if (LocalLook.current.soft) {
                    // Soft's monogram: DM Sans over a thin 12dp raspberry bar.
                    Text("M", fontFamily = SoftFont, fontSize = 18.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(12.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
                } else {
                    Text("M", style = DisplayStyle, fontSize = 24.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(14.dp).height(4.dp).clip(if (LocalLook.current.classic) RectangleShape else RoundedCornerShape(2.dp)).background(LocalAccent.current.accent))
                }
            }
            if (humSearch) {
                // The dock's search lifts to the top and becomes this field; closing sends it back down.
                AnimatedVisibility(!searchOpen, enter = if (calm) EnterTransition.None else fadeIn(tween(EnterMs, 80)) + expandHorizontally(tween(EnterMs)),
                    exit = if (calm) ExitTransition.None else fadeOut(tween(QuickMs)) + shrinkHorizontally(tween(ExitMs))) { Wordmark() }
                Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(searchOpen, Modifier.fillMaxWidth(),
                        enter = if (calm) EnterTransition.None else slideInVertically(tween(320, easing = EmphasizedDecelerate)) { it * 12 } + fadeIn(tween(QuickMs)),
                        exit = if (calm) ExitTransition.None else slideOutVertically(tween(260, easing = EmphasizedAccelerate)) { it * 12 } + fadeOut(tween(ExitMs))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(6.dp)); search()
                            IconButton(onClick = onCloseSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close search", Modifier.size(22.dp)) }
                        }
                    }
                }
                AnimatedVisibility(!searchOpen, enter = if (calm) EnterTransition.None else fadeIn(tween(EnterMs, 80)) + expandHorizontally(tween(EnterMs)),
                    exit = if (calm) ExitTransition.None else fadeOut(tween(QuickMs)) + shrinkHorizontally(tween(ExitMs))) {
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Settings, "Open settings", Modifier.size(22.dp)) }
                }
            } else {
                Wordmark()
                Spacer(Modifier.width(6.dp)); search()
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Settings, "Open settings", Modifier.size(22.dp)) }
            }
        }
    }
}

/**
 * How screens hand over. Tabs slide sideways in the direction of the tab bar, the player
 * lifts in briefly and settles back, and detail pages lift in from below.
 */
private fun AnimatedContentTransitionScope<String>.screenTransition(from: String, to: String): ContentTransform {
    val tabs = rootPages
    return when {
        // The player grows out of the mini player at the bottom: a tall lift with a slight scale-up.
        to == "now" -> (slideInVertically(tween(300, easing = EmphasizedDecelerate)) { it / 2 } + scaleIn(tween(300, easing = EmphasizedDecelerate), initialScale = .92f) + fadeIn(tween(220, easing = EmphasizedDecelerate)) togetherWith
            fadeOut(tween(160))).using(null)
        from == "now" -> (fadeIn(tween(ExitMs, easing = EmphasizedDecelerate)) togetherWith
            slideOutVertically(tween(240, easing = EmphasizedAccelerate)) { it / 2 } + scaleOut(tween(240), targetScale = .94f) + fadeOut(tween(ExitMs))).apply { targetContentZIndex = -1f }.using(null)
        from in tabs && to in tabs -> {
            val dir = if (tabs.indexOf(to) > tabs.indexOf(from)) 1 else -1
            (slideInHorizontally(tween(EnterMs, easing = EmphasizedDecelerate)) { w -> dir * w / 12 } + fadeIn(tween(200, 40)) togetherWith
                slideOutHorizontally(tween(QuickMs, easing = EmphasizedAccelerate)) { w -> -dir * w / 16 } + fadeOut(tween(100))).using(null)
        }
        else -> {
            val forward = to in listOf("settings", "collection", "search")
            // One curve and one duration for every part of the entrance, so fade, lift and
            // scale finish together instead of trailing each other.
            (fadeIn(tween(EnterMs, delayMillis = 40, easing = EmphasizedDecelerate)) +
                slideInVertically(PageSpring) { h -> if (forward) h / 16 else -h / 32 } +
                scaleIn(tween(EnterMs, easing = EmphasizedDecelerate), initialScale = if (forward) .985f else 1.01f) togetherWith
                fadeOut(tween(110, easing = FastOutLinearInEasing))).using(null)
        }
    }
}

private data class NavTab(val id: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
private val navTabs = listOf(
    NavTab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    NavTab("explore", "Explore", Glyph.Compass, Glyph.CompassFilled),
    NavTab("library", "Library", Glyph.Library, Glyph.LibraryFilled),
)

/**
 * Hum's floating pill: icons in a rounded dark capsule, with the chosen tab's pink capsule sliding
 * between them on a spring. Search is the fourth stop; it opens the field rather than a page.
 */
@Composable
private fun HumNavigation(page: String, reduced: Boolean, searching: Boolean, onSelect: (String) -> Unit, onSearch: () -> Unit) {
    val look = LocalLook.current
    val items = listOf(navTabs[0], navTabs[1], NavTab("search", "Search", Icons.Outlined.Search, Icons.Filled.Search), navTabs[2])
    val selectedIndex = if (searching) 2 else items.indexOfFirst { it.id == page }.coerceAtLeast(0)
    val position by animateFloatAsState(selectedIndex.toFloat(), if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 520f), label = "pill")
    val accent = LocalAccent.current.accent
    val onAccent = LocalAccent.current.onAccent
    Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().height(60.dp).shadow(14.dp, RoundedCornerShape(30.dp), clip = false)
                .clip(RoundedCornerShape(30.dp)).background(look.stage).padding(5.dp)
                .drawBehind {
                    val slot = size.width / items.size
                    drawRoundRect(accent, Offset(slot * position, 0f), Size(slot, size.height), CornerRadius(size.height / 2))
                },
        ) {
            items.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(if (selected) onAccent else look.stageMuted, tween(if (reduced) 0 else QuickMs), label = "pill tint")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (tab.id == "search") onSearch() else onSelect(tab.id) }
                        .semantics { contentDescription = tab.label; role = Role.Tab; this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(tab.label, fontFamily = font, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

/**
 * A flat tab bar on the dark dock with sentence-case labels. The chosen tab shows one small
 * accent light above its label, like a speaker light; it fades between tabs and the labels
 * never move, so switching tabs never re-measures the bar.
 */
@Composable
private fun AppNavigation(page: String, reduced: Boolean, onSelect: (String) -> Unit) {
    val selectedIndex = navTabs.indexOfFirst { it.id == page }.coerceAtLeast(0)
    val mark = LocalAccent.current.onDock
    val look = LocalLook.current
    Row(Modifier.fillMaxWidth().height(56.dp).background(look.stage)) {
        navTabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color by animateColorAsState(if (selected) look.stageText else look.stageMuted, tween(if (reduced) 0 else QuickMs), label = "tab colour")
            val lit = animateFloatAsState(if (selected) 1f else 0f, if (reduced) snap() else tween(QuickMs, easing = Standard), label = "tab lights")
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.id) }
                    .semantics { contentDescription = tab.label; role = Role.Tab; this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                // One light over the chosen tab (three dots read as a "more" menu).
                Canvas(Modifier.size(width = 20.dp, height = 5.dp).graphicsLayer { alpha = lit.value }.testTag("tab-light")) {
                    drawCircle(mark, size.height / 2, center)
                }
                Spacer(Modifier.height(6.dp))
                // The tab's name is its content description; the label adds nothing for TalkBack.
                Text(tab.label, fontFamily = font, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = color, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * Classic's tab bar, as in 0.3.32: a flat mono tab bar on the dark dock. A 3dp accent mark rides
 * the top edge of the chosen tab and stretches across to the next one, its leading edge first and
 * the trailing edge catching up. The mark is painted in the draw phase, so switching tabs never
 * re-measures the bar.
 */
@Composable
private fun ClassicNavigation(page: String, reduced: Boolean, onSelect: (String) -> Unit) {
    val selectedIndex = navTabs.indexOfFirst { it.id == page }.coerceAtLeast(0)
    val lead = remember { Animatable(selectedIndex.toFloat()) }
    val trail = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex, reduced) {
        val target = selectedIndex.toFloat()
        if (reduced) { lead.snapTo(target); trail.snapTo(target); return@LaunchedEffect }
        // Springs keep their velocity when a second tap retargets them mid-flight.
        launch { lead.animateTo(target, spring(dampingRatio = 1f, stiffness = 700f)) }
        delay(40)
        trail.animateTo(target, spring(dampingRatio = 1f, stiffness = 420f))
    }
    val mark = LocalAccent.current.onDock
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Ink).drawBehind {
            val slot = size.width / navTabs.size
            val left = slot * minOf(lead.value, trail.value)
            val right = slot * (maxOf(lead.value, trail.value) + 1f)
            drawRect(mark, Offset(left, 0f), Size(right - left, 3.dp.toPx()))
        },
    ) {
        navTabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color by animateColorAsState(if (selected) Paper else PaperMuted, tween(if (reduced) 0 else QuickMs), label = "tab colour")
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.id) }
                    .semantics { contentDescription = tab.label; role = Role.Tab; this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.label.uppercase(), style = MonoSmall, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = color, maxLines = 1, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
            }
        }
    }
}

/**
 * Soft's tab bar, as in 0.3.31: icons and labels on the light dock, with a soft pill behind the
 * chosen icon that stretches across to the next tab, leading edge first. The tabs keep today's
 * accessibility: a Tab role, the selected state, and the name read once.
 */
@Composable
private fun SoftNavigation(page: String, reduced: Boolean, onSelect: (String) -> Unit) {
    val selectedIndex = navTabs.indexOfFirst { it.id == page }.coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme
    val lead = remember { Animatable(selectedIndex.toFloat()) }
    val trail = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex, reduced) {
        val target = selectedIndex.toFloat()
        if (reduced) { lead.snapTo(target); trail.snapTo(target); return@LaunchedEffect }
        launch { lead.animateTo(target, spring(dampingRatio = 1f, stiffness = 700f)) }
        delay(40)
        trail.animateTo(target, spring(dampingRatio = 1f, stiffness = 420f))
    }
    val pill = scheme.secondaryContainer
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp).drawBehind {
            val slot = size.width / navTabs.size
            val w = 56.dp.toPx(); val h = 30.dp.toPx(); val top = 7.dp.toPx()
            val left = slot * (minOf(lead.value, trail.value) + .5f) - w / 2
            val right = slot * (maxOf(lead.value, trail.value) + .5f) + w / 2
            drawRoundRect(pill, Offset(left, top), Size(right - left, h), CornerRadius(h / 2))
        },
    ) {
        navTabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, tween(if (reduced) 0 else QuickMs), label = "tab colour")
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.id) }
                    .semantics { contentDescription = tab.label; role = Role.Tab; this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 7.dp).size(56.dp, 30.dp), contentAlignment = Alignment.Center) {
                    Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(22.dp))
                }
                Text(tab.label, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = .3.sp, color = color, maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
            }
        }
    }
}

/**
 * Who wants live audio levels right now: the channel monitor, the room on Home and the player's
 * level meter. Metering runs while any of them asks, so one leaving never turns it off for another.
 */
internal class MeteringClaims {
    var sink: (Boolean) -> Unit = {}
    private val wanting = mutableSetOf<String>()
    fun claim(owner: String, wanted: Boolean) {
        if (wanted) wanting += owner else wanting -= owner
        sink(wanting.isNotEmpty())
    }
}

@Composable
private fun PersonalScreen(state: MusicState, onSearch: () -> Unit, onPlay: (Track, List<Track>) -> Unit, onToggle: () -> Unit,
    onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onConnect: () -> Unit,
    onOpenPlayer: () -> Unit = {}, audioLevels: StateFlow<labs.rtensor.dsp.AudioLevelFrame?>? = null, metering: MeteringClaims? = null, listState: LazyListState = rememberLazyListState(), active: Boolean = true,
    recommendations: HomeRecommendationGroups = homeRecommendationGroups(state)) {
    val recent = state.history.firstOrNull()
    val recentIsCurrent = recent != null && recent.libraryKey() == state.current?.libraryKey()
    val recentLoading = recentIsCurrent && (state.resolving || state.buffering)
    val recentPlaying = recentIsCurrent && state.playing && !recentLoading
    val hasFeed = state.forYou.isNotEmpty() || feedSongs(state.feed).isNotEmpty()
    // Last played stays at the top of Home, also after the provider feed has loaded.
    val showHero = recent != null || (state.forYou.isEmpty() && state.feed.none { section -> section.items.any { it.videoId.isNotBlank() || it.kind == "spotify_track" } })
    val now = remember(state.playEvents, state.history) { System.currentTimeMillis() }
    // Everything below is read from what you actually played on this phone; nothing is invented.
    val lastPlayedAt = remember(state.playEvents) {
        state.playEvents.groupBy { it.track.libraryKey() }.mapValues { (_, events) -> events.maxOf { it.startedAt } }
    }
    val playsToday = remember(state.playEvents, now) { state.playEvents.count { it.startedAt >= startOfToday(now) } }
    val topArtists = remember(state.playEvents, now) {
        val names = state.playEvents.flatMap { ListeningHistory.artists(it.track.artist) }.associateBy { it.lowercase() }
        Recommender.artistAffinity(state.playEvents, now).filterValues { it > 0 }.entries
            .sortedByDescending { it.value }.take(2).mapNotNull { names[it.key] }
    }
    val unheard = recommendations.newToYou
    // Only the songs Home actually shows (collections stay in Explore), and only to disk.
    PrefetchArtwork((recommendations.fromListening + recommendations.newToYou + state.history.take(12)).map(Track::artwork), 156.dp, diskOnly = true)
    val look = LocalLook.current
    // Rows of the song grid that already show stay as they are when "More from your feed" adds songs.
    val batches = remember(state.feed) { GridBatches() }
    PullToRefresh(state.feedBusy && !state.feedAppending, onRefresh) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
        // The Room look opens on your room: the speakers you are hearing, lit by what plays. Hum starts on the feed.
        if (look.choice == LookChoice.ROOM) item(key = "room") {
            ListeningRoom(state, audioLevels, metering, onOpenPlayer, Modifier.padding(top = 4.dp, bottom = 6.dp))
        }
        item(key = "title") {
            Column(Modifier.padding(bottom = 4.dp)) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(state.feedBusy && !state.feedAppending, state.reducedMotion)
            }
        }
        val google = state.googleProfile
        if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE && google != null && !state.account.connected && !state.account.connecting) item(key = "sync") {
            SyncBanner(google.email, onConnect, motionModifier().padding(top = 16.dp))
        }
        if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE && state.account.connected && !state.feedPersonalized && hasFeed) item(key = "generic-feed") {
            NoticeBanner("Your YouTube Music feed isn’t coming through",
                "YouTube answered as signed out, so this is the general feed. Reconnecting your account usually fixes it.", "Reconnect", onConnect, motionModifier().padding(top = 16.dp))
        }
        if (state.feedError != null && (state.feed.isEmpty() || !state.feedHasMore)) item(key = "feed-error") {
            Row(motionModifier().fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.feedError, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, shape = ButtonShape) { Text(look.label("Retry"), style = LabelText) }
            }
        }
        if (showHero && look.soft) item(key = "hero") {
            SoftHero(state, recent, recentIsCurrent, recentLoading, recentPlaying, onSearch, onToggle, onPlay, onOpenPlayer)
        } else if (showHero && !(look.hum && recent == null)) item(key = "hero") {
            Column(Modifier.fillMaxWidth().signalRule(bottom = true, thickness = 1.dp).padding(vertical = 20.dp)) {
                if (recent == null) {
                    Text(look.label("Start with a song"), style = LabelText)
                    Spacer(Modifier.height(12.dp))
                    Headline(look.headline("Find your\nnext repeat"), 40.sp, 36.sp, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Text("A song, an artist, a familiar feeling.", fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    SignalAction("Find music", Icons.Outlined.Search, loading = false, onClick = onSearch)
                } else {
                    val label = if (playsToday > 0) "Last played · $playsToday today" else "Last played"
                    if (look.classic) Text(look.label(label), style = LabelText, modifier = Modifier.padding(bottom = 10.dp))
                    else SectionTitle(label, Modifier.padding(bottom = 6.dp))
                    // The featured row keeps one line per field, so its control never moves as metadata changes.
                    Row(Modifier.fillMaxWidth().songClickable(recent) { if (recentIsCurrent) onOpenPlayer() else onPlay(recent, listOf(recent)) }
                        .signalRule(thickness = 1.dp, color = MaterialTheme.colorScheme.outline).heightIn(min = 76.dp).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(recent, Modifier.size(52.dp).clip(look.art), size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            ScrollingMetadata(recent.title, DisplayStyle.copy(fontSize = 20.sp, lineHeight = 22.sp))
                            ScrollingMetadata(recent.artist, TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                        Spacer(Modifier.width(12.dp))
                        SignalAction(if (recentLoading) "Loading" else if (recentPlaying) "Pause" else "Play",
                            if (recentPlaying) Glyph.Pause else Icons.Outlined.PlayArrow, loading = recentLoading,
                            enabled = !(recentIsCurrent && state.resolving)) {
                            if (recentIsCurrent) onToggle() else onPlay(recent, listOf(recent))
                        }
                    }
                    if (state.history.size > 1) {
                        Text("Recently played", style = if (look.classic) MonoSmall else TextStyle(fontFamily = font, fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        state.history.drop(1).take(4).forEach { track ->
                            val playing = track.libraryKey() == state.current?.libraryKey()
                            LastPlayedRow(track, if (playing) look.whenNow() else lastPlayedAt[track.libraryKey()]?.let { whenLabel(it, now, look.classic) }.orEmpty()) {
                                onPlay(track, listOf(track))
                            }
                        }
                    }
                }
            }
        }
        // Home shelves (ui/HomeShelves.kt): "Next up for you", "Play it again" and "Your mixes".
        homeShelves(state, recommendations.fromListening, topArtists, onPlay)
        if (unheard.isNotEmpty() && look.soft) item(key = "unheard") {
            // Soft has no section like this in 0.3.31; it is set in Soft's type as a plain list,
            // so songs added by "More from your feed" still arrive below.
            Column(motionModifier().padding(top = 20.dp).fillMaxWidth()) {
                SectionTitle(if (state.trackHistory) "New to your ears" else "Discover more")
                Text(if (state.trackHistory) "Songs outside your recorded listening history."
                    else "Individual recommendations from ${state.recommendationEngine.label}.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                unheard.forEach { track -> TrackRow(track, false, false, { onPlay(track, listOf(track)) }, null) }
            }
        } else if (unheard.isNotEmpty()) item(key = "unheard") {
            if (look.classic) {
                val accent = LocalAccent.current
                val on = accent.onAccent
                val scheme = MaterialTheme.colorScheme.copy(onSurface = on, onSurfaceVariant = on.copy(alpha = .8f),
                    onBackground = on, primary = on, outline = on.copy(alpha = .25f))
                MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                    Column(motionModifier().padding(vertical = 20.dp).fillMaxWidth().background(accent.accent).padding(16.dp)) {
                        Text(look.label(if (state.trackHistory) "New here" else "Keep exploring"), style = LabelText, color = on)
                        Text(if (state.trackHistory) "New to your ears" else "Discover more", style = DisplayStyle,
                            fontSize = 32.sp, lineHeight = 36.sp, color = on, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                        Text(if (state.trackHistory) "Songs outside your recorded listening history."
                            else "Individual recommendations from ${state.recommendationEngine.label}.",
                            fontSize = 13.sp, lineHeight = 19.sp, color = on, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                        unheard.forEach { track ->
                            TrackRow(track, false, false, { onPlay(track, listOf(track)) }, null)
                        }
                    }
                }
            } else {
                // The Listening room shows these songs as a two-column grid of cards.
                Column(motionModifier().padding(vertical = 20.dp).fillMaxWidth()) {
                    Text(if (state.trackHistory) "New here" else "Keep exploring", style = LabelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PosterText(if (state.trackHistory) "New to your ears" else "Discover more", SectionStyle.copy(fontSize = 30.sp, lineHeight = 30.sp),
                        Modifier.padding(top = 4.dp), heading = true)
                    Text(if (state.trackHistory) "Songs outside your recorded listening history."
                        else "Individual recommendations from ${state.recommendationEngine.label}.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    batches.rows(unheard.map { it.libraryKey() }).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { index ->
                                val track = unheard[index]
                                RoomCard(track, Modifier.weight(1f)) { onPlay(track, listOf(track)) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // Full provider shelves and continuous discovery live in Explore → For you.

    }
    }
}

/**
 * Splits the grid into rows of two without moving cards already shown: songs appended after a
 * list that was on screen start a new row, so "More from your feed" always adds below. Any other
 * change (a refresh, a song moving to History) lays the grid out afresh.
 */
private class GridBatches {
    private var last: List<String> = emptyList()
    private val starts = mutableListOf<Int>()
    fun rows(keys: List<String>): List<List<Int>> {
        val appended = last.isNotEmpty() && keys.size > last.size && keys.subList(0, last.size) == last
        if (appended) starts += last.size else if (keys != last) starts.clear()
        last = keys
        val bounds = (listOf(0) + starts.filter { it in 1 until keys.size } + keys.size).distinct().sorted()
        return bounds.zipWithNext().flatMap { (from, to) -> (from until to).chunked(2) }
    }
}

/**
 * Soft's Home hero, as in 0.3.31: a caption, a big DM Sans title and artist, a round raspberry
 * action, and the cover on the right; then "Recently played" as a row of cards. It keeps today's
 * behaviour: Play toggles the current song, starts only that song otherwise, and offers no tap
 * while the current song is still finding audio.
 */
@Composable
private fun SoftHero(state: MusicState, recent: Track?, recentIsCurrent: Boolean, recentLoading: Boolean, recentPlaying: Boolean,
    onSearch: () -> Unit, onToggle: () -> Unit, onPlay: (Track, List<Track>) -> Unit, onOpenPlayer: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().then(if (recent != null) Modifier.songClickable(recent) {
            if (recentIsCurrent) onOpenPlayer() else onPlay(recent, listOf(recent))
        } else Modifier), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(.46f)) {
                Caption(if (recent == null) "START WITH A SONG" else "LAST PLAYED")
                Spacer(Modifier.height(10.dp))
                val big = TextStyle(fontFamily = SoftFont, fontWeight = FontWeight.Medium, fontSize = 27.sp, lineHeight = 31.sp, letterSpacing = (-1).sp)
                if (recent == null) Text("Find your\nnext repeat.", style = big, maxLines = 3, overflow = TextOverflow.Ellipsis)
                else ScrollingMetadata(recent.title, big)
                Spacer(Modifier.height(10.dp))
                if (recent == null) Text("A song, an artist,\na familiar feeling.", fontSize = 12.sp, lineHeight = 19.sp,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else ScrollingMetadata(recent.artist, TextStyle(fontSize = 12.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.clip(RoundedCornerShape(24.dp)).pressable(enabled = !(recentIsCurrent && state.resolving)) {
                    if (recent == null) onSearch() else if (recentIsCurrent) onToggle() else onPlay(recent, listOf(recent))
                }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        if (recentLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                        else Icon(if (recent == null) Icons.Outlined.Search else if (recentPlaying) Glyph.Pause else Icons.Outlined.PlayArrow,
                            null, tint = scheme.onPrimary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (recent == null) "Find music" else if (recentLoading) "Loading" else if (recentPlaying) "Pause" else "Play",
                        fontSize = 14.sp, color = scheme.primary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Artwork(recent, Modifier.weight(.54f).aspectRatio(.9f).clip(RoundedCornerShape(14.dp)), size = 220.dp)
        }
        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("Recently played")
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(state.history.take(12), key = { i, t -> "$i:${t.libraryKey()}" }) { _, track ->
                    MediaCard(track.title, track.artist, track, 142.dp) { onPlay(track, listOf(track)) }
                }
            }
        }
    }
}

private fun startOfToday(now: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = now
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** A short mono timestamp: 5M, 2H, YDAY, 3D. */
private fun Look.whenNow() = if (classic) "NOW" else "Playing"

/** When a song was last played: Classic's mono shorthand (5M, 2H, YDAY, 3D), or plain words. */
internal fun whenLabel(at: Long, now: Long, classic: Boolean = true): String {
    val minutes = ((now - at).coerceAtLeast(0)) / 60_000
    if (!classic) return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        at >= startOfToday(now) -> "${minutes / 60} h ago"
        at >= startOfToday(now) - ListeningHistory.DAY -> "Yesterday"
        else -> "${((startOfToday(now) - at) / ListeningHistory.DAY) + 1} days ago"
    }
    return when {
        minutes < 1 -> "NOW"
        minutes < 60 -> "${minutes}M"
        at >= startOfToday(now) -> "${minutes / 60}H"
        at >= startOfToday(now) - ListeningHistory.DAY -> "YDAY"
        else -> "${((startOfToday(now) - at) / ListeningHistory.DAY) + 1}D"
    }
}

@Composable
private fun LastPlayedRow(track: Track, time: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().signalRule(thickness = 1.dp, color = MaterialTheme.colorScheme.outline).songClickable(track, onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, Modifier.size(52.dp).clip(LocalLook.current.art), size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = DisplayStyle, fontSize = 18.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(time, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A square accent action with on-accent text. Text stays a real Text node, so it reads and tests as the label. */
@Composable
private fun SignalAction(label: String, icon: ImageVector, loading: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val look = LocalLook.current
    Row(modifier.heightIn(min = 48.dp).clip(ButtonShape).pressable(enabled = enabled, onClick = onClick).background(accent.accent)
        .padding(horizontal = if (look.classic) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent.onAccent)
            else Icon(icon, null, tint = accent.onAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = DisplayStyle, fontSize = 17.sp, color = accent.onAccent, maxLines = 1)
    }
}

/** An ink-bordered button: square with a 2dp edge and a mono label in Classic, a pill otherwise. */
@Composable
private fun SignalOutlineButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val classic = LocalLook.current.classic
    OutlinedButton(onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(if (classic) 2.dp else 1.5.dp, if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        contentPadding = PaddingValues(horizontal = 16.dp)) {
        Text(label, style = LabelText, maxLines = 1)
    }
}

/** A Listening room song card: rounded panel, artwork on top, title and artist below. */
@Composable
private fun RoomCard(track: Track, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    Column(modifier.clip(look.card).background(look.panel).songClickable(track, onClick = onClick)) {
        Artwork(track, Modifier.fillMaxWidth().aspectRatio(1.4f), size = 180.dp)
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            ScrollingMetadata(track.title, TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = look.text))
            // Keep the artist line even when it is empty, so cards in a row stay the same height.
            ScrollingMetadata(track.artist, TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp, color = look.muted), Modifier.padding(top = 2.dp))
        }
    }
}

/** A square recommendation card: mono code top-left, condensed title along the bottom. */
@Composable
private fun SignalCard(track: Track, code: String, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).songClickable(track, onClick = onClick)) {
        Box(Modifier.size(128.dp).background(Ink)) {
            Artwork(track, Modifier.fillMaxSize(), size = 128.dp)
            // A scrim keeps the code and title readable over any cover.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = .55f), .35f to Color.Transparent, .55f to Color.Transparent, 1f to Ink.copy(alpha = .85f))))
            Text(code, style = MonoSmall, color = Paper, modifier = Modifier.padding(10.dp))
            ScrollingMetadata(track.title, DisplayStyle.copy(fontSize = 24.sp, lineHeight = 26.sp, color = Paper),
                Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
        }
        ScrollingMetadata(track.artist, TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
            Modifier.padding(top = 8.dp))
    }
}

/** A hairline along the top (or bottom) edge, drawn without taking layout space. */
private fun Modifier.signalRule(bottom: Boolean = false, thickness: Dp = 2.dp, color: Color = Color.Unspecified): Modifier = composed {
    // The rules are Classic's; the Listening room separates sections with space instead.
    if (!LocalLook.current.classic) return@composed Modifier
    val ink = if (color == Color.Unspecified) MaterialTheme.colorScheme.onBackground else color
    drawBehind {
        val h = thickness.toPx()
        drawRect(ink, Offset(0f, if (bottom) size.height - h else 0f), Size(size.width, h))
    }
}

/**
 * Pull down to reload, instead of a refresh button. The indicator follows the finger, gives
 * one tick when pulled far enough and spins while the page reloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefresh(busy: Boolean, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val pull = rememberPullToRefreshState()
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    // Only a pull shows the spinner: the load that runs on its own must not cover the page title.
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) manual = false }
    LaunchedEffect(pull) {
        snapshotFlow { pull.distanceFraction >= 1f }.collect { ready ->
            // One tick when you have pulled far enough, like Sanctum's door.
            if (ready && !armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            armed = ready
        }
    }
    PullToRefreshBox(
        isRefreshing = busy, onRefresh = { manual = true; onRefresh() }, state = pull, modifier = Modifier.fillMaxSize().clipToBounds(),
        indicator = {
            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = busy && manual, modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surface, color = MaterialTheme.colorScheme.primary)
        },
        content = content,
    )
}

@Composable
private fun SyncBanner(email: String, onConnect: () -> Unit, modifier: Modifier = Modifier) =
    NoticeBanner("Finish syncing YouTube Music", "$email is chosen. Connect it to YouTube Music to bring your feed here.", "Connect", onConnect, modifier)

@Composable
private fun NoticeBanner(title: String, detail: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    if (LocalLook.current.soft) {
        // Soft, as in 0.3.31: a pink container, muted detail and a filled raspberry button.
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(detail, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text(action, fontSize = 13.sp) }
            }
        }
        return
    }
    Surface(color = accent.accent, shape = LocalLook.current.card, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = accent.onAccent)
                Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = accent.onAccent, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            // The button inverts the banner text: an ink button with paper text, or white with ink.
            Button(onClick = onAction, shape = ButtonShape, contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent.onAccent, contentColor = if (accent.onAccent == Ink) Paper else Ink)) { Text(action, fontSize = 13.sp) }
        }
    }
}

/**
 * Warm the image cache for what is about to scroll into view, so cards are already filled in
 * when they appear instead of loading one by one during a fast fling.
 */
@Composable
private fun PrefetchArtwork(urls: List<String>, size: Dp, diskOnly: Boolean = false) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    LaunchedEffect(urls) {
        val loader = context.imageLoader
        urls.asSequence().filter { it.isNotBlank() }.distinct().take(80).forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(ArtworkUrl.sized(url, px)).size(px)
                // Warm the disk cache only; the image is decoded into memory when it is shown.
                .apply { if (diskOnly) memoryCachePolicy(coil.request.CachePolicy.DISABLED) }.build())
        }
    }
}

/** Reserves its height so content below never jumps when loading starts or stops. */
@Composable
private fun LoadingLine(busy: Boolean, reduced: Boolean) {
    val alpha by animateFloatAsState(if (busy) 1f else 0f, tween(if (reduced) 0 else EnterMs), label = "loading line")
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        if (busy || alpha > .01f) LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp).graphicsLayer { this.alpha = alpha },
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f), strokeCap = StrokeCap.Round,
        )
    }
}

private fun LazyListScope.feedSections(sections: List<FeedSection>, onEntry: (FeedEntry) -> Unit, gap: Dp = 0.dp) {
    providerShelves(sections, onEntry, gap)
}

@Composable
private fun MediaCard(title: String, subtitle: String, art: Track, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val soft = LocalLook.current.soft
    val shape = if (soft) RoundedCornerShape(12.dp) else LocalLook.current.art
    if (soft) {
        // Soft's card, as in 0.3.31: 12dp corners and smaller DM Sans metadata.
        Column(modifier.width(size).clip(shape).then(if (art.url.isNotBlank()) Modifier.songClickable(art, onClick = onClick) else Modifier.pressable(onClick = onClick))) {
            Artwork(art, Modifier.size(size).clip(shape), size = size)
            ScrollingMetadata(title, TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium), Modifier.padding(top = 10.dp))
            ScrollingMetadata(subtitle, TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant), Modifier.padding(top = 3.dp, bottom = 4.dp))
        }
        return
    }
    Column(modifier.width(size).clip(shape).then(if (art.url.isNotBlank()) Modifier.songClickable(art, onClick = onClick) else Modifier.pressable(onClick = onClick))) {
        Artwork(art, Modifier.size(size).clip(shape), size = size)
        ScrollingMetadata(title, TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), Modifier.padding(top = 8.dp))
        // Keep the artist slot even before metadata arrives, so the next shelf never moves.
        ScrollingMetadata(subtitle, TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant), Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

/** One line per metadata field. Marquee changes drawing, not measurement or gestures. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollingMetadata(text: String, style: TextStyle, modifier: Modifier = Modifier, poster: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val line = remember(text) { text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ') }
    // A poster line is set in capitals on screen; it still reads (and is found) as written.
    val shown = remember(line, poster) { if (poster) line.uppercase(Locale.getDefault()) else line }
    Text(shown, style = LocalTextStyle.current.merge(style),
        modifier = modifier.then(if (poster) Modifier.clearAndSetSemantics { this.text = AnnotatedString(line) } else Modifier)
            .fillMaxWidth().clipToBounds().then(if (reduced) Modifier else Modifier.basicMarquee(
            iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, repeatDelayMillis = 1_500, velocity = 24.dp)),
        minLines = 1, maxLines = 1, softWrap = false,
        overflow = if (reduced) TextOverflow.Ellipsis else TextOverflow.Clip)
}

/** Personal listening, discovery and provider collections keep independent browsing state. */
@Composable
private fun ExploreScreen(state: MusicState, content: ExploreShelves, tab: String, onTab: (String) -> Unit, onEntry: (FeedEntry) -> Unit,
    onRefreshFeed: () -> Unit, onRefreshExplore: () -> Unit,
    discoverScroll: LazyListState, browseScroll: LazyListState, active: Boolean, personal: @Composable () -> Unit) {
    val tabs = listOf("for-you" to "For you", "discover" to "Discover", "browse" to "Browse")
    val position = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            PageTitle("Explore")
            if (!LocalLook.current.hum) Text("Discoveries from ${state.recommendationEngine.label}.", fontSize = 12.sp, lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
            else Spacer(Modifier.height(16.dp))
            SegmentedTabs(tabs.map { it.second }, position.toFloat()) { onTab(tabs[it].first) }
        }
        // Clip the shelves to their own area: scrolled up, they were drawn over the title and the tabs.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            // key(tab) builds each tab's list from scratch: reusing one LazyColumn for two tabs left the
            // previous tab's rows drawn while the new tab's rows were in the semantics tree.
            pages.SaveableStateProvider(tab) { key(tab) {
                if (tab == "for-you") personal()
                else {
                    val sections = if (tab == "browse") content.browse else content.discover
                    val sourceEmpty = state.feed.isEmpty() && state.feedContinuations.isEmpty()
                    ProviderHome(state.copy(feed = sections,
                        feedBusy = state.exploreBusy || sections.isEmpty() && state.feedBusy, feedAppending = false,
                        feedError = state.exploreError ?: state.feedError.takeIf { sections.isEmpty() },
                        feedHasMore = false, feedEnded = false, homeFilters = emptyList(), homeFilter = null), onEntry,
                        { onRefreshExplore(); if (sourceEmpty) onRefreshFeed() }, {},
                        listState = if (tab == "browse") browseScroll else discoverScroll,
                        active = active, showTitle = false)
                }
            } }
        }
    }
}

@Composable
private fun ProviderHome(state: MusicState, onEntry: (FeedEntry) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, title: String = "For your ears", listState: LazyListState = rememberLazyListState(), active: Boolean = true, showTitle: Boolean = true, expanded: Boolean = false) {
    PrefetchArtwork(state.feed.flatMap { it.items.take(4).map(FeedEntry::artwork) }.take(12), 140.dp, diskOnly = true)
    PullToRefresh(state.feedBusy && !state.feedAppending, onRefresh) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (showTitle || state.homeFilters.isNotEmpty() || state.feedBusy && !state.feedAppending) item(key = "title") {
            Column {
                if (showTitle) {
                    PageTitle(title)
                    Spacer(Modifier.height(16.dp))
                }
                ProviderFilters(state)
                LoadingLine(state.feedBusy && !state.feedAppending, state.reducedMotion)
            }
        }
        if (expanded) expandedShelf(state.feed, onEntry) else feedSections(state.feed, onEntry)
        if (state.feedError != null && (state.feed.isEmpty() || !state.feedHasMore)) item(key = "error") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.feedError, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = if (state.feedHasMore && state.feed.isNotEmpty()) onMore else onRefresh, enabled = !state.feedBusy, shape = ButtonShape) { Text(LocalLook.current.label("Retry"), style = LabelText) }
            }
        }
        if (state.feed.isEmpty() && !state.feedBusy && state.feedError == null) item(key = "empty") { EmptyState("More music awaits", "Pull down to load discoveries from ${state.recommendationEngine.label}.", Glyph.Compass) }
        item(key = "feed-tail") { FeedContinuation(state, active, onMore) }
    }
    }
}

@Composable
private fun CollectionScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        if (LocalLook.current.hum) Text(state.collectionEntry?.title.orEmpty(), fontFamily = HumDisplay, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
            lineHeight = 38.sp, letterSpacing = (-.025).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Text(state.collectionEntry?.title.orEmpty(), fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.collectionEntry?.subtitle.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        if (state.collectionBusy && state.collection.isEmpty()) LoadingLine(true, state.reducedMotion)
        else if (state.collection.isEmpty() && state.error == null) {
            EmptyState("Nothing to play here", "This page has no available recordings.", Glyph.MusicNote)
            if (state.collectionHasMore) OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth(), shape = ButtonShape) { Text("Load more songs") }
        }
        else {
            val download = LocalSongActions.current.download
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.collection.firstOrNull()?.let { onPlay(it, state.collection) } }, enabled = state.collection.isNotEmpty(), shape = ButtonShape) { Icon(Icons.Outlined.PlayArrow, null); Text("Play all") }
                OutlinedButton(onClick = { download(state.collection) }, enabled = state.collection.any { it.source == Source.YOUTUBE }, shape = ButtonShape) { Text("Download all") }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(state.collection, key = { i, track -> "$i:${track.id}" }) { _, track ->
                    TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(), { onPlay(track, state.collection) }, { onSave(track) })
                }
                if (state.collectionBusy) item { LoadingLine(true, state.reducedMotion) }
                if (state.collectionHasMore) item { OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth(), shape = ButtonShape) { Text("Load more songs") } }
            }
        }
    }
}

@Composable
private fun SearchBar(state: MusicState, onQuery: (String) -> Unit, onSearch: () -> Unit, focus: FocusRequester, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSearch() }
    OutlinedTextField(
        value = state.query, onValueChange = onQuery,
        modifier = modifier.focusRequester(focus).semantics { contentDescription = "Search music" }, singleLine = true,
        placeholder = { Text("Songs, artists or a link", fontSize = 13.sp, maxLines = 1) },
        leadingIcon = { IconButton(onClick = submit, enabled = state.query.isNotBlank() && !state.searching) { Icon(Icons.Outlined.Search, "Search YouTube", Modifier.size(20.dp)) } },
        trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery(""); keyboard?.hide() }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) } },
        shape = LocalLook.current.field,
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
                Caption(LocalLook.current.let { if (it.room) "${state.results.size} ${if (state.results.size == 1) "result" else "results"}" else "${state.results.size} RESULTS" })
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(state.results, key = { index, track -> "$index:${track.id}" }) { _, track ->
                        TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(), { onPlay(track, listOf(track)) }, { onSave(track) }, motionModifier())
                    }
                }
            }
            state.searched && state.error == null -> EmptyState("Nothing found this time", "Try another title, artist, or a direct YouTube link.", Icons.Outlined.Search)
            !state.searched -> EmptyState("Follow a little curiosity", "Search YouTube or paste a video or playlist link. You can also share a link here from another app.", Glyph.MusicNote)
        }
    }
}

/** Saved and History side by side; swipe or tap the tabs, the highlight follows your finger. */
@Composable
private fun LibraryScreen(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit, onSearch: () -> Unit, onRemovePlay: (PlayEvent) -> Unit) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val reduced = state.reducedMotion
    val playlistActions = LocalSongActions.current
    LaunchedEffect(pager.currentPage, state.account.connected, state.account.revision) { if (pager.currentPage == 2) playlistActions.loadPlaylists(null) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(10.dp)); PageTitle("Library")
            Spacer(Modifier.height(18.dp))
            SegmentedTabs(listOf("Saved", "History", "Playlists", "Downloads"), pager.currentPage + pager.currentPageOffsetFraction) { index ->
                scope.launch { if (reduced) pager.scrollToPage(index) else pager.animateScrollToPage(index, animationSpec = spring(dampingRatio = .9f, stiffness = 400f)) }
            }
            Spacer(Modifier.height(16.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1, key = { it }) { index ->
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                when (index) {
                    0 -> SavedList(state, onPlay, onSave, onSearch)
                    1 -> HistoryList(state, onPlay, onRemovePlay)
                    2 -> PlaylistLibrary(state, onPlay, pager.currentPage == 2)
                    else -> DownloadsList(state, onPlay)
                }
            }
        }
    }
}

/** A pill track with a thumb that slides continuously, including mid-swipe between pages. */
@Composable
private fun SegmentedTabs(labels: List<String>, position: Float, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pill = if (LocalLook.current.classic) RectangleShape else RoundedCornerShape(percent = 50)
    // 48dp tall targets; the chosen tab is a solid thumb in the text colour with its label in the
    // page colour (the old raised thumb was 1.1:1 against the track).
    val look = LocalLook.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp).background(look.selectorTrack, pill).border(1.dp, look.selectorEdge, pill).padding(2.dp).selectableGroup()) {
        val slot = maxWidth / labels.size
        Box(Modifier.offset(x = slot * position.coerceIn(0f, labels.lastIndex.toFloat())).width(slot).fillMaxHeight()
            .graphicsLayer { shadowElevation = 3.dp.toPx(); shape = pill; clip = true }
            .background(look.selectorThumb, pill))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val closeness = (1f - abs(position - index)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(look.muted, look.onSelectorThumb, closeness)
                val chosen = abs(position - index) < .5f
                Box(Modifier.weight(1f).fillMaxHeight().clip(pill).clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { contentDescription = label; selected = chosen }, contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 14.sp, fontWeight = if (closeness > .5f) FontWeight.SemiBold else FontWeight.Medium, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** Songs saved on this device: they play without a connection. In-flight ones show their progress. */
@Composable
private fun DownloadsList(state: MusicState, onPlay: (Track, List<Track>) -> Unit) {
    val actions = LocalSongActions.current
    val songs = remember(state.downloads) { state.downloads.map { it.track } }
    if (songs.isEmpty() && state.downloading.isEmpty()) {
        EmptyState("Nothing downloaded yet", "Open a song's options and choose Download to listen without a connection.", Icons.Outlined.KeyboardArrowDown)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val megabytes = state.downloads.sumOf { it.bytes } / 1_048_576
            Caption(if (LocalLook.current.room) "${songs.size} saved · $megabytes MB" else "${songs.size} DOWNLOADED · $megabytes MB")
            if (songs.isNotEmpty()) Button(onClick = { onPlay(songs.first(), songs) }, shape = ButtonShape) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Play all", fontSize = 12.sp)
            }
        }
        if (state.downloading.isNotEmpty()) {
            val waiting = state.downloading.values.count { it < 0f }
            val average = state.downloading.values.filter { it >= 0f }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            Text("Downloading ${state.downloading.size} ${if (state.downloading.size == 1) "song" else "songs"}${if (waiting > 0) " · $waiting waiting" else ""}",
                Modifier.padding(top = 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { average }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(songs, key = { "dl:" + it.libraryKey() }) { track ->
                TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(),
                    { onPlay(track, songs) }, null, motionModifier())
            }
            if (songs.isNotEmpty()) item { TextButton(onClick = { songs.forEach(actions.removeDownload) }) { Text("Remove all downloads") } }
        }
    }
}

@Composable
private fun SavedList(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onSave: (Track) -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Classic's and Soft's line; the Listening room goes straight to the songs.
        if (!LocalLook.current.room) Text("Worth keeping.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        if (state.saved.isEmpty()) {
            EmptyState("Keep a good thing close", "Tap the heart on a recording to save it here.", Icons.Outlined.FavoriteBorder)
            if (!LocalLook.current.hum) TextButton(onClick = onSearch, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Find music") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Caption(if (LocalLook.current.room) "${state.saved.size} saved" else "${state.saved.size} SAVED")
                Button(onClick = { onPlay(state.saved.first(), state.saved) }, shape = ButtonShape) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Play all", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                // Saved is unique by libraryKey; a Spotify-matched copy can share the YouTube id.
                items(state.saved, key = { it.libraryKey() }) { track -> TrackRow(track, true, state.current?.libraryKey() == track.libraryKey(), { onPlay(track, listOf(track)) }, { onSave(track) }, motionModifier()) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(state: MusicState, onPlay: (Track, List<Track>) -> Unit, onRemove: (PlayEvent) -> Unit) {
    val events = state.playEvents
    if (events.isEmpty()) {
        EmptyState(if (state.trackHistory) "Your listening, day by day" else "History is off",
            if (state.trackHistory) "Songs you listen to for more than a few seconds show up here, with how long you listened." else "Turn on listening history in Settings › Privacy.", Glyph.History)
        return
    }
    val days = remember(events) { ListeningHistory.days(events) }
    val stats = remember(events) { ListeningHistory.stats(events) }
    val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "stats") {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${stats.minutesThisWeek}", "minutes this week", Modifier.weight(1f))
                StatTile("${stats.playsThisWeek}", "plays this week", Modifier.weight(1f))
                StatTile(stats.topArtist ?: "—", "top artist", Modifier.weight(1.3f), small = true)
            }
        }
        days.forEach { day ->
            stickyHeader(key = "day:${day.label}") {
                Text(day.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 12.dp, bottom = 6.dp))
            }
            items(day.events, key = { "play:${it.startedAt}:${it.track.libraryKey()}" }) { event ->
                HistoryRow(event, timeFormat.format(java.util.Date(event.startedAt)), state.current?.id == event.track.id,
                    { onPlay(event.track, listOf(event.track)) }, { onRemove(event) }, motionModifier())
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, LocalLook.current.tile).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(value, fontSize = if (small) 15.sp else 22.sp, lineHeight = if (small) 22.sp else 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

/**
 * The colour that marks the song that is playing in a list: the accent, moved in lightness until
 * it reads 4.5:1 as text on the page, or the text colour if no lightness does.
 */
@Composable
private fun playingColour(): Color {
    val accent = LocalAccent.current.accent
    val ground = MaterialTheme.colorScheme.background
    val text = MaterialTheme.colorScheme.onSurface
    return remember(accent, ground, text) { closestLightness(accent) { contrastRatio(it, ground) >= MinTextContrast } ?: text }
}

/** "Playing ·" before the artist, so the playing song is never marked by colour alone. */
@Composable
private fun PlayingTag(colour: Color) {
    Text("Playing · ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colour, maxLines = 1)
}

@Composable
private fun HistoryRow(event: PlayEvent, time: String, current: Boolean, onPlay: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val track = event.track
    Row(modifier.fillMaxWidth().clip(LocalLook.current.art).songClickable(track, onClick = onPlay).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, Modifier.size(48.dp).clip(LocalLook.current.art), size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val playingColour = playingColour()
            Text(track.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) playingColour else MaterialTheme.colorScheme.onSurface)
            val listened = clock(event.listenedMs)
            Row(Modifier.padding(top = 3.dp)) {
                if (current) PlayingTag(playingColour)
                Text(listOf(track.artist, "$time · $listened" + if (event.skipped) " · skipped" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove ${track.title} from history", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Motion for the player: Material emphasized curves, 160–240 ms, no bounces or scaling. */
private const val QuickMs = 160
private const val SwapMs = 180

@Composable
private fun NowScreen(state: MusicState, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
    onSave: (Track) -> Unit, onSkipTo: (Int) -> Unit, onProcessing: (Boolean) -> Unit, playbackPosition: StateFlow<Long>?, onRetryRadio: () -> Unit, onLyrics: suspend (Track) -> labs.rtensor.music.data.Lyrics?,
    audioLevels: StateFlow<labs.rtensor.dsp.AudioLevelFrame?>?, metering: MeteringClaims) {
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
    val hasNext = queueIndex >= 0 && queueIndex < state.queue.lastIndex
    // Same rule as the mini player: buffering never locks the transport, so a stalled song can
    // still be paused or skipped here. Only finding audio (resolving) holds Play/Pause back.
    val canSkip = !pending
    val upcoming = if (queueIndex >= 0) state.queue.withIndex().drop(queueIndex + 1) else emptyList()
    val tones = LocalPlayerTones.current
    val ground = if (tones != null) Modifier.background(Brush.verticalGradient(listOf(tones.top, tones.bottom)))
        else Modifier.background(MaterialTheme.colorScheme.background)
    OnPlayerTones(ground) {
    val scheme = MaterialTheme.colorScheme
    val look = LocalLook.current
    val saved = state.saved.any { it.libraryKey() == track.libraryKey() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Soft, as in 0.3.31: a soft, blurred wash of the cover behind everything; it fades with
        // the track. The wash is drawing only and never takes touches or focus.
        if (look.soft) Crossfade(track, Modifier.matchParentSize().testTag("soft-cover-wash").clearAndSetSemantics {},
            animationSpec = tween(if (reduced) 0 else 240), label = "backdrop") { shown ->
            Box(Modifier.fillMaxSize()) {
                Artwork(shown, Modifier.fillMaxWidth().fillMaxHeight(.62f).graphicsLayer { alpha = .38f }.blur(60.dp), size = 120.dp)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(scheme.background.copy(alpha = .35f),
                    scheme.background.copy(alpha = .85f), scheme.background), endY = 1600f)))
            }
        }
        // The cover fills the width between the gutters, up to 37% of the height, which leaves
        // the poster title, meter and controls with the next song still in view. Classic keeps its
        // smaller 0.3.32 cover; Soft's fills the whole width, as in 0.3.31.
        val gutter = if (look.soft) 24.dp else 20.dp
        val coverSize = when (look.choice) {
            LookChoice.CLASSIC -> (maxHeight * .31f).coerceIn(150.dp, 250.dp)
            LookChoice.SOFT -> maxWidth - gutter * 2
            LookChoice.ROOM -> minOf(maxWidth - 40.dp, maxHeight * .37f).coerceAtLeast(150.dp)
            LookChoice.HUM -> minOf(maxWidth - 40.dp, maxHeight * .30f).coerceAtLeast(150.dp)
        }
        // The queue continues right under the controls, so scrolling down shows what plays next.
        // Hum pins the player: only the queue below it scrolls, so the cover and controls never move.
        val playerTail: @Composable () -> Unit = {
                    RouteLabel(state.routeLabel)
                    if (showsAudioComparison(state)) {
                        when (look.choice) {
                            LookChoice.CLASSIC -> ClassicAudioComparison(state, onProcessing)
                            LookChoice.SOFT -> SoftAudioComparison(state, onProcessing)
                            LookChoice.ROOM, LookChoice.HUM -> AudioComparison(state, onProcessing)
                        }
                    }
                    if (state.developerOptions) ChannelMonitor(state.audioProcessing.report, playing && !loading, track.id, audioLevels) { metering.claim("monitor", it) }
                    LyricsPanel(track, onLyrics, reduced, Modifier.padding(top = 16.dp), positions = playbackPosition, onSeek = onSeek)
                    Spacer(Modifier.height(30.dp))
                    Row(Modifier.fillMaxWidth().signalRule(bottom = true).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        when (look.choice) {
                            LookChoice.CLASSIC -> Text("Up next", style = DisplayStyle, fontSize = 24.sp, modifier = Modifier.weight(1f).semantics { heading() })
                            LookChoice.SOFT -> Text("Up next", fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp, modifier = Modifier.weight(1f).semantics { heading() })
                            LookChoice.ROOM, LookChoice.HUM -> PosterText("Up next", SectionStyle, Modifier.weight(1f), heading = true)
                        }
                        if (upcoming.isNotEmpty()) Text(if (look.classic) "${upcoming.size} ${if (upcoming.size == 1) "SONG" else "SONGS"}"
                            else "${upcoming.size} ${if (upcoming.size == 1) "song" else "songs"}",
                            style = if (look.soft) TextStyle(fontSize = 12.sp) else MonoSmall, color = scheme.onSurfaceVariant)
                    }
        }
        val playerBlock: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(coverSize)) {
                            CoverDeck(track, queueIndex, state.queue.getOrNull(queueIndex - 1), state.queue.getOrNull(queueIndex + 1), reduced,
                                canPrevious = canSkip, canNext = canSkip && hasNext, onPrevious = onPrevious, onNext = onNext)
                        }
                    }
                    if (look.soft) {
                        // Soft, as in 0.3.31: the seek line under the cover, a big DM Sans title, then
                        // Previous, a raspberry Play, Next and Save, each with its name underneath.
                        Spacer(Modifier.height(16.dp))
                        PlaybackTimeline(playbackPosition, position, duration, playing, reduced, pending, null, onSeek)
                        Spacer(Modifier.height(18.dp))
                        NowTitle(track, reduced, saved, { onSave(track) }) { shown ->
                            ScrollingMetadata(shown.title, TextStyle(fontFamily = SoftFont, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium), Modifier.semantics { heading() })
                            ScrollingMetadata(shown.artist, TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = scheme.onSurfaceVariant), Modifier.padding(top = 6.dp))
                        }
                        LoadingNote(state, loading, reduced, top = 8.dp)
                        Spacer(Modifier.height(17.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TransportButton("Previous", Glyph.Previous, onPrevious, canSkip) }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PlayButton(if (loading) 0 else if (playing) 1 else 2, !state.resolving, reduced, onToggle) }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TransportButton("Next", Glyph.Next, onNext, canSkip && hasNext) }
                        }
                    } else if (look.classic) {
                        // Classic, as in 0.3.32: the seek line under the cover, a display title, square
                        // Previous/Next either side of one wide Play button.
                        Spacer(Modifier.height(16.dp))
                        PlaybackTimeline(playbackPosition, position, duration, playing, reduced, pending, null, onSeek)
                        Spacer(Modifier.height(14.dp))
                        NowTitle(track, reduced, saved, { onSave(track) }) { shown ->
                            ScrollingMetadata(shown.title, DisplayStyle.copy(fontSize = 32.sp, lineHeight = 36.sp), Modifier.semantics { heading() })
                            ScrollingMetadata(shown.artist, TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = scheme.onSurfaceVariant), Modifier.padding(top = 6.dp))
                        }
                        LoadingNote(state, loading, reduced, top = 8.dp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LabelledControl("Previous", Glyph.Previous, onPrevious, enabled = canSkip)
                            WidePlayButton(if (loading) 0 else if (playing) 1 else 2, enabled = !state.resolving, reduced = reduced, onClick = onToggle, modifier = Modifier.weight(1f))
                            LabelledControl("Next", Glyph.Next, onNext, enabled = canSkip && hasNext)
                        }
                    } else {
                        Spacer(Modifier.height(20.dp))
                        // The title rises into place from below while the old one lifts away: a different
                        // motion from the cover so the two never look like one sliding block.
                        // A poster title (two lines at most, smaller when it needs to be), then the
                        // artist with the codec badge.
                        val badge = audioBadge(state.routeLabel)
                        NowTitle(track, reduced, saved, { onSave(track) }) { shown ->
                            PosterTitle(shown.title)
                            ArtistLine(shown.artist, badge)
                        }
                        // The small level meter: shown only while live levels move it. Its line is always
                        // reserved, so nothing moves when it appears.
                        val report = state.audioProcessing.report
                        val meterLive = playing && !loading && !reduced && state.developerOptions && audioLevels != null
                        if (state.developerOptions) LiveMetering("meter", meterLive, metering)
                        val frame = audioLevels?.collectAsStateWithLifecycle()
                        val bars = markOn(LocalAccent.current.accent, scheme.background, scheme.onBackground)
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(16.dp)) {
                            // Drawn only while it moves; a still meter reads as broken. The line stays reserved.
                            if (report != null && meterLive) LevelMeter(report, frame, meterLive, bars, Modifier.testTag("level-meter"))
                        }
                        Spacer(Modifier.height(10.dp))
                        PlaybackTimeline(playbackPosition, position, duration, playing, reduced, pending, null, onSeek)
                        LoadingNote(state, loading, reduced, top = 4.dp)
                        Spacer(Modifier.height(6.dp))
                        // Symmetric transport keeps Play/Pause on the screen centre.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            TransportButton("Previous", Glyph.Previous, onPrevious, enabled = canSkip)
                            PlayButton(if (loading) 0 else if (playing) 1 else 2, enabled = !state.resolving, reduced = reduced, onClick = onToggle)
                            TransportButton("Next", Glyph.Next, onNext, enabled = canSkip && hasNext)
                        }
                    }
                    if (!look.hum) playerTail()
                }
        }
        val queueItems: LazyListScope.() -> Unit = {
            // Keyed by song (plus its repeat number), never by position: a position in the key changes
            // for every row each time the track advances, so the whole list faded out and in on top of
            // itself and drew across the controls.
            val seen = HashMap<String, Int>()
            val queueKeys = upcoming.map { (_, item) -> "q:${item.id}:${seen.merge(item.id, 1, Int::plus)}" }
            items(upcoming.size, key = { queueKeys[it] }) { slot ->
                val (index, item) = upcoming[slot]
                TrackRow(item, false, false, { onSkipTo(index) }, null,
                    if (reduced) Modifier else Modifier.animateItem(fadeOutSpec = null), SongMenuTarget(item, queueIndex = index))
            }
            item(key = "save-queue") {
                val menus = LocalSongMenus.current
                TextButton(onClick = { menus?.create?.invoke(state.queue) }, enabled = state.queue.isNotEmpty() && !state.playlistBusy) { Text("Save queue as playlist") }
            }
            item(key = "queue-end") {
                Column {
                    HorizontalDivider(color = scheme.outline, thickness = .5.dp)
                    Text(when {
                        !state.autoplay -> "The queue ends here. Turn on Autoplay radio in Settings to keep going."
                        state.radioLoading -> "Getting ${state.recommendationEngine.label} recommendations…"
                        state.radioError != null -> state.radioError
                        track.source == labs.rtensor.music.data.Source.LOCAL -> "Local recording · choose another song or add recordings to the queue."
                        else -> "${state.recommendationEngine.label} recommendations continue after this queue."
                    },
                        fontSize = 12.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                    if (state.autoplay && state.radioError != null) TextButton(onClick = onRetryRadio) { Text("Retry recommendations") }
                }
            }
        }
        if (look.hum) Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 10.dp)) {
            playerBlock()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "player-tail") { Column { playerTail() } }
                queueItems()
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, top = 10.dp, bottom = 32.dp)) {
            item(key = "player") { playerBlock() }
            queueItems()
        }
    }
    }
}

/** The song's title and artist beside Save. A new title rises into place while the old one lifts away. */
@Composable
private fun NowTitle(track: Track, reduced: Boolean, saved: Boolean, onSave: () -> Unit, content: @Composable ColumnScope.(Track) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(track, modifier = Modifier.weight(1f), contentKey = { it.id }, label = "now title", transitionSpec = {
            if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
            else ((fadeIn(tween(220, 60, EmphasizedDecelerate)) + slideInVertically(tween(280, easing = EmphasizedDecelerate)) { it / 4 }) togetherWith
                (fadeOut(tween(100)) + slideOutVertically(tween(140, easing = EmphasizedAccelerate)) { -it / 8 })).using(SizeTransform(clip = false))
        }) { shown -> Column(Modifier.fillMaxWidth()) { content(shown) } }
        Spacer(Modifier.width(12.dp))
        val accent = LocalAccent.current
        if (LocalLook.current.classic) {
            LabelledControl(if (saved) "Saved" else "Save", if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                onSave, modifier = Modifier.size(48.dp), highlighted = saved)
        } else IconToggleButton(checked = saved, onCheckedChange = { onSave() }, modifier = Modifier.size(48.dp).clip(CircleShape)
            .semantics { contentDescription = if (saved) "Saved" else "Save" },
            colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = accent.accent.copy(alpha = .14f), checkedContentColor = accent.accent)) {
            LikeBurst(saved, reduced, accent.accent) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null, Modifier.size(24.dp)) }
        }
        SongMoreButton(track)
    }
}

/** "Finding audio…" or "Buffering…" in a reserved line, so the controls never jump when loading starts or stops. */
@Composable
private fun LoadingNote(state: MusicState, loading: Boolean, reduced: Boolean, top: Dp) {
    val look = LocalLook.current
    Box(Modifier.padding(top = top).height(16.dp)) {
        androidx.compose.animation.AnimatedVisibility(loading, enter = fadeIn(tween(if (reduced) 0 else QuickMs)), exit = fadeOut(tween(if (reduced) 0 else QuickMs))) {
            Text(look.label(if (state.resolving) "Finding audio…" else "Buffering…"), style = if (look.classic) MonoSmall else TextStyle(fontFamily = font, fontSize = 12.sp),
                color = if (look.soft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp)
        }
    }
}

/** Classic's wide accent Play/Pause button with its word. [mode]: 0 loading, 1 playing (shows Pause), 2 paused. */
@Composable
private fun WidePlayButton(mode: Int, enabled: Boolean, reduced: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val label = when (mode) { 0 -> "Loading"; 1 -> "Pause"; else -> "Play" }
    Button(onClick = onClick, enabled = enabled, shape = RectangleShape, modifier = modifier.height(72.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent.accent, contentColor = accent.onAccent,
            disabledContainerColor = accent.accent.copy(alpha = .55f), disabledContentColor = accent.onAccent)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(mode, modifier = Modifier.size(28.dp), animationSpec = tween(if (reduced) 0 else 90), label = "play icon") { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (shown) {
                        0 -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accent.onAccent)
                        1 -> Icon(Glyph.Pause, null, Modifier.size(22.dp))
                        else -> Icon(Icons.Outlined.PlayArrow, null, Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = DisplayStyle, fontSize = 20.sp, maxLines = 1)
        }
    }
}

/**
 * On the player with a cover gradient, content reads as on ink: paper text, muted paper for
 * secondary text, and the accent lifted so it stands out from the tone. Elsewhere it does nothing
 * but apply [modifier].
 */
@Composable
private fun OnPlayerTones(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tones = LocalPlayerTones.current
    if (tones == null) { Box(modifier) { content() }; return }
    val accent = LocalAccent.current
    val lifted = remember(accent.accent, tones) { accentOnTone(accent.accent, tones.top) }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, tones, lifted) {
        base.copy(primary = Paper, onPrimary = Ink, background = tones.top, onBackground = Paper, surface = tones.top, onSurface = Paper,
            surfaceVariant = Paper.copy(alpha = .12f).compositeOver(tones.top), onSurfaceVariant = PaperMuted,
            outline = Paper.copy(alpha = .3f).compositeOver(tones.top), outlineVariant = Paper.copy(alpha = .18f).compositeOver(tones.top),
            primaryContainer = lifted, onPrimaryContainer = onAccentFor(lifted), secondaryContainer = lifted, onSecondaryContainer = onAccentFor(lifted))
    }
    CompositionLocalProvider(LocalAccent provides AccentColours(lifted, onAccentFor(lifted), accent.onDock)) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            Box(modifier) { CompositionLocalProvider(LocalContentColor provides Paper) { content() } }
        }
    }
}

/** Shown for every multichannel source, and whenever the DSP is missing, so a disabled
 * toggle explains itself; plain stereo plays the original by design and stays quiet. */
private fun showsAudioComparison(state: MusicState): Boolean {
    val audio = state.audioProcessing
    val report = audio.report
    return when {
        // No processing on this build: a switch that cannot switch is noise, so it is not shown.
        !audio.available -> false
        report == null -> false
        else -> report.comparisonAvailable || report.sourceChannels > 2
    }
}

@Composable
private fun AudioComparison(state: MusicState, onProcessing: (Boolean) -> Unit) {
    val audio = state.audioProcessing
    val report = audio.report
    val waiting = audio.available && (state.loadingTrack != null || report == null)
    val ready = audio.available && report?.comparisonAvailable == true && state.loadingTrack == null
    val processed = audio.selectedProcessed && (waiting || report?.comparisonAvailable == true)
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        // One pill with two options, Original and Processed. It is a single switch (on is
        // Processed), so a tap anywhere changes sides, exactly like the earlier switch; it is
        // disabled, with the reason below, whenever the comparison is not available.
        val pill = RoundedCornerShape(percent = 50)
        val layout = report?.let(::layoutName)
        val chosen = accent.accent.copy(alpha = .24f).compositeOver(scheme.surfaceVariant)
        val edge = markOn(accent.accent, scheme.surfaceVariant, scheme.onSurface)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).height(IntrinsicSize.Min).clip(pill).background(scheme.surfaceVariant)
                .toggleable(value = processed, enabled = ready, role = Role.Switch, onValueChange = onProcessing)
                .semantics { contentDescription = "Processed audio" }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (side in listOf(false, true)) {
                val on = side == processed
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(pill)
                        .then(if (on) Modifier.background(if (ready) chosen else chosen.copy(alpha = .5f)).border(1.5.dp, if (ready) edge else edge.copy(alpha = .4f), pill) else Modifier),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (side) { SpeakerLights(report, processed, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(if (side) listOfNotNull("Processed", layout).joinToString(" · ") else "Original", fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        // The side not chosen stays readable on a cover-tone ground too (muted grey was 3.5:1).
                        color = if (!ready) scheme.onSurfaceVariant else if (on) scheme.onSurface else scheme.onSurface.copy(alpha = .85f))
                }
            }
        }
        val detail = comparisonDetail(state)
        Text(detail, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The line under the Original/Processed control: what is playing, or why the control is off. */
private fun comparisonDetail(state: MusicState): String {
    val audio = state.audioProcessing
    val report = audio.report
    return when {
        !audio.available -> "Original audio only on this build"
        state.loadingTrack != null || report == null -> "Waiting for audio…"
        !report.comparisonAvailable -> when (report.sourceChannels) {
            1 -> "Mono · original audio"
            2 -> "Stereo · original audio"
            else -> "${report.sourceChannels} channels · original audio" + (report.unavailableReason?.let { " · $it" } ?: "")
        }
        audio.comparisonPending -> {
            val target = when {
                !audio.selectedProcessed -> "Original"
                else -> "Processed"
            }
            if (!state.playing || state.buffering || state.resolving) "$target · ready when you play"
            else "Switching to ${target.lowercase()}…"
        }
        !report.processingEnabled -> if (report.inputPositions.size == 2) "Stereo · original audio" else "Front L/R only · ${report.inputPositions.size}-channel source"
        report.inputPositions.size == 2 -> "Stereo processing"
        else -> "${report.inputPositions.size} channels → stereo"
    }
}

/**
 * Soft's Original/Processed control, as in 0.3.31: two filter chips. Same state, callbacks and
 * explanation as the other looks.
 */
@Composable
private fun SoftAudioComparison(state: MusicState, onProcessing: (Boolean) -> Unit) {
    val audio = state.audioProcessing
    val report = audio.report
    val waiting = audio.available && (state.loadingTrack != null || report == null)
    val ready = audio.available && report?.comparisonAvailable == true && state.loadingTrack == null
    val processed = audio.selectedProcessed && (waiting || report?.comparisonAvailable == true)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !processed, onClick = { onProcessing(false) }, enabled = ready,
                label = { Text("Original") }, modifier = Modifier.semantics { contentDescription = "Original audio" })
            FilterChip(selected = processed, onClick = { onProcessing(true) }, enabled = ready,
                label = { Text("Processed") }, modifier = Modifier.semantics { contentDescription = "Processed audio" })
        }
        Text(comparisonDetail(state), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Soft's player control, as in 0.3.31: an outlined rounded icon button with its name underneath.
 * The button carries the name; the caption below is not read twice.
 */
@Composable
private fun SoftControl(label: String, image: ImageVector, onClick: () -> Unit, enabled: Boolean = true, highlighted: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
            val tint by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.primary else if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), tween(if (LocalReducedMotion.current) 0 else 180), label = "control tint")
            Icon(image, null, Modifier.size(21.dp), tint = tint)
        }
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics {})
    }
}

/**
 * Classic's Original/Processed control, as in 0.3.32: two square segments, ink when chosen. Same
 * state, callbacks and explanation as the Listening room's switch.
 */
@Composable
private fun ClassicAudioComparison(state: MusicState, onProcessing: (Boolean) -> Unit) {
    val audio = state.audioProcessing
    val report = audio.report
    val waiting = audio.available && (state.loadingTrack != null || report == null)
    val ready = audio.available && report?.comparisonAvailable == true && state.loadingTrack == null
    val processed = audio.selectedProcessed && (waiting || report?.comparisonAvailable == true)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        val edge = if (ready) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).height(IntrinsicSize.Min).border(2.dp, edge, RectangleShape).padding(2.dp)) {
            ModeSegment("ORIGINAL", "Original audio", !processed, ready, Modifier.weight(1f)) { onProcessing(false) }
            ModeSegment("PROCESSED", "Processed audio", processed, ready, Modifier.weight(1f)) { onProcessing(true) }
        }
        Text(comparisonDetail(state), style = MonoSmall, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** One half of Classic's listening-mode control: ink when chosen, bare ground otherwise. */
@Composable
private fun ModeSegment(text: String, description: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.onBackground else Color.Transparent
    val ink = when {
        selected -> scheme.background
        enabled -> scheme.onBackground
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier.fillMaxHeight().background(if (selected && !enabled) fill.copy(alpha = .6f) else fill)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MonoLabel, color = ink, maxLines = 1) }
}

/** A speaker layout the way people write it: stereo, 5.1, 7.1.4. Null when the source does not say. */
internal fun layoutName(report: labs.rtensor.dsp.DownmixReport): String? {
    val positions = report.inputPositions
    if (positions.isEmpty()) return report.sourceChannels.takeIf { it > 0 }?.let { if (it == 2) "stereo" else if (it == 1) "mono" else "$it ch" }
    if (positions.size == 1) return "mono"
    if (positions.size == 2) return "stereo"
    val lfe = if (labs.rtensor.dsp.SpeakerPosition.LFE in positions) 1 else 0
    val height = positions.count { it.id >= labs.rtensor.dsp.SpeakerPosition.TFL.id }
    val bed = positions.size - lfe - height
    return "$bed.$lfe" + if (height > 0) ".$height" else ""
}

/** Read the clock in this small subtree, rather than invalidating the cover,
 * controls and lazy queue every time the playback position changes. */
@Composable
private fun PlaybackTimeline(updates: StateFlow<Long>?, fallback: Long, duration: Long, playing: Boolean,
    reduced: Boolean, pending: Boolean, badge: String?, onSeek: (Long) -> Unit) {
    val position = updates?.collectAsStateWithLifecycle()?.value ?: fallback
    ProgressLine(if (pending) 0 else position, duration, playing, reduced, badge, onSeek)
}

/**
 * The audio format as a short technical badge, taken from the route label the player already
 * shows ("YouTube · AAC compressed 48.0 kHz · stereo · unchanged" gives "AAC · 48 kHz").
 * Only what the label states is shown; there is no bitrate in it, so none is shown.
 */
internal fun audioBadge(route: String): String? {
    if (route.isBlank()) return null
    val codec = listOf("E-AC-3 JOC", "E-AC-3", "FLAC", "ALAC", "AAC").firstOrNull { route.contains(it) }
    val rate = Regex("""(\d+(?:\.\d+)?) kHz""").find(route)?.groupValues?.get(1)?.toFloatOrNull()?.let {
        if (it % 1f == 0f) "${it.toInt()} kHz" else "${"%.1f".format(Locale.ROOT, it)} kHz"
    }
    return listOfNotNull(codec, rate).joinToString(" · ").ifBlank { null }
}

/**
 * The Listening room's player title: the condensed face in capitals, two lines at most, stepping
 * down in size until it fits. Its height is always two lines, so the controls never move.
 */
@Composable
private fun PosterTitle(text: String) {
    val colour = MaterialTheme.colorScheme.onBackground
    val shown = remember(text) { text.replace('\n', ' ').uppercase(Locale.getDefault()) }
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val height = with(density) { (38.sp * .95f * 2).toDp() } + 4.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.BottomStart) {
        val width = constraints.maxWidth
        val limit = constraints.maxHeight
        // The largest size, in 2sp steps from 38sp down to 20sp, at which the title fits in two lines.
        val size = remember(shown, width, limit, density) {
            (38 downTo 20 step 2).firstOrNull { sp ->
                val layout = measurer.measure(shown, PosterStyle.copy(fontSize = sp.sp, lineHeight = .95.em), maxLines = 2,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = width))
                !layout.hasVisualOverflow && layout.size.height <= limit
            } ?: 20
        }
        Text(shown, Modifier.fillMaxWidth().clearAndSetSemantics { this.text = AnnotatedString(text); heading() },
            style = PosterStyle.copy(fontSize = size.sp, lineHeight = .95.em), color = colour, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Artist · AAC · 48 kHz": the artist, then the format badge taken from the route label. */
@Composable
private fun ArtistLine(artist: String, badge: String?) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (artist.isNotBlank()) Text(artist, Modifier.weight(1f, fill = false), fontFamily = font, fontSize = 15.sp, lineHeight = 20.sp,
            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            if (artist.isNotBlank()) Text(" · ", fontSize = 15.sp, color = scheme.onSurfaceVariant)
            Text(badge, style = MonoSmall, fontSize = 11.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.border(1.dp, scheme.outline, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Audio format $badge" })
        }
    }
}

/** Previous and Next: a bare glyph in a round 56dp target, no box. */
@Composable
private fun TransportButton(label: String, image: ImageVector, onClick: () -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(56.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(image, null, Modifier.size(28.dp), tint = if (enabled) scheme.onBackground else scheme.onSurfaceVariant.copy(alpha = .4f)) }
}

/** The round 58dp accent Play/Pause button. [mode]: 0 loading, 1 playing (shows Pause), 2 paused. */
@Composable
private fun PlayButton(mode: Int, enabled: Boolean, reduced: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val label = when (mode) { 0 -> "Loading"; 1 -> "Pause"; else -> "Play" }
    Box(
        Modifier.size(64.dp).glow(accent.accent, spread = 1.45f, strength = if (enabled) .45f else .2f)
            .clip(CircleShape).background(if (enabled) accent.accent else accent.accent.copy(alpha = .55f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(mode == 0, modifier = Modifier.size(32.dp), animationSpec = tween(if (reduced) 0 else 90), label = "play button") { busy ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent.onAccent)
                else PlayPauseMorph(mode == 1, accent.onAccent, 28.dp, reduced)
            }
        }
    }
}

/** The mini player's clock also stays in its own composition/draw subtree. */
@Composable
private fun MiniPlaybackProgress(updates: StateFlow<Long>?, fallback: Long, duration: Long, playing: Boolean, modifier: Modifier) {
    val position = updates?.collectAsStateWithLifecycle()?.value ?: fallback
    val smooth = rememberSmoothPosition(position, duration, playing)
    // The hairline runs along the top of the black dock.
    val accent = LocalAccent.current.onDock
    Canvas(modifier.graphicsLayer().semantics {
        contentDescription = "Mini player progress"
        progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f)
    }) {
        if (duration > 0) {
            val fraction = (smooth.value.toFloat() / duration).coerceIn(0f, 1f)
            drawRect(accent, size = Size(size.width * fraction, 2.dp.toPx()))
        }
    }
}

/** What the deck remembers between frames without causing recomposition. */
private class DeckMemory(var track: Track, var index: Int) {
    /** The track whose card the current drag offset belongs to. */
    var dragOwner = ""
    /** Set while a card has been thrown and the player has not moved on yet. */
    var thrownFor = ""
    var motion: Job? = null
}

/** How a new top card arrives: dealt in over the old one, or already in place after a throw. */
private class DeckChange(val outgoing: Track?, val from: Float, val thrown: Boolean) {
    val progress = Animatable(if (outgoing == null) 1f else 0f)
}

/**
 * The cover is the top card of a small deck. Dragging swings it off on an arc (it pivots
 * around a point below the card) while the next or previous cover rises from underneath.
 * Let go past a third of the width, or flick, and the card is thrown; the one underneath is
 * already in place, so nothing crossfades. Skips from the buttons deal the new card in from
 * the side it comes from. Every frame of this runs in the draw phase.
 */
@Composable
private fun CoverDeck(track: Track, index: Int, previousTrack: Track?, nextTrack: Track?, reduced: Boolean,
    canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    val drag = remember { mutableFloatStateOf(0f) }
    val memory = remember { DeckMemory(track, index) }
    // Worked out while composing, so the very first frame of a new track is already right:
    // a thrown card's offset never carries over, and a dealt card starts at the side.
    val arrival = remember(track.id) {
        val old = memory.track
        val forward = index >= memory.index
        val thrown = old.id != track.id && memory.thrownFor == old.id
        memory.track = track; memory.index = index; memory.thrownFor = ""
        if (thrown) memory.motion?.cancel()
        DeckChange(if (old.id == track.id || thrown || reduced) null else old, if (forward) 1f else -1f, thrown)
    }
    // The same recording can be restarted in a new queue. Keep its latest index and
    // metadata even when remember(track.id) quite correctly retains the current animation.
    SideEffect { memory.track = track; memory.index = index }
    val currentId by rememberUpdatedState(track.id)
    val scope = rememberCoroutineScope()
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val allowNext by rememberUpdatedState(canNext)
    val allowPrevious by rememberUpdatedState(canPrevious)
    val shape = LocalLook.current.cover
    val shadow = LocalLook.current.room
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val flick = with(LocalDensity.current) { 700.dp.toPx() }
        suspend fun slide(to: Float, ms: Int, easing: Easing) {
            animate(drag.floatValue, to, animationSpec = tween(if (reduced) 0 else ms, easing = easing)) { v, _ -> drag.floatValue = v }
        }
        LaunchedEffect(arrival) {
            if (memory.dragOwner != track.id) drag.floatValue = 0f
            arrival.progress.animateTo(1f, tween(if (reduced) 0 else 300, easing = EmphasizedDecelerate))
        }
        val owner = track.id
        // The drag offset, but only while it belongs to the card on top.
        fun offset() = if (memory.dragOwner == owner) drag.floatValue else 0f
        // How far the drag has gone, 0..1, towards whichever neighbour it reveals.
        fun reveal(towardNext: Boolean): Float {
            val x = offset()
            return if ((x < 0) == towardNext && x != 0f) (abs(x) / width).coerceIn(0f, 1f) else 0f
        }
        // Neighbours wait underneath, invisible until a drag reveals one of them.
        for ((shown, towardNext) in listOf(previousTrack to false, nextTrack to true)) {
            if (shown == null) continue
            key(towardNext, shown.id) {
                Artwork(shown, Modifier.matchParentSize().graphicsLayer {
                    val p = reveal(towardNext)
                    alpha = if (p == 0f) 0f else .4f + .6f * p
                    scaleX = .9f + .1f * p; scaleY = scaleX
                    this.shape = shape; clip = true
                }, size = 400.dp)
            }
        }
        arrival.outgoing?.let { leaving ->
            Artwork(leaving, Modifier.matchParentSize().graphicsLayer {
                val d = arrival.progress.value
                alpha = 1f - d; scaleX = 1f - .08f * d; scaleY = scaleX
                translationX = -arrival.from * width * .12f * d
                this.shape = shape; clip = true
            }, size = 400.dp)
        }
        Artwork(track, Modifier.matchParentSize()
            .graphicsLayer {
                val d = arrival.progress.value
                val x = offset() + arrival.from * width * .3f * (1f - d)
                translationX = x
                rotationZ = x / width * 10f
                transformOrigin = TransformOrigin(.5f, 1.6f)
                alpha = if (d < 1f) .25f + .75f * d else 1f
                // A soft shadow lifts the top card off the ground (Classic's square cover sits flat).
                if (shadow) { shadowElevation = 14.dp.toPx(); ambientShadowColor = Color.Black; spotShadowColor = Color.Black }
                this.shape = shape; clip = true
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        memory.motion?.cancel(); tracker.resetTracking()
                        if (memory.dragOwner != currentId) { memory.dragOwner = currentId; drag.floatValue = 0f }
                    },
                    onDragEnd = {
                        val x = drag.floatValue
                        val v = tracker.calculateVelocity().x
                        val goNext = allowNext && (x < -width / 3 || (v < -flick && x < 0))
                        val goPrevious = allowPrevious && (x > width / 3 || (v > flick && x > 0))
                        memory.motion = scope.launch {
                            if (goNext || goPrevious) {
                                val thrown = currentId
                                memory.thrownFor = thrown
                                slide(if (goNext) -width * 1.3f else width * 1.3f, 160, EmphasizedAccelerate)
                                if (goNext) next() else previous()
                                // If the player never moves on, bring the card back.
                                delay(1200)
                                if (memory.thrownFor == thrown) { memory.thrownFor = ""; slide(0f, SwapMs, EmphasizedDecelerate) }
                            } else slide(0f, SwapMs, EmphasizedDecelerate)
                        }
                    },
                    onDragCancel = { memory.motion = scope.launch { slide(0f, SwapMs, EmphasizedDecelerate) } },
                ) { change, delta ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val x = drag.floatValue
                    // Resist past the edge when there is nothing to swipe to.
                    val blocked = (x + delta < 0 && !allowNext) || (x + delta > 0 && !allowPrevious)
                    drag.floatValue = x + if (blocked) delta * .2f else delta
                }
            }
            .semantics { contentDescription = "Cover for ${track.title}. Swipe left for next, right for previous." },
            size = 400.dp)
    }
}

/**
 * Playback position that keeps moving between the player's updates. It is returned as a
 * State so callers read it while drawing; reading it in composition would recompose every frame.
 */
@Composable
private fun rememberSmoothPosition(position: Long, duration: Long, animate: Boolean): State<Long> {
    val shown = remember { mutableLongStateOf(position) }
    LaunchedEffect(position, duration, animate) {
        val drift = position - shown.longValue
        shown.longValue = if (!animate || abs(drift) > 750) position else shown.longValue + drift / 3
        if (!animate) return@LaunchedEffect
        val base = shown.longValue
        val limit = if (duration > 0) duration else Long.MAX_VALUE
        val start = withFrameNanos { it }
        // Ten updates a second, not one per frame: the line moves well under a pixel per tick, and
        // the window can idle between ticks instead of redrawing at 60–120 Hz.
        while (true) {
            withFrameNanos { now -> shown.longValue = (base + (now - start) / 1_000_000).coerceAtMost(limit) }
            delay(ClockTickMs)
        }
    }
    return shown
}

/** How often the playback clock moves the progress lines while a song plays. */
internal const val ClockTickMs = 100L

/**
 * A drawn seek line. The played part and the thumb are painted from the smooth position in
 * the draw phase; the time labels only recompose when the second changes. Touching the line
 * thickens it, and you can tap or drag anywhere on it to seek.
 */
@Composable
private fun ProgressLine(position: Long, duration: Long, playing: Boolean, reduced: Boolean, badge: String?, onSeek: (Long) -> Unit) {
    val drag = remember { mutableStateOf<Float?>(null) }
    val smooth = rememberSmoothPosition(position, duration, playing && !reduced)
    val fraction = { drag.value ?: if (duration > 0) (smooth.value.toFloat() / duration).coerceIn(0f, 1f) else 0f }
    // Tenths of a second: enough for the time label and for accessibility, and it limits
    // recomposition to ten times a second while the line itself is drawn every frame.
    val tenths by remember(duration) { derivedStateOf { (fraction() * duration).toLong() / 100 } }
    val seconds = tenths / 10
    val active by remember { derivedStateOf { drag.value != null } }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(active) { grow.animateTo(if (active) 1f else 0f, tween(if (reduced) 0 else QuickMs, easing = EmphasizedDecelerate)) }
    val seek by rememberUpdatedState(onSeek)
    val scope = rememberCoroutineScope()
    val classic = LocalLook.current.classic
    val soft = LocalLook.current.soft
    // Classic's line is ink on a square track; the Listening room's is the accent, rounded, with a thumb.
    // The accent as a mark: lifted or deepened until it stands out 3:1 from the page (a fixed
    // orange on paper was 2.7:1).
    val ground = MaterialTheme.colorScheme.background
    val raw = LocalAccent.current.accent
    val accent = if (classic) MaterialTheme.colorScheme.onBackground else remember(raw, ground) { markOn(raw, ground, Color.Unspecified).takeIf { it != Color.Unspecified } ?: raw }
    val line = MaterialTheme.colorScheme.outline
    fun release() {
        val target = drag.value ?: return
        seek((target * duration).toLong())
        // Hold the line where it was dropped until the player reports the new position.
        scope.launch { delay(450); drag.value = null }
    }
    Canvas(
        Modifier.fillMaxWidth().height(32.dp).graphicsLayer()
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { drag.value = (it.x / size.width).coerceIn(0f, 1f); release() }
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { drag.value = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { release() }, onDragCancel = { drag.value = null },
                ) { change, _ -> change.consume(); drag.value = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (tenths * 100f / duration).coerceIn(0f, 1f) else 0f, 0f..1f)
                if (duration <= 0) disabled()
                setProgress { value -> if (duration > 0) seek((value.coerceIn(0f, 1f) * duration).toLong()); duration > 0 }
            },
    ) {
        val g = grow.value
        if (classic) {
            val h = (8.dp + 4.dp * g).toPx()
            val y = center.y - h / 2
            drawRect(line, Offset(0f, y), Size(size.width, h))
            val played = size.width * fraction()
            drawRect(accent, Offset(0f, y), Size(played, h))
            // A square notch marks the playhead while it is being dragged.
            if (g > 0f) {
                val w = 4.dp.toPx()
                drawRect(accent, Offset((played - w / 2).coerceIn(0f, size.width - w), y - 4.dp.toPx() * g), Size(w, h + 8.dp.toPx() * g))
            }
            return@Canvas
        }
        if (soft) {
            // Soft, as in 0.3.31: a thin raspberry line with a round thumb.
            val h = (4.dp + 2.dp * g).toPx()
            val y = center.y - h / 2
            drawRoundRect(line, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            val played = size.width * fraction()
            drawRoundRect(accent, Offset(0f, y), Size(played.coerceAtLeast(h), h), CornerRadius(h / 2))
            val r = (6.dp + 3.dp * g).toPx()
            drawCircle(accent, r, Offset(played.coerceIn(r, size.width - r), center.y))
            return@Canvas
        }
        val h = (6.dp + 2.dp * g).toPx()
        val y = center.y - h / 2
        val round = CornerRadius(h / 2)
        drawRoundRect(line, Offset(0f, y), Size(size.width, h), round)
        // The round thumb stays inside the line at both ends.
        val r = (7.dp + 2.dp * g).toPx()
        val played = size.width * fraction()
        if (played > 0f) drawRoundRect(accent, Offset(0f, y), Size(played, h), round)
        drawCircle(accent, r, Offset(played.coerceIn(r, size.width - r), center.y))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // Soft's times are small DM Sans in the muted colour; the others use DM Mono.
        val times = if (soft) TextStyle(fontSize = 11.sp, letterSpacing = .5.sp) else MonoSmall
        val timeColour = if (soft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(clock(seconds * 1000), style = times, color = timeColour)
        if (badge != null) Text(badge, style = MonoSmall, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = "Audio format $badge" })
        Text(if (duration > 0) clock(duration) else "—:—", style = times, color = timeColour)
    }
}


// ---------------------------------------------------------------------------------------
// Settings: one scrolling page of grouped cards with a shared audio-import entry.
// ---------------------------------------------------------------------------------------

private data class SettingsSection(val label: String, val icon: ImageVector)
private val settingsSections = listOf(
    SettingsSection("Account", Icons.Outlined.AccountCircle),
    SettingsSection("Playback", Glyph.MusicNote),
    SettingsSection("Sources", Glyph.AudioFile),
    SettingsSection("Appearance", Glyph.Contrast),
    SettingsSection("Privacy", Glyph.Shield),
    SettingsSection("About", Icons.Outlined.Info),
)

@Composable
private fun SettingsTabs(selected: Int, reduced: Boolean, onSelect: (Int) -> Unit) {
    val look = LocalLook.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        settingsSections.forEachIndexed { index, section ->
            val on = index == selected
            val fill by animateColorAsState(if (on) look.selectorThumb else Color.Transparent, tween(if (reduced) 0 else QuickMs), label = "settings tab fill")
            Box(
                Modifier.clip(look.button).background(fill).border(1.dp, if (on) look.selectorThumb else look.line, look.button)
                    .clickable(role = Role.Tab) { onSelect(index) }.padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { this.selected = on },
            ) { Text(section.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) look.onSelectorThumb else look.text) }
        }
    }
}

@Composable
private fun SettingsScreen(state: MusicState, onTheme: (String) -> Unit, onAccent: (AccentChoice) -> Unit, onLocal: () -> Unit,
    onChooseMasters: () -> Unit, onClearMasters: () -> Unit, onClearHistory: () -> Unit,
    onConnect: () -> Unit, onWebSignIn: () -> Unit, onDisconnect: () -> Unit, onCancelConnect: () -> Unit,
    onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit, onCancelGoogle: () -> Unit,
    onCheckUpdates: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: () -> Unit,
    onAutoplay: (Boolean) -> Unit, onTrackHistory: (Boolean) -> Unit,
    onConnectSource: (MusicProvider, SourceProtocol, String, String) -> Unit, onRemoveSource: (String) -> Unit,
    onConnectTidal: () -> Unit, onDisconnectTidal: () -> Unit, onCancelTidal: () -> Unit,
    onConnectApple: () -> Unit, onNativeApple: () -> Unit, onDisconnectApple: () -> Unit, onCancelApple: () -> Unit,
    onDeveloperOptions: (Boolean) -> Unit,
    onRecommendationEngine: (labs.rtensor.music.recommendations.RecommendationEngine) -> Unit,
    onConnectSpotify: () -> Unit, onDisconnectSpotify: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showLicences by rememberSaveable { mutableStateOf(false) }
    var showChangelog by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val notices = remember { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
    val scroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    var showImport by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The Listening room names the page once, in the header; Classic and Soft keep their headline.
        if (!LocalLook.current.room) PageTitle("Settings")

        SettingsTabs(tab, state.reducedMotion) { tab = it }
        AnimatedContent(targetState = tab, label = "settings tab", transitionSpec = {
            if (state.reducedMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
            else {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(EnterMs, 60)) + slideInHorizontally(tween(EnterMs, easing = EmphasizedDecelerate)) { it / 8 * dir }) togetherWith
                    (fadeOut(tween(100)) + slideOutHorizontally(tween(QuickMs, easing = EmphasizedAccelerate)) { -it / 10 * dir })
            }.using(SizeTransform(clip = false))
        }) { t -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t == 0) {
        AccountCard(state, onGoogleSignIn, onGoogleSignOut, onCancelGoogle, onConnect, onWebSignIn, onDisconnect, onCancelConnect)

        }
        if (t == 1) {
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Recommendation source", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Choose who recommends your next songs. Audio uses your existing playback sources.", fontSize = 12.sp, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labs.rtensor.music.recommendations.RecommendationEngine.entries.forEach { engine ->
                        FilterChip(selected = state.recommendationEngine == engine, onClick = {
                            if (engine == labs.rtensor.music.recommendations.RecommendationEngine.SPOTIFY && !state.spotifyAccount.connected) onConnectSpotify()
                            else onRecommendationEngine(engine)
                        }, label = { Text(engine.label) }, modifier = Modifier.semantics { contentDescription = "${engine.label} recommendations" })
                    }
                }
                if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.SPOTIFY) Text("Spotify discovery is experimental.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                if (state.spotifyAccount.connecting) Text("Connecting Spotify…", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                if (state.spotifyAccount.connected) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Spotify · ${state.spotifyAccount.name}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDisconnectSpotify) { Text("Disconnect Spotify", fontSize = 12.sp) }
                }
                state.spotifyAccount.error?.let { Text(it, fontSize = 12.sp, color = scheme.onSurfaceVariant) }
            }
            CardDivider()
            SettingItem("Autoplay radio", "When the queue ends, keep playing similar songs", Glyph.Sparkle, onClick = { onAutoplay(!state.autoplay) }) {
                Switch(checked = state.autoplay, onCheckedChange = onAutoplay, modifier = Modifier.semantics { contentDescription = "Autoplay radio" })
            }

        }

        }
        if (t == 2) {
        SettingsCard {
            val count = state.playbackSources.size
            // A saved server is only a candidate; each song is checked before anything plays from it.
            SettingItem("Playback sources",
                if (state.appleAccount.connected) "Apple Music signed in${if (state.tidalAccount.connected) " · TIDAL" else ""}"
                else if (state.tidalAccount.connected) "TIDAL signed in${if (count > 0) " · $count saved ${if (count == 1) "server" else "servers"}" else ""}"
                else if (count == 0) "Sign in to Apple Music, TIDAL or connect a music source"
                else "$count saved ${if (count == 1) "server" else "servers"} · checked per song before it plays",
                Glyph.MusicNote, onClick = { showSources = !showSources }, expanded = showSources)
            if (showSources) PlaybackSourcesEditor(state, { showSources = false }, onConnectSource, onRemoveSource,
                onConnectTidal, onDisconnectTidal, onCancelTidal, onConnectApple, onNativeApple, onDisconnectApple, onCancelApple)
            CardDivider()
            SettingItem("Import audio", if (state.mastersBusy) state.mastersSummary else "Stereo, lossless and Atmos files", Icons.Outlined.Add, onClick = { showImport = true })
            state.importMessage?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp)) }
        }

        }
        if (t == 3) {
        SettingsCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Theme", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                ThemeSelector(state.theme, state.reducedMotion, onTheme)
                Spacer(Modifier.height(18.dp))
                Text("Accent colour", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (state.look != LookChoice.ROOM && state.look != LookChoice.HUM) {
                    // Classic always uses the Signal orange and Soft its raspberry, so there is nothing to choose here.
                    Text(if (state.look == LookChoice.CLASSIC) "Classic always uses Signal orange. Choose Listening room to pick an accent colour or take it from the cover."
                        else "Soft always uses its raspberry. Choose Listening room to pick an accent colour or take it from the cover.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(if (state.accent == AccentChoice.COVER) "Taken from the artwork of the song that is playing. Berry is used when there is none."
                        else "The one strong colour on Play, highlights and the dock.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    AccentSelector(state.accent, onAccent)
                }
            }
        }

        }
        if (t == 4) {
        SettingsCard {
            SettingItem("Keep listening history", "Powers the History tab and “Mixed for you”. Stays on this device.", Glyph.History, onClick = { onTrackHistory(!state.trackHistory) }) {
                Switch(checked = state.trackHistory, onCheckedChange = onTrackHistory, modifier = Modifier.semantics { contentDescription = "Keep listening history" })
            }
            CardDivider()
            val hasHistory = state.history.isNotEmpty() || state.playEvents.isNotEmpty()
            val recordings = remember(state.playEvents) { ListeningHistory.latestPerSong(state.playEvents).size }
            val recent = remember(state.history) { state.history.distinctBy { it.libraryKey() }.size }
            val count = if (recordings > 0) "$recordings ${if (recordings == 1) "recording" else "recordings"} in History"
                else "$recent recent ${if (recent == 1) "recording" else "recordings"}"
            SettingItem("Clear listening history", if (!hasHistory) "Nothing to clear" else count, Icons.Outlined.Delete,
                onClick = if (hasHistory) ({ confirmClear = true }) else null)
            CardDivider(start = 16.dp)
            Text("Saved recordings, history and encrypted account sessions stay on this device. Connected music servers receive song searches and playback requests. The selected recommendation provider receives discovery requests; YouTube receives recording-match searches. GitHub receives update checks.",
                fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }

        }
        if (t == 5) {
        SettingsCard {
            SettingItem("Music ${BuildConfig.VERSION_NAME}", "Preview channel · GitHub Releases", Icons.Outlined.Refresh)
            Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.update.message.isNotBlank()) Text(state.update.message, fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant)
                if (state.update.downloading) {
                    val progress by animateFloatAsState(state.update.progress / 100f, tween(if (state.reducedMotion) 0 else EnterMs), label = "update progress")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                    Text("${state.update.progress}%", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdates, enabled = !state.update.checking && !state.update.downloading, shape = ButtonShape) { Text("Check updates", fontSize = 14.sp) }
                    if (state.update.apkPath != null) Button(onClick = onInstallUpdate, shape = ButtonShape) { Text("Install update", fontSize = 14.sp) }
                    else if (state.update.release != null) Button(onClick = onDownloadUpdate, enabled = !state.update.downloading && !state.update.checking, shape = ButtonShape) { Text("Download", fontSize = 14.sp) }
                }
            }
            CardDivider()
            SettingItem("What’s new", "Changes in each release", Icons.Outlined.Info, onClick = { showChangelog = true })
            CardDivider()
            SettingItem("Open-source licences", "Libraries and fonts used by Music", Icons.Outlined.Info, onClick = { showLicences = true })
            CardDivider()
            SettingItem("Developer options", "Channel monitor, and live speaker levels on Home and in the player", Glyph.ChannelMonitor,
                onClick = { onDeveloperOptions(!state.developerOptions) }) {
                Switch(checked = state.developerOptions, onCheckedChange = onDeveloperOptions,
                    modifier = Modifier.semantics { contentDescription = "Developer options" })
            }
        }
        }
        } }
        Text("Hum · RTensorLabs · ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp))
    }
    if (showImport) AlertDialog(
        onDismissRequest = { showImport = false },
        title = { Text("Import audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose audio from your device.")
                Button(onClick = { showImport = false; onLocal() }, modifier = Modifier.fillMaxWidth(), shape = ButtonShape) { Text("Choose file") }
                OutlinedButton(onClick = { showImport = false; onChooseMasters() }, enabled = !state.mastersBusy,
                    modifier = Modifier.fillMaxWidth(), shape = ButtonShape) { Text("Choose folder") }
                if (state.mastersSummary.isNotBlank()) {
                    Text(state.mastersSummary, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    if (!state.mastersBusy) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showImport = false; onChooseMasters() }) { Text("Rescan folder") }
                            TextButton(onClick = { onClearMasters(); showImport = false }) { Text("Forget folder") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showImport = false }) { Text("Close") } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("Your saved recordings will stay here.") },
        confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } },
    )
    if (showChangelog) ChangelogDialog(onDismiss = { showChangelog = false })
    if (showLicences) AlertDialog(
        onDismissRequest = { showLicences = false },
        title = { Text("Open-source licences") },
        text = {
            val fullNotices = remember(notices) {
                if (!BuildConfig.APPLE_RUNTIME_INCLUDED) notices else notices + "\n\nApple runtime acknowledgements\n\n" +
                    android.text.Html.fromHtml(context.assets.open("apple-runtime/acknowledgements.html").bufferedReader().use { it.readText() }, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            }
            Text(fullNotices, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } },
    )
}

/**
 * One account card for both sign-ins. Choosing a Google account immediately continues to
 * YouTube Music with that email preselected, and signing out clears both together.
 */
@Composable
private fun AccountCard(state: MusicState, onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit, onCancelGoogle: () -> Unit,
    onConnect: () -> Unit, onWebSignIn: () -> Unit, onDisconnect: () -> Unit, onCancelConnect: () -> Unit) {
    val google = state.googleProfile
    val youtube = state.account
    val displayName = google?.name?.ifBlank { google.email } ?: youtube.name.takeIf { youtube.connected && it.isNotBlank() } ?: "Not signed in"
    val detail = google?.email ?: if (youtube.connected) "YouTube Music account" else "Choose an account, then connect YouTube Music."
    SettingsCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(if (google != null || youtube.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                if (LocalLook.current.classic) RectangleShape else CircleShape), contentAlignment = Alignment.Center) {
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
        AnimatedVisibility(state.googleBusy || youtube.connecting,
            enter = if (state.reducedMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (state.reducedMotion) ExitTransition.None else shrinkVertically() + fadeOut()) {
            LoadingLine(true, state.reducedMotion)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (state.googleError ?: youtube.error)?.let { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.primary) }
            val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            when {
                state.googleBusy -> OutlinedButton(onClick = onCancelGoogle, modifier = wide, shape = ButtonShape) { Text("Cancel Google sign-in", fontSize = 15.sp) }
                youtube.connecting -> {
                    Text("Finish sign-in on YouTube’s page.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCancelConnect, modifier = wide, shape = ButtonShape) { Text("Cancel sign-in", fontSize = 15.sp) }
                }
                google == null && !youtube.connected -> {
                    Button(onClick = onGoogleSignIn, modifier = wide, shape = ButtonShape) { Text("Sign in with Google", fontSize = 15.sp) }
                    Text("Choose a Gmail account on this phone to connect YouTube Music. Google may ask you to confirm sign-in.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onWebSignIn) { Text("Sign in using browser", fontSize = 15.sp) }
                }
                google != null && !youtube.connected -> {
                    Button(onClick = onConnect, modifier = wide, shape = ButtonShape) { Text("Connect YouTube Music", fontSize = 15.sp) }
                    Text("Finishes syncing ${google.email}. You’ll return here automatically.", fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow {
                        TextButton(onClick = onGoogleSignIn) { Text("Change Google account", fontSize = 15.sp) }
                        TextButton(onClick = onGoogleSignOut) { Text("Sign out", fontSize = 15.sp) }
                    }
                }
                else -> {
                    OutlinedButton(onClick = if (google != null) onGoogleSignIn else onConnect, modifier = wide, shape = ButtonShape) {
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
        val tint by animateColorAsState(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, tween(if (LocalReducedMotion.current) 0 else 180), label = "status")
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }
}

/**
 * A segmented control whose highlight slides between options. The highlight is painted in
 * the draw phase from one spring, so changing theme never re-measures the row.
 */
@Composable
private fun ThemeSelector(theme: String, reduced: Boolean, onTheme: (String) -> Unit) =
    SlidingSelector(listOf("system" to "Device", "light" to "Light", "dark" to "Dark"), theme, reduced, { "$it theme" }, Role.Tab, onTheme)

@Composable
private fun SlidingSelector(options: List<Pair<String, String>>, chosen: String, reduced: Boolean, describe: (String) -> String,
    role: Role, onChoose: (String) -> Unit) {
    val index = options.indexOfFirst { it.first == chosen }.coerceAtLeast(0)
    val slot = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, reduced) { if (reduced) slot.snapTo(index.toFloat()) else slot.animateTo(index.toFloat(), IndicatorSpring) }
    val look = LocalLook.current
    val classic = look.classic
    val shape = when (look.choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(14.dp); LookChoice.ROOM, LookChoice.HUM -> RoundedCornerShape(percent = 50) }
    val thumbShape = when (look.choice) { LookChoice.CLASSIC -> RectangleShape; LookChoice.SOFT -> RoundedCornerShape(10.dp); LookChoice.ROOM, LookChoice.HUM -> RoundedCornerShape(percent = 50) }
    // The track is set apart from its card by a muted outline (3:1 or more against the card), and
    // the chosen option is a solid thumb in the text colour, so the choice never relies on a tint.
    val thumb = look.selectorThumb
    Row(
        // At least 48dp, and taller when large text needs it, so labels never clip.
        Modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min).clip(shape).background(look.selectorTrack).border(1.dp, look.selectorEdge, shape).selectableGroup()
            .drawBehind {
                val pad = 4.dp.toPx()
                val w = (size.width - pad * 2) / options.size
                val r = CornerRadius(when (look.choice) { LookChoice.CLASSIC -> 0f; LookChoice.SOFT -> 10.dp.toPx(); LookChoice.ROOM, LookChoice.HUM -> (size.height - pad * 2) / 2 })
                drawRoundRect(thumb, Offset(pad + w * slot.value, pad), Size(w, size.height - pad * 2), r)
            }
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, (id, name) ->
            val selected = i == index
            val fg by animateColorAsState(if (selected) look.onSelectorThumb else look.muted, tween(if (reduced) 0 else QuickMs), label = "selector fg")
            Box(Modifier.weight(1f).fillMaxHeight().clip(thumbShape).clickable { onChoose(id) }
                .semantics { contentDescription = describe(name); this.selected = selected; this.role = role },
                contentAlignment = Alignment.Center) {
                // Three options (the looks) get a slightly smaller label so "Listening room" fits on a phone.
                Text(name, fontSize = if (options.size > 2 && options.any { it.second.length > 10 }) 12.sp else 14.sp, color = fg,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, letterSpacing = 0.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * Labelled swatches, one radio target each (48dp wide or more). A ring and a check mark the
 * chosen one, so the choice never depends on colour alone. The cover swatch shows the colour
 * taken from the current artwork once it is in use, and the fixed hues before that.
 */
@Composable
private fun AccentSelector(selected: AccentChoice, onAccent: (AccentChoice) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val current = LocalAccent.current.accent
    val swatch = if (LocalLook.current.classic) RectangleShape else CircleShape
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AccentChoice.entries.forEach { choice ->
            val chosen = choice == selected
            val colour = (if (choice == AccentChoice.SIGNAL && LocalLook.current.hum) (if (dark) HumPink else HumPinkDay) else fixedAccent(choice, dark)) ?: current.takeIf { chosen }
            Column(
                Modifier.width(48.dp).heightIn(min = 48.dp)
                    .selectable(chosen, role = Role.RadioButton) { onAccent(choice) }
                    .semantics { contentDescription = "${choice.label} accent colour" }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(44.dp).border(2.dp, if (chosen) scheme.onBackground else Color.Transparent, swatch).padding(5.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().clip(swatch).border(.5.dp, scheme.outline, swatch).drawBehind {
                        if (colour != null) drawRect(colour)
                        else {
                            // Not chosen yet: the cover's colour is unknown, so show that it varies.
                            val half = Size(size.width / 2, size.height / 2)
                            drawRect(SignalAccent, Offset.Zero, half); drawRect(Cobalt, Offset(half.width, 0f), half)
                            drawRect(Moss, Offset(0f, half.height), half); drawRect(Oxblood, Offset(half.width, half.height), half)
                        }
                    }, contentAlignment = Alignment.Center) {
                        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = onAccentFor(colour ?: SignalAccent))
                    }
                }
                Text(choice.label, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {})
            }
        }
    }
}

/** Sits close to its own card and far from the one above (proximity groups them). */
@Composable
private fun SettingsGroupHeader(section: SettingsSection, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 18.dp, start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(section.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        if (!LocalLook.current.room) Text(section.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else PosterText(section.label, SectionStyle.copy(fontSize = 18.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val look = LocalLook.current
    // Classic: a hairline-bordered square card. Listening room: a rounded panel.
    Surface(shape = look.card, color = look.cardFill, contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (look.room) null else BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
        // Critically damped: the card grows to fit an opened editor without overshooting.
        Column(Modifier.padding(vertical = 4.dp).animateContentSize(animationSpec = if (LocalReducedMotion.current) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)), content = content)
    }
}

@Composable
private fun CardDivider(start: Dp = 66.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
}

/** A 56dp-minimum list row. [expanded] turns the chevron to show an inline panel is open. */
@Composable
private fun SettingItem(title: String, detail: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null,
    expanded: Boolean? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        else if (onClick != null) {
            val turn by animateFloatAsState(if (expanded == true) 90f else 0f, if (LocalReducedMotion.current) snap<Float>() else IndicatorSpring, label = "chevron")
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp).graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    val accent = LocalAccent.current
    if (LocalLook.current.soft) {
        // Soft: a pink tile with a raspberry icon.
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        }
        return
    }
    Box(Modifier.size(36.dp).background(accent.accent, LocalLook.current.tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = accent.onAccent)
    }
}

// ---------------------------------------------------------------------------------------
// Mini player
// ---------------------------------------------------------------------------------------

/**
 * The mini player is the top half of the bottom dock. Its progress is a hairline painted
 * along its upper edge. Swipe sideways to skip, or up to open the player. A new track rolls
 * in like a ticker: up when moving forward, down when going back.
 */
@Composable
private fun MiniPlayer(state: MusicState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    playbackPosition: StateFlow<Long>?) {
    val track = state.current ?: return
    val reduced = state.reducedMotion
    val loading = state.resolving || state.buffering
    val i = state.queueIndex
    val hasNext = i >= 0 && i < state.queue.lastIndex
    val dx = remember { mutableFloatStateOf(0f) }
    val dy = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val allowNext by rememberUpdatedState(hasNext)
    val density = LocalDensity.current
    fun settle() {
        val spec = tween<Float>(if (reduced) 0 else SwapMs, easing = EmphasizedDecelerate)
        scope.launch { animate(dx.floatValue, 0f, animationSpec = spec) { v, _ -> dx.floatValue = v } }
        scope.launch { animate(dy.floatValue, 0f, animationSpec = spec) { v, _ -> dy.floatValue = v } }
    }
    Box(
        Modifier.fillMaxWidth()
            .songClickable(track, onClick = onOpen)
            .pointerInput(Unit) {
                val trigger = with(density) { 56.dp.toPx() }
                detectDragGestures(
                    onDragEnd = {
                        val x = dx.floatValue; val y = dy.floatValue
                        when {
                            -y > trigger && -y > abs(x) -> open()
                            x < -trigger && allowNext -> next()
                            x > trigger -> previous()
                        }
                        settle()
                    },
                    onDragCancel = { settle() },
                ) { change, drag ->
                    change.consume()
                    dx.floatValue += drag.x
                    dy.floatValue += drag.y
                }
            },
    ) {
        MiniPlaybackProgress(playbackPosition, state.positionMs, state.durationMs, state.playing && !reduced, Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp)
                .graphicsLayer {
                    translationX = dx.floatValue * .35f
                    translationY = dy.floatValue.coerceAtMost(0f) * .3f
                    alpha = 1f - (abs(dx.floatValue) / size.width).coerceIn(0f, .5f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = track to i, contentKey = { it.first.id }, label = "mini track", modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.second >= initialState.second
                    if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                    else ((fadeIn(tween(SwapMs, 40)) + slideInVertically(tween(240, easing = EmphasizedDecelerate)) { if (forward) it / 2 else -it / 2 }) togetherWith
                        (fadeOut(tween(100)) + slideOutVertically(tween(160, easing = EmphasizedAccelerate)) { if (forward) -it / 3 else it / 3 })).using(SizeTransform(clip = true))
                },
            ) { (shown, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val look = LocalLook.current
                    val dockText = if (look.hum) look.text else look.stageText
                    val dockMuted = if (look.hum) look.muted else look.stageMuted
                    // The accent bar: square in Classic, a lit pill with a soft glow in the Listening room;
                    // Soft shows the artwork instead.
                    if (look.soft) Artwork(shown, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), size = 44.dp)
                    else if (look.classic) Box(Modifier.size(width = 8.dp, height = 40.dp).background(LocalAccent.current.onDock))
                    // No glow: the row clips it to a hard-edged rectangle.
                    else Box(Modifier.size(width = 8.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(LocalAccent.current.onDock))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        ScrollingMetadata(shown.title, if (look.soft) TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = dockText)
                            else DisplayStyle.copy(fontSize = 16.sp, lineHeight = 20.sp, color = dockText))
                        ScrollingMetadata(shown.artist, if (look.classic) MonoSmall.copy(lineHeight = 16.sp, color = PaperMuted)
                            else TextStyle(fontFamily = font, fontSize = if (look.soft) 12.sp else 13.sp, lineHeight = 16.sp, color = dockMuted), Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PlayPauseCircle(state.playing, loading, reduced, enabled = !state.resolving, onClick = onToggle)
            IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = LocalLook.current.let { if (it.hum) it.text else it.stageText }, disabledContentColor = LocalLook.current.let { if (it.hum) it.muted else it.stageMuted }.copy(alpha = .4f))) {
                Icon(Glyph.Next, "Next recording", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PlayPauseCircle(playing: Boolean, loading: Boolean, reduced: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val soft = LocalLook.current.soft
    val look = LocalLook.current
    val glyph = if (soft) MaterialTheme.colorScheme.onPrimary else if (look.hum) look.background else Ink
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .92f else 1f, if (reduced) snap<Float>() else PressSpring, label = "play press")
    Box(
        Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(if (soft) CircleShape else ButtonShape).background(if (soft) MaterialTheme.colorScheme.primary else if (look.hum) look.text else look.stageText)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(loading, modifier = Modifier.size(24.dp), animationSpec = tween(if (reduced) 0 else 90), label = "mini play") { busy ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = glyph)
                else PlayPauseMorph(playing, glyph, 24.dp, reduced)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------

/** Clickable with a soft spring "press-in", used for cards and primary tap targets. */
internal fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .97f else 1f, if (reduced) snap<Float>() else PressSpring, label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

@Composable
internal fun TrackRow(track: Track, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: (() -> Unit)?, modifier: Modifier = Modifier, target: SongMenuTarget = SongMenuTarget(track), singleLine: Boolean = false) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(LocalLook.current.art).songClickable(track, target, onPlay).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(LocalLook.current.art), size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val playingColour = playingColour()
                    val titleColor by animateColorAsState(if (current) playingColour else MaterialTheme.colorScheme.onSurface, tween(if (LocalReducedMotion.current) 0 else QuickMs), label = "row title")
                    Text(track.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = titleColor, maxLines = if (singleLine) 1 else 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 3.dp)) {
                        if (current) PlayingTag(playingColour)
                        Text(track.artist, Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (track.durationMs > 0) Text(clock(track.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            SongMoreButton(track, target)
            if (onSave != null) IconButton(onClick = onSave) {
                val tint by animateColorAsState(if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(if (LocalReducedMotion.current) 0 else QuickMs), label = "save tint")
                LikeBurst(saved, LocalReducedMotion.current, MaterialTheme.colorScheme.primary) {
                    Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved && track.source == labs.rtensor.music.data.Source.LOCAL) "Remove ${track.title} from app" else if (saved) "Unsave ${track.title}" else "Save ${track.title}", Modifier.size(20.dp), tint = tint)
                }
            }
        }
    }
}

/**
 * Requests a rendition sized for the slot (a 48dp row downloads a ~120px image, not a
 * 1200px cover), shares it through the memory and disk caches, and falls back to a rendition
 * every upload has. A flat colour stands in while loading, so fast scrolling never flashes art.
 */
@Composable
internal fun Artwork(track: Track?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val source = track?.artwork?.takeIf { it.isNotBlank() }
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    var attempt by remember(source) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val url = source?.let { when (attempt) { 0 -> ArtworkUrl.sized(it, px); 1 -> ArtworkUrl.safe(it); else -> it } }
    val request = remember(context, url, px, reduced) {
        ImageRequest.Builder(context).data(url).size(px.coerceAtMost(1200)).crossfade(if (reduced) 0 else 160)
            .memoryCacheKey(url).diskCacheKey(url).build()
    }
    val tone = MaterialTheme.colorScheme.surfaceVariant
    val placeholder = remember(tone) { ColorPainter(tone) }
    AsyncImage(
        model = request, contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        onError = { if (attempt < 2) attempt++ },
        placeholder = placeholder, error = placeholder, fallback = placeholder,
    )
}

/** A square, 2dp-bordered transport control. The label is its accessible name. */
@Composable
private fun LabelledControl(label: String, image: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, highlighted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val accent = LocalAccent.current
    val fill by animateColorAsState(if (highlighted) accent.accent else Color.Transparent, tween(if (reduced) 0 else 180), label = "control fill")
    val tint by animateColorAsState(if (highlighted) accent.onAccent else if (enabled) scheme.onBackground else scheme.onSurfaceVariant.copy(alpha = .4f),
        tween(if (reduced) 0 else 180), label = "control tint")
    Box(
        modifier.size(64.dp, 72.dp).clip(LocalLook.current.control).pressable(enabled = enabled, onClick = onClick).background(fill)
            .border(if (LocalLook.current.classic) 2.dp else 1.5.dp, if (enabled || highlighted) scheme.onBackground else scheme.outline, LocalLook.current.control)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(image, null, Modifier.size(24.dp), tint = tint) }
}

/** A page's headline: Classic's full-stop display line over a rule, or a Listening room poster title. */
@Composable
private fun PageTitle(text: String) {
    val look = LocalLook.current
    if (look.soft) {
        // Soft's headline, as in 0.3.31: medium DM Sans with a raspberry full stop. It reads as written.
        val title = buildAnnotatedString { append(text); withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append('.') } }
        Text(title, Modifier.clearAndSetSemantics { this.text = AnnotatedString(text); heading() }, fontFamily = SoftFont, fontSize = 30.sp,
            lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp)
    } else if (look.classic) Column(Modifier.fillMaxWidth().signalRule(bottom = true).padding(top = 8.dp, bottom = 16.dp)) {
        Text(look.headline(text), style = DisplayStyle, fontSize = 44.sp, lineHeight = 40.sp, letterSpacing = (-.02).em, modifier = Modifier.semantics { heading() })
    } else Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        PosterText(text, PosterStyle.copy(fontSize = 36.sp, lineHeight = 34.sp), heading = true)
    }
}

/** Listening room section titles: the condensed face in capitals. */
private val SectionStyle = PosterStyle.copy(fontSize = 24.sp, lineHeight = 25.sp)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    if (LocalLook.current.soft) Text(text, modifier.semantics { heading() }, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    else if (LocalLook.current.classic) Text(text, style = DisplayStyle, fontSize = 24.sp, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    else PosterText(text, SectionStyle, modifier, maxLines = 2)
}

/** A big display line inside a section: as written in Classic, in poster capitals otherwise. */
@Composable
private fun Headline(text: String, size: androidx.compose.ui.unit.TextUnit, height: androidx.compose.ui.unit.TextUnit, maxLines: Int = Int.MAX_VALUE) {
    if (LocalLook.current.soft) Text(text, fontFamily = SoftFont, fontWeight = FontWeight.Medium, fontSize = 27.sp, lineHeight = 31.sp, letterSpacing = (-1).sp, maxLines = maxLines)
    else if (LocalLook.current.classic) Text(text, style = DisplayStyle, fontSize = size, lineHeight = height, maxLines = maxLines)
    else PosterText(text, PosterStyle.copy(fontSize = size, lineHeight = height), maxLines = maxLines)
}

/**
 * Poster type: shown in capitals, but read by TalkBack (and found by tests) exactly as written,
 * so a screen reader never spells out a shouted title.
 */
@Composable
private fun PosterText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE, heading: Boolean = false) {
    val hum = LocalLook.current.hum
    val shown = remember(text, hum) { if (hum) text else text.uppercase(Locale.getDefault()) }
    Text(shown, modifier.clearAndSetSemantics { this.text = AnnotatedString(text); if (heading) heading() }, color = color,
        style = if (hum) style.copy(fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.025).em) else style,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    // Soft's caption is small spaced DM Sans; the others use DM Mono.
    if (LocalLook.current.soft) Text(text, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    else Text(text, style = MonoSmall, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun EmptyState(title: String, detail: String, image: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Text(detail, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp))
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onRetry: () -> Unit, diagnostic: String = "", videoId: String = "") {
    val context = LocalContext.current
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    // An error must not look like Play: a quiet panel (Soft keeps its pink container) with a
    // warning icon in the accent, ordinary text, and TalkBack announces it when it appears.
    val fill = if (look.soft) scheme.secondaryContainer else look.panel
    val mark = markOn(LocalAccent.current.accent, fill, scheme.onSurface)
    val headline = friendlyError(message)
    Surface(color = fill, contentColor = scheme.onSurface, shape = if (look.soft) RoundedCornerShape(14.dp) else look.card,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp)) {
            Icon(Icons.Outlined.Warning, "Error", Modifier.padding(top = 1.dp, end = 10.dp).size(20.dp), tint = mark)
            Column(Modifier.weight(1f)) {
                if (headline != null) Text(headline, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(message, fontSize = if (headline != null) 12.sp else 14.sp, lineHeight = if (headline != null) 17.sp else 20.sp,
                    color = if (headline != null) scheme.onSurfaceVariant else scheme.onSurface)
                val ink = ButtonDefaults.textButtonColors(contentColor = if (look.soft) scheme.primary else scheme.onSurface)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                    if (diagnostic.isNotBlank()) TextButton(onClick = {
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Playback details", "Music ${BuildConfig.VERSION_NAME} · $videoId\n$diagnostic"))
                    }, shape = ButtonShape, colors = ink) { Text("Copy details", fontSize = 13.sp) }
                    TextButton(onClick = onDismiss, shape = ButtonShape, colors = ink) { Text("Dismiss", fontSize = 13.sp) }
                    TextButton(onClick = onRetry, shape = ButtonShape, colors = ink) { Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * A plain-language first line for errors that only carry a status code. The original message
 * stays underneath, so nothing is hidden and "Copy details" still has the specifics.
 */
internal fun friendlyError(message: String): String? {
    val code = Regex("""HTTP (\d{3})""").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return when (code) {
        401, 403 -> "The music service wouldn't play this song"
        404, 410 -> "This song isn't available any more"
        429 -> "Too many requests for now; try again in a minute"
        in 500..599 -> "The music service is having trouble"
        else -> "This song couldn't be loaded"
    }
}

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
