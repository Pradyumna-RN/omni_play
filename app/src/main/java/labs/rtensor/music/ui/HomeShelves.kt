package labs.rtensor.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import labs.rtensor.music.MusicState
import labs.rtensor.music.R
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.recommendations.RecommendationEngine
import java.util.Locale

/*
 * Home's recommendations as horizontal shelves. Each shelf has one job:
 *  - "Next up for you": the songs picked from your listening, as a paged grid of four rows a column;
 *  - "Play it again": songs you played on this phone, as two rows of square tiles (only while
 *    listening history is on);
 *  - "Your mixes": big cards that play a whole mix (your own mix and each section of the feed).
 * Everything here is read from data Home already has; nothing is fetched or invented.
 */

private val ShelfDisplay = FontFamily(Font(R.font.archivo_condensed_extrabold, FontWeight.ExtraBold))
private val ShelfArchivo = FontFamily(Font(R.font.archivo_regular), Font(R.font.archivo_semibold, FontWeight.SemiBold))
private val ShelfSoft = FontFamily(Font(R.font.dm_sans))

/** A mix card's content: a name, a line under it and the songs it plays in order. */
internal data class HomeMix(val key: String, val title: String, val detail: String, val tracks: List<Track>)

/** Songs for "Play it again": most recent plays first, without the ones the Last played block already shows. */
internal fun playItAgain(state: MusicState, shownAbove: Int): List<Track> {
    if (!state.trackHistory) return emptyList()
    val above = state.history.take(shownAbove).mapTo(HashSet()) { it.libraryKey() }
    return (state.playEvents.sortedByDescending { it.startedAt }.map { it.track } + state.history)
        .distinctBy { it.libraryKey() }.filter { it.libraryKey() !in above }.take(16)
}

/** "Your mixes": your own mix (what Play mix plays) and one mix per feed section with songs in it. */
internal fun homeMixes(state: MusicState): List<HomeMix> {
    val own = state.forYou.filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    val engine = if (state.recommendationEngine == RecommendationEngine.SPOTIFY) "Radio from Spotify" else "From what you play most"
    val mine = if (own.size >= 2) listOf(HomeMix("mix:yours", "Your mix", engine, state.forYou)) else emptyList()
    return mine + state.feed.mapIndexedNotNull { i, section ->
        val songs = feedSongs(listOf(section)).distinctBy { it.libraryKey() }
        if (songs.size < 2 || section.title.isBlank()) null
        else HomeMix("mix:$i:${section.title}", section.title, artistsLine(songs), songs)
    }
}

private fun artistsLine(tracks: List<Track>): String {
    val names = tracks.map { it.artist.trim() }.filter { it.isNotEmpty() }.distinct()
    return when {
        names.isEmpty() -> "${tracks.size} songs"
        names.size <= 2 -> names.joinToString(" & ")
        else -> "${names.take(2).joinToString(", ")} and more"
    }
}

/**
 * Adds the shelves to Home's list. [fromListening] is the familiar group from
 * [homeRecommendationGroups]; songs play alone (seeding radio), as everywhere on Home, and only
 * Play mix and the mix cards pass a whole list as the queue.
 */
internal fun LazyListScope.homeShelves(state: MusicState, fromListening: List<Track>, topArtists: List<String>,
    onPlay: (Track, List<Track>) -> Unit) {
    if (fromListening.isNotEmpty() || state.forYouBusy) item(key = "for-you", contentType = "shelf-grid") {
        val look = LocalLook.current
        val detail = when {
            state.recommendationEngine == RecommendationEngine.SPOTIFY -> "Radio from Spotify"
            look.soft || topArtists.isEmpty() -> "From what you play most on this phone"
            else -> "Your sound · ${topArtists.joinToString(" · ")}"
        }
        Shelf("01", "Next up for you", detail, action = {
            if (state.forYou.isNotEmpty()) ShelfButton("Play mix") { onPlay(state.forYou.first(), state.forYou) }
        }) {
            if (fromListening.isEmpty()) ShelfLoading()
            else SongGrid(fromListening, state.current?.libraryKey()) { onPlay(it, listOf(it)) }
        }
    }
    val again = playItAgain(state, if (state.look == labs.rtensor.music.data.LookChoice.SOFT) 12 else 5)
    if (again.isNotEmpty()) item(key = "again", contentType = "shelf-tiles") {
        Shelf("02", "Play it again", "From your listening history") {
            TileGrid(again) { onPlay(it, listOf(it)) }
        }
    }
    val mixes = homeMixes(state)
    if (mixes.isNotEmpty()) item(key = "mixes", contentType = "shelf-mixes") {
        Shelf("03", "Your mixes", "Each plays as one queue") {
            MixRow(mixes) { mix -> onPlay(mix.tracks.first(), mix.tracks) }
        }
    }
}

/** A shelf: a heading (with an optional action on the right) and its horizontal content. */
@Composable
private fun Shelf(number: String, title: String, detail: String, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = if (look.classic) 24.dp else 22.dp)) {
        if (look.classic) Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.onBackground))
        Row(Modifier.fillMaxWidth().padding(top = if (look.classic) 12.dp else 0.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    look.classic -> {
                        Text("$number / ${detail.uppercase(Locale.ROOT)}", style = MonoSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$title.", Modifier.padding(top = 6.dp).clearAndSetSemantics { text = AnnotatedString(title); heading() }, fontFamily = ShelfDisplay, fontWeight = FontWeight.ExtraBold,
                            fontSize = 28.sp, lineHeight = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    look.soft -> {
                        Text(title, Modifier.semantics { heading() }, fontFamily = ShelfSoft, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                            letterSpacing = (-.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(detail, fontFamily = ShelfSoft, fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    else -> {
                        Text(detail, fontFamily = ShelfArchivo, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = look.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Poster capitals on screen, read as written.
                        val shown = remember(title) { title.uppercase(Locale.getDefault()) }
                        Text(shown, Modifier.padding(top = 2.dp).clearAndSetSemantics { text = AnnotatedString(title); heading() },
                            style = TextStyle(fontFamily = ShelfDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 28.sp,
                                letterSpacing = .01.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            action()
        }
        content()
    }
}

@Composable
private fun ShelfButton(label: String, onClick: () -> Unit) {
    val look = LocalLook.current
    val ink = MaterialTheme.colorScheme.onBackground
    Spacer(Modifier.width(12.dp))
    OutlinedButton(onClick = onClick, shape = look.button, modifier = Modifier.heightIn(min = 48.dp),
        border = androidx.compose.foundation.BorderStroke(if (look.classic) 2.dp else 1.5.dp, ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ink), contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(label, style = LabelText, maxLines = 1)
    }
}

@Composable
private fun ShelfLoading() {
    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f))
}

private const val GridRows = 4
private val RowHeight = 60.dp

/**
 * Four song rows a column; columns page sideways and snap to the start, with the next column
 * peeking in so it reads as swipeable. TalkBack gets the grid's scroll actions.
 */
@Composable
private fun SongGrid(tracks: List<Track>, currentKey: String?, onPlay: (Track) -> Unit) {
    val look = LocalLook.current
    val state = rememberLazyGridState()
    val rows = minOf(GridRows, tracks.size)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val column = maxWidth - 36.dp
        val inner = RowHeight * rows + 4.dp * (rows - 1)
        // The Listening room sets the grid on a rounded panel; the other looks leave it on the page.
        val grid = if (look.room) Modifier.fillMaxWidth().height(inner + 12.dp).clip(look.card).background(look.panel).padding(vertical = 6.dp)
            else Modifier.fillMaxWidth().height(inner)
        LazyHorizontalGrid(GridCells.Fixed(rows), grid.testTag("shelf:next-up"),
            state = state, flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(if (look.room) 0.dp else 12.dp)) {
            itemsIndexed(tracks, key = { i, t -> "next:$i:${t.libraryKey()}" }, contentType = { _, _ -> "song-row" }) { i, track ->
                SongRow(track, i, track.libraryKey() == currentKey, Modifier.width(column)) { onPlay(track) }
            }
        }
    }
}

@Composable
private fun SongRow(track: Track, index: Int, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccent.current
    val description = if (track.artist.isBlank()) track.title else "${track.title} by ${track.artist}"
    Row(modifier.height(RowHeight).then(if (look.soft) Modifier.clip(RoundedCornerShape(14.dp)) else Modifier)
        .clickable(onClickLabel = "Play", role = Role.Button, onClick = onClick)
        .clearAndSetSemantics {
            contentDescription = if (playing) "$description, playing" else description
            text = AnnotatedString(track.title); role = Role.Button
            onClick("Play") { onClick(); true }
        }
        .padding(horizontal = if (look.room) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (look.classic) {
            Text("%02d".format(Locale.ROOT, index + 1), style = MonoSmall, color = if (playing) scheme.onBackground else scheme.onSurfaceVariant,
                modifier = Modifier.width(26.dp))
        }
        Box {
            Artwork(track, Modifier.size(48.dp).clip(look.art), size = 48.dp)
            if (playing && look.room) Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(8.dp).glow(accent.accent, 2f, .8f).background(accent.accent, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontFamily = if (look.soft) ShelfSoft else ShelfArchivo, fontWeight = if (look.soft) FontWeight.Medium else FontWeight.SemiBold,
                fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing && look.soft) scheme.primary else scheme.onBackground)
            Text(if (look.classic) track.artist.uppercase(Locale.ROOT) else track.artist, style = if (look.classic) MonoSmall else TextStyle(
                fontFamily = if (look.soft) ShelfSoft else ShelfArchivo, fontSize = 13.sp, lineHeight = 17.sp),
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val TileSize = 112.dp

/** Two rows of square tiles; the grid scrolls sideways and snaps to a tile. */
@Composable
private fun TileGrid(tracks: List<Track>, onPlay: (Track) -> Unit) {
    val state = rememberLazyGridState()
    val rows = if (tracks.size >= 4) 2 else 1
    LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.fillMaxWidth().height((TileSize + 46.dp) * rows + 12.dp * (rows - 1)).testTag("shelf:again"),
        state = state, flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks, key = { _, t -> "again:${t.libraryKey()}" }, contentType = { _, _ -> "tile" }) { _, track ->
            Tile(track) { onPlay(track) }
        }
    }
}

@Composable
private fun Tile(track: Track, onClick: () -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val description = if (track.artist.isBlank()) track.title else "${track.title} by ${track.artist}"
    Column(Modifier.width(TileSize)
        .clickable(onClickLabel = "Play", role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = description; text = AnnotatedString(track.title); role = Role.Button; onClick("Play") { onClick(); true } }) {
        val frame = Modifier.size(TileSize).clip(look.card).then(
            if (look.classic) Modifier.border(2.dp, scheme.onBackground) else Modifier)
        Artwork(track, frame, size = TileSize)
        Text(track.title, Modifier.padding(top = 6.dp), fontFamily = if (look.soft) ShelfSoft else ShelfArchivo,
            fontWeight = if (look.soft) FontWeight.Medium else FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onBackground)
        Text(if (look.classic) track.artist.uppercase(Locale.ROOT) else track.artist,
            style = if (look.classic) MonoSmall.copy(fontSize = 10.sp) else TextStyle(fontFamily = if (look.soft) ShelfSoft else ShelfArchivo, fontSize = 12.sp, lineHeight = 16.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
    }
}

/** Big mix cards: a cover collage, the mix's name and a play mark. One tap plays the whole mix. */
@Composable
private fun MixRow(mixes: List<HomeMix>, onPlay: (HomeMix) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(Modifier.fillMaxWidth().testTag("shelf:mixes"), state = state, flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(mixes, key = { _, m -> m.key }, contentType = { _, _ -> "mix" }) { _, mix -> MixCard(mix) { onPlay(mix) } }
    }
}

@Composable
private fun MixCard(mix: HomeMix, onClick: () -> Unit) {
    val look = LocalLook.current
    val accent = LocalAccent.current
    val scheme = MaterialTheme.colorScheme
    val covers = mix.tracks.distinctBy { it.artwork }.take(4)
    Box(Modifier.width(248.dp).height(200.dp).clip(look.card)
        .then(if (look.classic) Modifier.border(2.dp, scheme.onBackground) else Modifier)
        .background(if (look.soft) look.surface else look.panel)
        .clickable(onClickLabel = "Play mix", role = Role.Button, onClick = onClick)
        .clearAndSetSemantics {
            contentDescription = "${mix.title}, ${mix.tracks.size} songs, ${mix.detail}"
            text = AnnotatedString(mix.title); role = Role.Button; onClick("Play mix") { onClick(); true }
        }) {
        if (covers.size >= 4) Column(Modifier.fillMaxSize()) {
            for (r in 0..1) Row(Modifier.weight(1f)) {
                for (c in 0..1) Artwork(covers[r * 2 + c], Modifier.weight(1f).fillMaxSize(), size = 124.dp)
            }
        } else Artwork(mix.tracks.first(), Modifier.fillMaxSize(), size = 248.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .35f to Color.Transparent, 1f to Color.Black.copy(alpha = .86f))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (look.classic) Text("MIX · ${mix.tracks.size}", style = MonoSmall, color = Paper)
                val title = when {
                    look.soft -> TextStyle(fontFamily = ShelfSoft, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-.6).sp)
                    look.classic -> TextStyle(fontFamily = ShelfDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 27.sp)
                    else -> TextStyle(fontFamily = ShelfDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = .01.em)
                }
                Text(if (look.room) mix.title.uppercase(Locale.getDefault()) else mix.title, style = title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(mix.detail, fontFamily = if (look.soft) ShelfSoft else ShelfArchivo, fontSize = 12.sp, lineHeight = 16.sp,
                    color = Color.White.copy(alpha = .82f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(10.dp))
            val ring = if (look.soft) scheme.primary else accent.accent
            val mark = if (look.soft) scheme.onPrimary else accent.onAccent
            Box(Modifier.size(48.dp).then(if (look.room) Modifier.glow(ring, 1.6f, .55f) else Modifier)
                .background(ring, if (look.classic) androidx.compose.ui.graphics.RectangleShape else CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, null, tint = mark, modifier = Modifier.size(26.dp))
            }
        }
    }
}
