package labs.rtensor.music.playback

import androidx.media3.common.Player

/**
 * The in-app transport buttons. Media3 keeps playWhenReady true after a playback error and
 * at the end of the queue, and a stopped player (dismissed notification) stays idle until it
 * is prepared again, so each control states the player it expects instead of trusting
 * playWhenReady alone.
 */
internal object PlayerTransport {
    /** Previous restarts the song after this much has played, as in other players. */
    const val RESTART_THRESHOLD_MS = 3_000L

    fun toggle(player: Player, retry: () -> Unit) {
        when {
            player.playerError != null -> retry()
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition(player.currentMediaItemIndex)
                player.play()
            }
            player.playbackState == Player.STATE_IDLE -> {
                if (player.mediaItemCount == 0) return
                player.prepare()
                player.play()
            }
            // During a call (audio focus) the player is held silent with play still requested, and the
            // app shows Play. A tap on that Play must keep playing once the call ends, not pause.
            player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE -> player.pause()
            else -> player.play()
        }
    }

    fun skipTo(player: Player, index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        resume(player)
        player.play()
    }

    fun next(player: Player) {
        if (!player.hasNextMediaItem()) return
        player.seekToNextMediaItem()
        resume(player)
    }

    fun previous(player: Player, knownDurationMs: Long) {
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            seek(player, 0, knownDurationMs)
            return
        }
        player.seekToPreviousMediaItem()
        resume(player)
    }

    /** Clamp to the known duration; before it is known, let Media3 clamp instead of restarting. */
    fun seek(player: Player, positionMs: Long, knownDurationMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: knownDurationMs
        player.seekTo(if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
    }

    /** A failed or stopped player stays idle after a seek; moving to another song should play it. */
    private fun resume(player: Player) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
    }
}
