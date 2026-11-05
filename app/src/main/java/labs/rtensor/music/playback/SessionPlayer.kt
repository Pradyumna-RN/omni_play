package labs.rtensor.music.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player the media session exposes to the notification, lock screen, headphones and the car.
 *
 * After a playback error Media3 leaves the player idle, and a skip from outside the app only
 * moves the queue position: the next song never loads. The app's own buttons prepare again
 * ([PlayerTransport]); this does the same for every other controller, so Next or Previous on a
 * headset or the lock screen starts the chosen song.
 */
@UnstableApi
internal class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    override fun seekToNext() { super.seekToNext(); recover() }
    override fun seekToNextMediaItem() { super.seekToNextMediaItem(); recover() }
    override fun seekToPrevious() { super.seekToPrevious(); recover() }
    override fun seekToPreviousMediaItem() { super.seekToPreviousMediaItem(); recover() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { super.seekToDefaultPosition(mediaItemIndex); recover() }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { super.seekTo(mediaItemIndex, positionMs); recover() }

    /** Only a failed player: a stopped one (notification dismissed) stays stopped until Play. */
    private fun recover() {
        if (playbackState == Player.STATE_IDLE && playerError != null && mediaItemCount > 0) prepare()
    }
}
