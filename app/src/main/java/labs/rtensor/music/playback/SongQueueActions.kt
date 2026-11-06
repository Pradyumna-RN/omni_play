package labs.rtensor.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import labs.rtensor.music.data.Track
import org.json.JSONObject

/** Explicit choices go ahead of the automatically generated radio tail, without restarting audio. */
@UnstableApi
internal object SongQueueActions {
    fun add(player: Player, item: MediaItem, next: Boolean) {
        val selected = key(item)
        val duplicates = (player.currentMediaItemIndex + 1 until player.mediaItemCount).count {
            val queued = player.getMediaItemAt(it)
            queued.mediaMetadata.extras?.getString(RADIO) != null && key(queued) == selected
        }
        require(player.mediaItemCount - duplicates < 100) { "The queue holds 100 songs. Remove a song before adding another." }
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            val queued = player.getMediaItemAt(i)
            if (queued.mediaMetadata.extras?.getString(RADIO) != null && key(queued) == selected) player.removeMediaItem(i)
        }
        val first = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        val index = if (next) first else (first until player.mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaMetadata.extras?.getString(RADIO) != null } ?: player.mediaItemCount
        player.addMediaItem(index, item)
    }
    private fun key(item: MediaItem) = runCatching {
        Track.fromJson(JSONObject(item.mediaMetadata.extras?.getString("catalogue_track").orEmpty())).libraryKey()
    }.getOrDefault(item.mediaId)
    private const val RADIO = "music_radio_provider"
}
