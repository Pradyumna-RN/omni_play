package labs.rtensor.music.playback

import labs.rtensor.music.data.Track
import org.json.JSONObject

/** Controller extras are unchanged between progress ticks. Decode only when the
 * actual catalogue JSON changes, including metadata edits to the same song. */
internal class CatalogueTrackCache {
    private var encoded: String? = null
    private var decoded: Track? = null
    fun read(value: String?): Track? {
        if (value != encoded) {
            encoded = value
            decoded = runCatching { value?.let { Track.fromJson(JSONObject(it)) } }.getOrNull()
        }
        return decoded
    }
}
