package labs.rtensor.music.ui

import labs.rtensor.music.MusicState
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track

internal data class HomeRecommendationGroups(val fromListening: List<Track>, val newToYou: List<Track>)

/** Partition provider suggestions, never manufacture suggestions by copying history. */
internal fun homeRecommendationGroups(state: MusicState): HomeRecommendationGroups {
    val heard = (state.playEvents.map { it.track } + state.history + listOfNotNull(state.current))
        .mapTo(HashSet()) { it.libraryKey() }
    val feedSongs = state.feed.flatMap { it.items }.filter { it.videoId.isNotBlank() || it.kind == "spotify_track" }
        .mapNotNull { runCatching { it.asTrack() }.getOrNull() }
    val candidates = (state.forYou + feedSongs).filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    val fresh = candidates.filter { it.libraryKey() !in heard }
    // Reserve a distinct set of songs for discovery, including unheard songs by familiar artists.
    val new = fresh.takeLast(minOf(6, (fresh.size + 1) / 2))
    val newKeys = new.mapTo(HashSet()) { it.libraryKey() }
    val familiar = state.forYou.filter { it.libraryKey() !in newKeys && it.source != Source.LOCAL }.distinctBy { it.libraryKey() }
    return HomeRecommendationGroups(familiar, new)
}
