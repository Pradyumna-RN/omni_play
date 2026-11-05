package labs.rtensor.music.ui

import labs.rtensor.music.MusicState
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track

internal data class HomeRecommendationGroups(val fromListening: List<Track>, val newToYou: List<Track>)

/** Individual songs in feed sections, in the provider's order; never albums, playlists or local files. */
internal fun feedSongs(sections: List<FeedSection>): List<Track> = sections.flatMap { it.items }
    .filter { it.videoId.isNotBlank() || it.kind == "spotify_track" }
    .mapNotNull { runCatching { it.asTrack() }.getOrNull() }
    .filter { it.source != Source.LOCAL }

private fun heardKeys(state: MusicState): MutableSet<String> =
    (state.playEvents.map { it.track } + state.history + listOfNotNull(state.current)).mapTo(HashSet()) { it.libraryKey() }

/** Every recording Home has loaded or that counts as heard; "More from your feed" only brings songs outside it. */
internal fun homeKnownKeys(state: MusicState): MutableSet<String> =
    heardKeys(state).apply { (state.forYou + feedSongs(state.feed) + state.feedMore).mapTo(this) { it.libraryKey() } }

/**
 * Partition provider suggestions, never manufacture suggestions by copying history. "New to your
 * ears" holds up to six unheard songs from the end of what Home has loaded; after "More from your
 * feed" it holds the newest batch instead, so each tap shows the next handful (as in 0.3.32).
 */
internal fun homeRecommendationGroups(state: MusicState): HomeRecommendationGroups {
    val heard = heardKeys(state)
    val candidates = (state.forYou + feedSongs(state.feed)).filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    val fresh = candidates.filter { it.libraryKey() !in heard }
    val batch = state.feedMore.filter { it.source != Source.LOCAL && it.libraryKey() !in heard }.distinctBy { it.libraryKey() }
    // Reserve a distinct set of songs for discovery, including unheard songs by familiar artists.
    val new = if (batch.isNotEmpty()) batch.takeLast(6) else fresh.takeLast(minOf(6, (fresh.size + 1) / 2))
    val newKeys = new.mapTo(HashSet()) { it.libraryKey() }
    val familiar = state.forYou.filter { it.libraryKey() !in newKeys && it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    return HomeRecommendationGroups(familiar, new)
}
