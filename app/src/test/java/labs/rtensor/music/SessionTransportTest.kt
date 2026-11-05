package labs.rtensor.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.playback.SessionPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Skips that arrive through the media session (notification, lock screen, headset buttons,
 * the car) after a song failed. Items whose id starts with "bad" cannot be parsed, as an
 * expired or unreadable stream; the others are real silence that plays.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionTransportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var controller: MediaController? = null

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id").build()

    private fun player(): ExoPlayer {
        val broken = DefaultMediaSourceFactory(DataSource.Factory { ByteArrayDataSource(byteArrayOf(0, 1, 2, 3)) })
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                if (mediaItem.mediaId.startsWith("bad")) broken.createMediaSource(mediaItem)
                else SilenceMediaSource.Factory().setDurationUs(5_000_000).setTag(mediaItem.mediaId).createMediaSource()
        }
        return ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf(Silent()) }).setMediaSourceFactory(factory).build().also { exo = it }
    }

    private class Silent : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "silent"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {}
        override fun isReady() = true
        override fun isEnded() = true
    }

    private fun runUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    /** A session over the same player wrapper the playback service gives Media3, and an outside controller. */
    private fun failedSession(items: List<MediaItem>, start: Int): Pair<ExoPlayer, MediaController> {
        val p = player()
        p.setMediaItems(items, start, 0); p.prepare(); p.play()
        runUntil("a playback error") { p.playerError != null }
        val s = MediaSession.Builder(context, SessionPlayer(p)).setId("transport-${System.nanoTime()}").build().also { session = it }
        val future = MediaController.Builder(context, s.token).buildAsync()
        runUntil("the controller to connect") { future.isDone }
        return p to future.get().also { controller = it }
    }

    @After fun release() {
        controller?.release(); session?.release(); exo?.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nextFromTheNotificationAfterAFailedSongPlaysTheNextSong() {
        val (p, c) = failedSession(listOf(item("bad"), item("two")), 0)
        c.seekToNext()
        runUntil("the next song to play") { p.isPlaying }
        assertEquals(1, p.currentMediaItemIndex); assertNull(p.playerError)
    }

    @Test fun previousFromAHeadsetAfterAFailedSongPlaysThePreviousSong() {
        val (p, c) = failedSession(listOf(item("one"), item("bad")), 1)
        c.seekToPreviousMediaItem()
        runUntil("the previous song to play") { p.isPlaying }
        assertEquals(0, p.currentMediaItemIndex)
    }

    @Test fun choosingAQueueItemInTheCarAfterAFailedSongPlaysIt() {
        val (p, c) = failedSession(listOf(item("bad"), item("two"), item("three")), 0)
        c.seekToDefaultPosition(2)
        runUntil("the chosen song to play") { p.isPlaying }
        assertEquals(2, p.currentMediaItemIndex)
    }

    @Test fun aStoppedPlayerIsNotRestartedBySkipping() {
        val p = player()
        p.setMediaItems(listOf(item("one"), item("two"))); p.prepare(); p.play()
        runUntil("playback") { p.isPlaying }
        p.stop() // Dismissing the notification.
        val wrapped = SessionPlayer(p)
        wrapped.seekToNext()
        assertEquals(1, p.currentMediaItemIndex)
        assertEquals("No error: the player stays stopped until Play", Player.STATE_IDLE, p.playbackState)
    }
}
