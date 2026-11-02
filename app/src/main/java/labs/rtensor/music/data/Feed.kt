package labs.rtensor.music.data
import org.json.JSONObject

data class FeedEntry(val id: String, val title: String, val subtitle: String,
    val artwork: String, val kind: String, val browseId: String, val videoId: String, val durationMs: Long = 0) {
    fun asTrack() = Track(id, title, subtitle, "https://www.youtube.com/watch?v=$videoId", artwork, durationMs)
    companion object {
        fun parse(o: JSONObject) = FeedEntry(o.optString("id"), o.optString("title"), o.optString("subtitle"),
            o.optString("artwork"), o.optString("kind"), o.optString("browseId"), o.optString("videoId").takeUnless { it == "null" }.orEmpty(), o.optLong("durationMs"))
    }
}
data class FeedSection(val title: String, val items: List<FeedEntry>)
data class HomeFeed(val sections: List<FeedSection>, val hasMore: Boolean)
