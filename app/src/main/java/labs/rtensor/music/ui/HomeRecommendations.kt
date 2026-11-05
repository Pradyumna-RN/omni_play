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

/** Every recording Home has loaded or that counts as heard; "More from your feed" only adds songs outside it. */
internal fun homeKnownKeys(state: MusicState): MutableSet<String> =
    heardKeys(state).apply { (state.forYou + feedSongs(state.feed) + state.feedMore).mapTo(this) { it.libraryKey() } }

/** Partition provider suggestions, never manufacture suggestions by copying history. */
internal fun homeRecommendationGroups(state: MusicState): HomeRecommendationGroups {
    val heard = heardKeys(state)
    val candidates = (state.forYou + feedSongs(state.feed)).filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    val fresh = candidates.filter { it.libraryKey() !in heard }
    // Reserve a distinct set of songs for discovery, including unheard songs by familiar artists.
    val new = fresh.takeLast(minOf(6, (fresh.size + 1) / 2))
    val newKeys = new.mapTo(HashSet()) { it.libraryKey() }
    val familiar = state.forYou.filter { it.libraryKey() !in newKeys && it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    // Songs from "More from your feed" go below the ones already on screen, which keep their places.
    val shown = (familiar + new).mapTo(HashSet()) { it.libraryKey() }
    val more = state.feedMore.filter { it.source != Source.LOCAL && it.libraryKey() !in heard && shown.add(it.libraryKey()) }
    return HomeRecommendationGroups(familiar, new + more)
}
