package labs.rtensor.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import labs.rtensor.music.R

/**
 * Our own now-playing notification. The whole notification is colorized with a deep tint
 * taken from the cover (allowed for a media foreground service), and our views draw on that
 * same tint, so there is no dark frame around an inner card. It stays a media-style
 * notification tied to the session, so lock screen, Bluetooth and the Android 13+ media
 * controls keep working.
 */
@UnstableApi
class PlayerNotification(private val context: Context) : MediaNotification.Provider {
    private class Cover(val key: String, val art: Bitmap, val tint: Int)

    private val main = Handler(Looper.getMainLooper())
    private var cover: Cover? = null
    private var loading: String? = null

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        ensureChannel()
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val key = metadata.artworkUri?.toString() ?: metadata.artworkData?.contentHashCode()?.toString()
        val shown = cover?.takeIf { it.key == key }
        if (key != null && shown == null && loading != key) {
            loading = key
            mediaSession.bitmapLoader.loadBitmapFromMetadata(metadata)?.let { future ->
                Futures.addCallback(future, object : FutureCallback<Bitmap> {
                    override fun onSuccess(result: Bitmap?) {
                        if (loading != key) return
                        loading = null
                        if (result != null) cover = Cover(key, rounded(result), tint(result))
                        onNotificationChangedCallback.onNotificationChanged(
                            createNotification(mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback)
                        )
                    }
                    override fun onFailure(t: Throwable) { if (loading == key) loading = null }
                }, java.util.concurrent.Executor { main.post(it) })
            } ?: run { loading = null }
        }

        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        val tint = shown?.tint ?: DefaultTint
        val title = metadata.title?.toString().orEmpty().ifBlank { "Nothing playing" }
        val artist = metadata.artist?.toString().orEmpty()
        val playIntent = actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_PLAY_PAUSE.toLong())
        val canNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) && player.hasNextMediaItem()
        val canPrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)

        fun RemoteViews.fill(expanded: Boolean) = apply {
            setInt(R.id.notification_root, "setBackgroundColor", tint)
            setTextViewText(R.id.notification_title, title)
            setTextViewText(R.id.notification_artist, artist)
            if (shown != null) setImageViewBitmap(R.id.notification_art, shown.art)
            else setImageViewResource(R.id.notification_art, R.drawable.ic_music)
            setInt(R.id.notification_play_pill, "setColorFilter", Color.WHITE)
            setImageViewResource(R.id.notification_play_icon, if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play)
            setInt(R.id.notification_play_icon, "setColorFilter", tint)
            setContentDescription(R.id.notification_play, if (playing) "Pause" else "Play")
            setOnClickPendingIntent(R.id.notification_play, playIntent)
            control(R.id.notification_next, canNext) { actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_SEEK_TO_NEXT.toLong()) }
            if (expanded) {
                control(R.id.notification_previous, canPrevious) { actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_SEEK_TO_PREVIOUS.toLong()) }
                val count = player.mediaItemCount
                setTextViewText(R.id.notification_label, if (count > 1) "NOW PLAYING · ${player.currentMediaItemIndex + 1} / $count" else "NOW PLAYING")
                // The chronometer keeps counting on its own, so the notification is not re-posted every second.
                val position = player.currentPosition.coerceAtLeast(0)
                setChronometer(R.id.notification_elapsed, SystemClock.elapsedRealtime() - position, null, player.isPlaying)
                val duration = player.duration
                setTextViewText(R.id.notification_duration, if (duration == C.TIME_UNSET || duration <= 0) "" else clock(duration))
            }
        }

        val notification = NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setCustomContentView(RemoteViews(context.packageName, R.layout.notification_player_collapsed).fill(false))
            .setCustomBigContentView(RemoteViews(context.packageName, R.layout.notification_player_expanded).fill(true))
            .setStyle(MediaStyleNotificationHelper.DecoratedMediaCustomViewStyle(mediaSession))
            .setColor(tint)
            .setColorized(true)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_STOP.toLong()))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .build()
        return MediaNotification(NotificationId, notification)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean = false

    private inline fun RemoteViews.control(id: Int, enabled: Boolean, intent: () -> android.app.PendingIntent?) {
        setInt(id, "setColorFilter", Color.WHITE)
        setInt(id, "setImageAlpha", if (enabled) 255 else 90)
        setOnClickPendingIntent(id, if (enabled) intent() else null)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(Channel) != null) return
        manager.createNotificationChannel(NotificationChannel(Channel, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What is playing, with play, pause and skip"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    companion object {
        const val Channel = "now_playing"
        const val NotificationId = 1001
        private const val DefaultTint = 0xFF7A1F3D.toInt()

        /** Square-crops the cover and rounds its corners, since notification views cannot clip. */
        fun rounded(source: Bitmap, size: Int = 192): Bitmap {
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * .14f, size * .14f, paint)
            return out
        }

        /** A deep colour taken from the cover, dark enough for white text (at least 7:1). */
        fun tint(source: Bitmap): Int {
            val pixel = Bitmap.createScaledBitmap(source, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(pixel, hsv)
            if (hsv[1] > .08f) hsv[1] = hsv[1].coerceIn(.35f, .8f)
            hsv[2] = hsv[2].coerceAtMost(.34f)
            return Color.HSVToColor(hsv)
        }

        fun clock(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
