package labs.rtensor.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.MusicState
import labs.rtensor.music.data.Track

@Composable
internal fun SearchSuggestionList(state: MusicState, onSelect: (String) -> Unit, onPlay: (Track) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        if (state.suggestionsBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
        itemsIndexed(state.suggestions, key = { i, suggestion -> "$i:${suggestion.track?.libraryKey() ?: suggestion.text}" }) { _, suggestion ->
            val track = suggestion.track
            if (track != null) TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(), { onPlay(track) }, null)
            else TextButton(onClick = { onSelect(suggestion.text) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Text(suggestion.text, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        if (state.query.isNotBlank()) item {
            TextButton(onClick = { onSelect(state.query) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Search for “${state.query}”")
            }
        }
    }
}
