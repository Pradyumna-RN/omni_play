package labs.rtensor.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.MusicState
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Track

internal val LocalFeedSectionOpen = staticCompositionLocalOf<(FeedSection?) -> Unit> { {} }
internal val LocalPlaySongs = staticCompositionLocalOf<(Track, List<Track>) -> Unit> { { _, _ -> } }
internal val LocalHomeFilterSelect = staticCompositionLocalOf<(String?) -> Unit> { {} }

@Composable
internal fun ProviderFilters(state: MusicState) {
    // Hum is music only: YouTube Music's Podcasts chip is left out.
    val videoSource = state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO
    val filters = state.homeFilters
        .filterNot { it.contains("podcast", ignoreCase = true) }
        .filter { it.lowercase() != "all" }
        .sortedByDescending { it.equals("music", ignoreCase = true) }
    if (filters.isEmpty() && !videoSource) return
    val select = LocalHomeFilterSelect.current
    val row = rememberLazyListState()
    // The source opens on Music and the row always begins there: when the selected tab changes,
    // bring the row back to its start so Music is visible without scrolling right.
    LaunchedEffect(videoSource, state.homeFilter) { if (videoSource) row.scrollToItem(0) }
    LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp), state = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (videoSource) {
            // Music is always the first chip, even before YouTube returns its category list; Feed is
            // YouTube's own home feed, next to it.
            item(key = "filter:music") { FilterChip(state.homeFilter?.equals("Music", ignoreCase = true) == true, { select("Music") }, label = { Text("Music") }) }
            item(key = "filter:feed") { FilterChip(state.homeFilter == null, { select(null) }, label = { Text("Feed") }) }
            itemsIndexed(filters.filterNot { it.equals("music", ignoreCase = true) }, key = { i, title -> "filter:$i:$title" }) { _, title ->
                FilterChip(state.homeFilter == title, { select(title) }, label = { Text(title) })
            }
        } else {
            item(key = "filter:all") { FilterChip(state.homeFilter == null, { select(null) }, label = { Text("All") }) }
            itemsIndexed(filters, key = { i, title -> "$i:$title" }) { _, title ->
                FilterChip(state.homeFilter == title, { select(title) }, label = { Text(title) })
            }
        }
    }
}

/** Real provider sections and item order. No invented mixes, genres or community playlists. */
internal fun LazyListScope.providerShelves(sections: List<FeedSection>, onEntry: (FeedEntry) -> Unit, gap: Dp = 20.dp) {
    itemsIndexed(sections, key = { i, section -> "provider-shelf:$i:${section.title}" }) { shelfIndex, section ->
        val open = LocalFeedSectionOpen.current
        val play = LocalPlaySongs.current
        val songs = remember(section) { section.items.filter { it.videoId.isNotBlank() || it.kind == "spotify_track" }.map(FeedEntry::asTrack) }
        Column(Modifier.fillMaxWidth().padding(top = gap).cascade(shelfIndex, LocalReducedMotion.current)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open(section) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                    if (LocalLook.current.hum) Text(section.title, Modifier.weight(1f).semantics { heading() }, fontFamily = HumDisplay, fontSize = 24.sp,
                        lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.02).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                    else Text(section.title, Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (songs.isNotEmpty()) TextButton(onClick = { play(songs.first(), songs) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Text("Play all", fontSize = 12.sp)
                }
            }
            if (section.items.all { it.kind == "browse" }) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(section.items, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
                    OutlinedButton(onClick = { onEntry(entry) }, shape = LocalLook.current.card) { Text(entry.title, maxLines = 1) }
                }
            } else if (songs.size == section.items.size && songs.size >= 3) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = minOf(maxWidth, 310.dp)
                    val rowHeight = 24.5.dp + maxOf(48.dp, (35 * LocalDensity.current.fontScale + 3).dp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(songs.chunked(4), key = { i, _ -> i }) { _, group ->
                            Column(Modifier.width(width)) {
                                repeat(4) { index ->
                                    val track = group.getOrNull(index)
                                    if (track == null) Spacer(Modifier.height(rowHeight))
                                    else TrackRow(track, false, false, { play(track, listOf(track)) }, null, Modifier.height(rowHeight), singleLine = true)
                                }
                            }
                        }
                    }
                }
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(section.items, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
                    val song = entry.takeIf { it.videoId.isNotBlank() || it.kind == "spotify_track" }?.asTrack()
                    val artwork = song ?: Track(entry.id, entry.title, entry.subtitle, "", entry.artwork)
                    Column(Modifier.width(140.dp).clip(LocalLook.current.art)
                        .then(if (song != null) Modifier.songClickable(song) { onEntry(entry) } else Modifier.clickable { onEntry(entry) })) {
                        Artwork(artwork, Modifier.size(140.dp).clip(LocalLook.current.art), size = 140.dp)
                        Text(entry.title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(entry.subtitle, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * A section opened from its arrow: the whole shelf laid out to scroll down the page, songs as a list
 * and everything else (albums, playlists) as a two-column grid, with no second heading.
 */
internal fun LazyListScope.expandedShelf(sections: List<FeedSection>, onEntry: (FeedEntry) -> Unit) {
    sections.forEachIndexed { sectionIndex, section ->
        val songs = section.items.filter { it.videoId.isNotBlank() || it.kind == "spotify_track" }
        if (songs.size == section.items.size && songs.isNotEmpty()) {
            val tracks = songs.map(FeedEntry::asTrack)
            itemsIndexed(tracks, key = { i, t -> "expanded:$sectionIndex:$i:${t.id}" }) { _, track ->
                val play = LocalPlaySongs.current
                TrackRow(track, false, false, { play(track, tracks) }, null)
            }
        } else {
            itemsIndexed(section.items.chunked(2), key = { i, _ -> "expanded:$sectionIndex:row$i" }) { _, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { entry ->
                        val song = entry.takeIf { it.videoId.isNotBlank() || it.kind == "spotify_track" }?.asTrack()
                        val artwork = song ?: Track(entry.id, entry.title, entry.subtitle, "", entry.artwork)
                        Column(Modifier.weight(1f).clip(LocalLook.current.art).clickable { onEntry(entry) }) {
                            Artwork(artwork, Modifier.fillMaxWidth().aspectRatio(1f).clip(LocalLook.current.art), size = 170.dp)
                            Text(entry.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
