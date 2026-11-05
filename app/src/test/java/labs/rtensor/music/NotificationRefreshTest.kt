package labs.rtensor.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import labs.rtensor.music.playback.PlayerNotification
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The now-playing notification draws its own elapsed-time clock, so it must be rebuilt after a seek. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null

    private class Silent : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "silent"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {}
        override fun isReady() = true
        override fun isEnded() = true
    }

    private val actions = object : MediaNotification.ActionFactory {
        private fun intent(code: Long) = PendingIntent.getBroadcast(context, code.toInt(), Intent("test.$code"), PendingIntent.FLAG_IMMUTABLE)
        override fun createMediaAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int) =
            NotificationCompat.Action(0, title, intent(command.toLong()))
        override fun createCustomAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, customAction: String, extras: Bundle) =
            NotificationCompat.Action(0, title, intent(99))
        override fun createCustomActionFromCustomCommandButton(mediaSession: MediaSession, customCommandButton: CommandButton) =
            NotificationCompat.Action(0, customCommandButton.displayName, intent(98))
        override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Long) = intent(command)
    }

    private fun runUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    private fun playing(): Pair<ExoPlayer, MediaSession> {
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                SilenceMediaSource.Factory().setDurationUs(60_000_000).setTag(mediaItem.mediaId).createMediaSource()
        }
        val p = ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf(Silent()) }).setMediaSourceFactory(factory)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .build().also { exo = it }
        p.setMediaItem(MediaItem.Builder().setMediaId("one").setUri("https://example.invalid/one")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("One").setArtist("Artist").build()).build())
        p.prepare(); p.play()
        runUntil("playback") { p.isPlaying }
        return p to MediaSession.Builder(context, p).setId("notification-${System.nanoTime()}").build().also { session = it }
    }

    @After fun release() { session?.release(); exo?.release(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun aPhoneCallPauseRebuildsTheNotificationSoItsClockStops() {
        val (p, s) = playing()
        val posted = mutableListOf<MediaNotification>()
        PlayerNotification(context).createNotification(s, ImmutableList.of(), actions) { posted += it }
        shadowOf(Looper.getMainLooper()).idle()
        val before = posted.size
        // A call takes audio focus for a while: Media3 suppresses playback but keeps "play" requested.
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runUntil("playback to be suppressed") { !p.isPlaying }
        assertTrue(p.playWhenReady)
        assertTrue("The paused clock must be redrawn", posted.size > before)
        val paused = posted.size
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        runUntil("playback to resume") { p.isPlaying }
        assertTrue("The running clock must be redrawn after the call", posted.size > paused)
        assertEquals(PlayerNotification.NotificationId, posted.last().notificationId)
    }

    @Test fun aStoppedPlayerDoesNotBringBackItsDismissedNotification() {
        val (p, s) = playing()
        val posted = mutableListOf<MediaNotification>()
        PlayerNotification(context).createNotification(s, ImmutableList.of(), actions) { posted += it }
        p.stop()
        shadowOf(Looper.getMainLooper()).idle()
        val before = posted.size
        p.seekTo(10_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, posted.size)
    }
}
