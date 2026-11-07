package labs.rtensor.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import labs.rtensor.music.MusicState

/** Browsing positions, including horizontal shelves and library tabs, survive opening the player. */
@Composable
internal fun RetainedBrowserScreen(holder: SaveableStateHolder, page: String, content: @Composable () -> Unit) {
    if (page in setOf("home", "explore", "library", "search", "collection", "provider-feed", "feed-section"))
        holder.SaveableStateProvider(page, content)
    else content()
}

/** Only composed near the list's end. One request per completed batch; errors require an explicit retry. */
@Composable
internal fun FeedContinuation(state: MusicState, active: Boolean, onMore: () -> Unit) {
    val load by rememberUpdatedState(onMore)
    LaunchedEffect(active, state.feedPage, state.feedHasMore, state.feedBusy, state.feedError) {
        if (active && state.feedHasMore && !state.feedBusy && state.feedError == null) {
            delay(300)
            load()
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            state.feedHasMore && state.feedError != null -> {
                Text(state.feedError, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onMore, enabled = !state.feedBusy) { Text("Retry loading more") }
            }
            state.feedHasMore || state.feedAppending -> HumSpinner(Modifier.size(22.dp))
            state.feedEnded -> Text("You’re caught up", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
