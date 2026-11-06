package labs.rtensor.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import labs.rtensor.music.data.LyricLine
import labs.rtensor.music.data.SyncedLyricsSource
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.runtime.staticCompositionLocalOf
import labs.rtensor.music.data.TranslatedLyrics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import labs.rtensor.music.data.Lyrics
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track

/** Translates a song's lyrics to English: (track id, text). Provided by the app; the default does nothing. */
internal val LocalLyricsTranslate = staticCompositionLocalOf<suspend (String, String) -> TranslatedLyrics?> { { _, _ -> null } }

/** Time-synced lyrics for a song: (track). Provided by the app; the default has none. */
internal val LocalSyncedLyrics = staticCompositionLocalOf<suspend (Track) -> List<LyricLine>> { { emptyList() } }

/**
 * Lyrics that follow the music: the current line is large and bright, the rest sit back, and the list
 * glides to keep the current line in view. Tapping a line jumps the song there. Dragging the list
 * pauses the following until the next line begins.
 */
@Composable
private fun SyncedLyricsView(lines: List<LyricLine>, positions: StateFlow<Long>?, onSeek: (Long) -> Unit, reduced: Boolean) {
    // The live playback clock; only the current line is derived from it, so the list recomposes when a line changes, not on every tick.
    val clock = (positions ?: remember { MutableStateFlow(0L) }).collectAsState()
    val active by remember(lines) { derivedStateOf { SyncedLyricsSource.activeIndex(lines, clock.value) } }
    val list = rememberLazyListState()
    LaunchedEffect(active, lines) {
        if (active < 0 || list.isScrollInProgress) return@LaunchedEffect
        val offset = -(list.layoutInfo.viewportSize.height * .22f).toInt()
        if (reduced) list.scrollToItem(active, offset) else list.animateScrollToItem(active, offset)
    }
    LazyColumn(Modifier.fillMaxWidth().height(250.dp), state = list, contentPadding = PaddingValues(vertical = 60.dp)) {
        itemsIndexed(lines, key = { i, line -> "$i:${line.timeMs}" }) { i, line ->
            val distance = if (active < 0) 2 else kotlin.math.abs(i - active)
            val alpha by animateFloatAsState(if (distance == 0) 1f else if (distance == 1) .55f else .35f, tween(if (reduced) 0 else 320), label = "lyric-alpha")
            val scale by animateFloatAsState(if (distance == 0) 1f else .88f, tween(if (reduced) 0 else 320), label = "lyric-scale")
            Text(line.text, Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(line.timeMs) }.padding(vertical = 8.dp)
                .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) },
                fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data class Done(val lyrics: Lyrics) : LyricsLoad
    data object Failed : LyricsLoad
}

/**
 * The song's lyrics from YouTube Music, under the controls. Collapsed it is one quiet row; opening
 * it fetches the words once per song. Songs from other sources have none, so the row is not shown.
 */
@Composable
internal fun LyricsPanel(track: Track, onLyrics: suspend (Track) -> Lyrics?, reduced: Boolean, modifier: Modifier = Modifier,
    positions: StateFlow<Long>? = null, onSeek: (Long) -> Unit = {}) {
    if (track.source != Source.YOUTUBE) return
    var open by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(track.id) { mutableStateOf<LyricsLoad>(LyricsLoad.Loading) }
    LaunchedEffect(track.id, open, attempt) {
        if (!open) return@LaunchedEffect
        load = LyricsLoad.Loading
        load = try { LyricsLoad.Done(onLyrics(track) ?: Lyrics("", "")) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { LyricsLoad.Failed }
    }
    // Time-synced lines, when the open lyrics library has this song. They replace the plain text and follow the music.
    val loadSynced = LocalSyncedLyrics.current
    var synced by remember(track.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(track.id, open) {
        if (!open || synced.isNotEmpty()) return@LaunchedEffect
        synced = try { loadSynced(track) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().animateContentSize(tween(if (reduced) 0 else 240))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }
            .semantics { contentDescription = if (open) "Hide lyrics" else "Show lyrics" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lyrics", Modifier.semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = scheme.onSurfaceVariant)
        }
        if (open) when (val state = load) {
            LyricsLoad.Loading -> CircularProgressIndicator(Modifier.padding(vertical = 16.dp).size(22.dp), strokeWidth = 2.dp)
            LyricsLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn’t load lyrics.", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
            is LyricsLoad.Done -> if (state.lyrics.available || synced.isNotEmpty()) {
                // YouTube Music has one version, in the language the song was written in. English is a
                // translation, fetched only when asked for and remembered with the language it detected.
                val translate = LocalLyricsTranslate.current
                var english by rememberSaveable { mutableStateOf(false) }
                var translation by remember(track.id) { mutableStateOf<TranslatedLyrics?>(null) }
                var translating by remember(track.id) { mutableStateOf(false) }
                var translateFailed by remember(track.id) { mutableStateOf(false) }
                var tries by remember(track.id) { mutableIntStateOf(0) }
                val sourceText = if (synced.isNotEmpty()) synced.joinToString("\n") { it.text } else state.lyrics.text
                LaunchedEffect(track.id, english, tries, sourceText) {
                    if (!english) return@LaunchedEffect
                    translating = true; translateFailed = false
                    try { translation = translate(track.id + ":" + sourceText.hashCode(), sourceText) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { translateFailed = true }
                    translating = false
                }
                val original = translation?.sourceName?.takeIf { it.isNotBlank() }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!english, { english = false }, label = { Text(if (original != null && original != "English") "Original · $original" else "Original") })
                    FilterChip(english, { english = true }, label = { Text("English") })
                }
                val shown = if (english) translation?.text else state.lyrics.text
                // English lines keep the original's timing when the translation came back line for line.
                val englishLines = translation?.text?.lines()?.filter { it.isNotBlank() }
                val timed = if (synced.isEmpty()) emptyList() else if (!english) synced
                    else if (englishLines != null && englishLines.size == synced.size) synced.mapIndexed { i, line -> LyricLine(line.timeMs, englishLines[i]) } else emptyList()
                when {
                    timed.isNotEmpty() -> SyncedLyricsView(timed, positions, onSeek, reduced)
                    english && translating -> CircularProgressIndicator(Modifier.padding(vertical = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    english && translateFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn’t translate these lyrics.", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                        TextButton(onClick = { tries++ }) { Text("Try again") }
                    }
                    shown != null -> {
                        if (english && translation?.sourceLanguage == "en") Text("These lyrics are already in English.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Text(shown, Modifier.padding(top = 8.dp), fontSize = 17.sp, lineHeight = 27.sp, color = scheme.onBackground)
                    }
                }
                if (english && shown != null) Text("Translated by Google Translate", Modifier.padding(top = 12.dp), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                if (state.lyrics.source.isNotBlank()) Text(state.lyrics.source, Modifier.padding(top = 12.dp), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
            } else Text("No lyrics for this song.", Modifier.padding(vertical = 8.dp), fontSize = 14.sp, color = scheme.onSurfaceVariant)
        }
    }
}
