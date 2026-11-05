package labs.rtensor.music.spotify

import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.Track
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class SpotifyItem(val id: String, val kind: String, val title: String, val artist: String,
    val artwork: String = "", val durationMs: Long = 0) {
    val uri get() = "spotify:$kind:$id"
    fun track(): Track {
        require(kind == "track")
        return Track("spotify:$id", title, artist, "https://open.spotify.com/track/$id", artwork, durationMs, spotifyId = id)
    }
    fun entry() = FeedEntry(uri, title, artist, artwork, "spotify_$kind", uri, "", durationMs, artist)
    fun json() = JSONObject().put("id", id).put("kind", kind).put("title", title).put("artist", artist)
        .put("artwork", artwork).put("durationMs", durationMs)
    companion object {
        val ID = Regex("[A-Za-z0-9]{22}")
        fun parse(o: JSONObject): SpotifyItem {
            val item = SpotifyItem(o.getString("id"), o.getString("kind"), o.getString("title"),
                o.optString("artist"), o.optString("artwork"), o.optLong("durationMs"))
            require(ID.matches(item.id) && item.kind in setOf("track", "album", "playlist"))
            require(item.title.isNotBlank() && item.title.length <= 512 && item.artist.length <= 512)
            require(item.durationMs in 0..86_400_000 && item.artwork.length <= 2048)
            return item
        }
        fun from(entry: FeedEntry): SpotifyItem {
            val parts = entry.browseId.split(':')
            require(parts.size == 3 && parts[0] == "spotify")
            return parse(JSONObject().put("id", parts[2]).put("kind", parts[1]).put("title", entry.title)
                .put("artist", entry.artist.ifBlank { entry.subtitle }).put("artwork", entry.artwork).put("durationMs", entry.durationMs))
        }
    }
}

/** Deliberately conservative: a missed match is preferable to another edition. */
internal object SpotifyRecordingMatch {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val decoration = Regex("(?i)\\b(official|audio|video|lyrics?|visuali[sz]er|hd|hq)\\b")
    private val editions = listOf("live", "remix", "cover", "acoustic", "instrumental", "karaoke", "slowed", "sped", "remaster", "extended", "radio edit")
    private fun text(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace("–", "-").replace("—", "-").replace(punctuation, " ").trim().replace(Regex("\\s+"), " ")
    private fun title(s: String) = text(s.replace(decoration, " "))
    private fun version(s: String) = editions.filter { term -> Regex("\\b${Regex.escape(term)}(?:ed)?\\b").containsMatchIn(text(s)) }.toSet()
    fun matches(item: SpotifyItem, candidate: Track): Boolean = matches(item.title, item.artist, item.durationMs,
        candidate.title, candidate.artist, candidate.durationMs)
    fun matches(aTitle: String, aArtist: String, aDuration: Long, bTitle: String, bArtist: String, bDuration: Long): Boolean {
        if (aDuration <= 0 || bDuration <= 0 || aArtist.isBlank() || bArtist.isBlank()) return false
        if (abs(aDuration - bDuration) > (aDuration * .02).toLong().coerceIn(2500, 6000)) return false
        if (version(aTitle) != version(bTitle)) return false
        if ("remaster" in version(aTitle) && Regex("\\b(?:19|20)[0-9]{2}\\b").findAll(aTitle).map { it.value }.toSet() !=
            Regex("\\b(?:19|20)[0-9]{2}\\b").findAll(bTitle).map { it.value }.toSet()) return false
        val a = title(aTitle); val b = title(bTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        val sameTitle = a == b || (a.split(' ').toSet().intersect(b.split(' ').toSet()).size * 2.0 /
            (a.split(' ').toSet().size + b.split(' ').toSet().size)) >= .9
        if (!sameTitle) return false
        val artists = aArtist.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|and|with)\\s+|[,;&]")).map(::text)
        val others = bArtist.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|and|with)\\s+|[,;&]")).map { text(it).removeSuffix(" topic").trim() }
        return artists.firstOrNull()?.takeIf(String::isNotBlank) in others
    }
}
