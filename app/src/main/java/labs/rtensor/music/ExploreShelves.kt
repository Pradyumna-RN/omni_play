package labs.rtensor.music

import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection

internal data class ExploreShelves(val discover: List<FeedSection>, val browse: List<FeedSection>)

/** Use actual provider entries already loaded, including when the separate Explore request fails. */
internal fun exploreShelves(explore: List<FeedSection>, recommendations: List<FeedSection>): ExploreShelves {
    val sources = explore + recommendations
    fun select(predicate: (FeedEntry) -> Boolean): List<FeedSection> {
        val seen = HashSet<String>()
        return sources.mapNotNull { section ->
            val entries = section.items.filter { entry ->
                predicate(entry) && seen.add(if (entry.kind == "browse") "browse:${entry.browseId}:${entry.params}"
                    else entry.id.ifBlank { "${entry.kind}:${entry.browseId}:${entry.videoId}:${entry.title}" })
            }
            section.copy(items = entries).takeIf { entries.isNotEmpty() }
        }
    }
    val discover = select { it.kind != "browse" }
    val navigation = select { it.kind == "browse" }
    val collections = select { it.kind in setOf("album", "playlist", "artist", "library_artist", "spotify_album", "spotify_playlist", "spotify_artist") }
    // Navigation does not replace available albums/playlists. Song-only providers remain browsable.
    return ExploreShelves(discover, (navigation + collections).ifEmpty { discover })
}
