package labs.rtensor.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import org.json.JSONObject

/** Change the actual media session, including sources pinned before a folder
 * was removed. Updating just the screen leaves open files playing in Media3. */
@UnstableApi
internal object LibraryQueueEdits {
    fun reconcile(player: Player, removed: (Track) -> Boolean, invalidMasterUris: Set<String>, rebuild: (Track) -> MediaItem) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            val track = runCatching { Track.fromJson(JSONObject(item.mediaMetadata.extras?.getString("catalogue_track").orEmpty())) }.getOrNull() ?: continue
            if (removed(track)) {
                val current = index == player.currentMediaItemIndex
                if (current) { player.pause(); player.stop() }
                player.removeMediaItem(index)
                if (current && player.mediaItemCount > 0) player.prepare()
            } else if (track.source != Source.LOCAL && item.localConfiguration?.uri?.toString() in invalidMasterUris) {
                val current = index == player.currentMediaItemIndex
                val position = player.currentPosition.coerceAtLeast(0)
                val resume = player.playWhenReady
                if (current) player.stop()
                player.replaceMediaItem(index, rebuild(track))
                if (current) {
                    player.seekTo(index, position)
                    player.prepare()
                    player.playWhenReady = resume
                }
            }
        }
    }
}
