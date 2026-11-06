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
import androidx.compose.material3.Icon
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
internal fun LyricsPanel(track: Track, onLyrics: suspend (Track) -> Lyrics?, reduced: Boolean, modifier: Modifier = Modifier) {
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
            is LyricsLoad.Done -> if (state.lyrics.available) {
                Text(state.lyrics.text, Modifier.padding(top = 4.dp), fontSize = 17.sp, lineHeight = 27.sp, color = scheme.onBackground)
                if (state.lyrics.source.isNotBlank()) Text(state.lyrics.source, Modifier.padding(top = 12.dp), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
            } else Text("No lyrics for this song.", Modifier.padding(vertical = 8.dp), fontSize = 14.sp, color = scheme.onSurfaceVariant)
        }
    }
}
