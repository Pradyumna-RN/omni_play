package labs.rtensor.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.playback.PlayerTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Transport controls against a real ExoPlayer. Items whose id starts with "bad" fail to
 * parse (as an expired or unreadable stream does); "blank" items hold no audio, so the player
 * genuinely reaches ENDED; every other item is real silence that genuinely plays.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerTransportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<ExoPlayer>()

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id").build()

    private fun player(silenceUs: Long = 300_000): ExoPlayer {
        val broken = DefaultMediaSourceFactory(DataSource.Factory { ByteArrayDataSource(byteArrayOf(0, 1, 2, 3)) })
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                if (mediaItem.mediaId.startsWith("bad")) broken.createMediaSource(mediaItem)
                else SilenceMediaSource.Factory().setDurationUs(if (mediaItem.mediaId.startsWith("blank")) 200_000 else silenceUs).setTag(mediaItem.mediaId).createMediaSource()
        }
        // A renderer that takes no track: the media clock alone advances, so short items reliably reach ENDED here.
        return ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf(Silent()) }).setMediaSourceFactory(factory).build().also { players += it }
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
            // Robolectric's clock only moves when told to; the playback thread runs for real.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    private fun failed(p: ExoPlayer) { p.prepare(); p.play(); runUntil("a playback error") { p.playerError != null } }

    @After fun release() { players.forEach { it.release() } }

    @Test fun playAfterAPlaybackErrorRetriesOnTheFirstTap() {
        val p = player(); p.setMediaItem(item("bad")); failed(p)
        assertEquals(Player.STATE_IDLE, p.playbackState)
        var retries = 0
        PlayerTransport.toggle(p) { retries++ }
        assertEquals("One tap on Play after an error must retry", 1, retries)
    }

    @Test fun playAtTheEndOfTheQueueReplaysOnTheFirstTap() {
        val p = player(); p.setMediaItems(listOf(item("blank-one"), item("blank-two")), 1, 0); p.prepare(); p.play()
        runUntil("the queue to end") { p.playbackState == Player.STATE_ENDED }
        assertTrue(p.playWhenReady)
        PlayerTransport.toggle(p) { fail("A finished queue is not an error") }
        assertTrue(p.playWhenReady)
        assertEquals(1, p.currentMediaItemIndex)
        assertNotEquals("Play must leave the ended state", Player.STATE_ENDED, p.playbackState)
    }

    @Test fun playAfterTheNotificationWasDismissedPreparesAgain() {
        val p = player(silenceUs = 5_000_000); p.setMediaItem(item("one")); p.prepare(); p.play()
        runUntil("playback") { p.isPlaying }
        p.stop() // Dismissing the notification sends COMMAND_STOP.
        runUntil("idle") { p.playbackState == Player.STATE_IDLE }
        PlayerTransport.toggle(p) { fail("A stopped player has no error") }
        runUntil("playback to resume") { p.isPlaying }
    }

    @Test fun pauseAndResumeKeepTheQueueAndPosition() {
        val p = player(silenceUs = 5_000_000); p.setMediaItems(listOf(item("one"), item("two"))); p.prepare(); p.play()
        runUntil("playback") { p.isPlaying }
        PlayerTransport.toggle(p) { fail("No error") }
        assertFalse(p.playWhenReady)
        PlayerTransport.toggle(p) { fail("No error") }
        assertTrue(p.playWhenReady); assertEquals(2, p.mediaItemCount); assertEquals(0, p.currentMediaItemIndex)
    }

    @Test fun pausingWhileBufferingIsHonoured() {
        val p = player(silenceUs = 5_000_000); p.setMediaItem(item("one")); p.prepare(); p.play()
        PlayerTransport.toggle(p) { fail("No error") }
        assertFalse(p.playWhenReady)
    }

    @Test fun nextAfterAFailedSongStartsTheNextSong() {
        val p = player(silenceUs = 5_000_000); p.setMediaItems(listOf(item("bad"), item("two"))); failed(p)
        PlayerTransport.next(p)
        assertEquals(1, p.currentMediaItemIndex)
        runUntil("the next song to play") { p.isPlaying }
        assertNull(p.playerError)
    }

    @Test fun previousAfterAFailedSongStartsThePreviousSong() {
        val p = player(silenceUs = 5_000_000); p.setMediaItems(listOf(item("one"), item("bad")), 1, 0); failed(p)
        PlayerTransport.previous(p, knownDurationMs = 0)
        assertEquals(0, p.currentMediaItemIndex)
        runUntil("the previous song to play") { p.isPlaying }
    }

    @Test fun choosingAQueuedSongAfterAFailureStartsIt() {
        val p = player(silenceUs = 5_000_000); p.setMediaItems(listOf(item("bad"), item("two"), item("three"))); failed(p)
        PlayerTransport.skipTo(p, 2)
        assertEquals(2, p.currentMediaItemIndex)
        runUntil("the chosen song to play") { p.isPlaying }
    }

    @Test fun skipToIgnoresIndexesOutsideTheQueue() {
        val p = player(); p.setMediaItems(listOf(item("one"), item("two")))
        PlayerTransport.skipTo(p, 2); PlayerTransport.skipTo(p, -1)
        assertEquals(0, p.currentMediaItemIndex); assertFalse(p.playWhenReady)
    }

    @Test fun nextOnTheLastSongDoesNothing() {
        val p = player(); p.setMediaItems(listOf(item("one"), item("two")), 1, 1_000)
        PlayerTransport.next(p)
        assertEquals(1, p.currentMediaItemIndex); assertEquals(1_000L, p.currentPosition)
    }

    @Test fun previousRestartsAfterThreeSecondsAndOtherwiseGoesBack() {
        val p = player(); p.setMediaItems(listOf(item("one"), item("two")), 1, 4_000)
        PlayerTransport.previous(p, knownDurationMs = 10_000)
        assertEquals(1, p.currentMediaItemIndex); assertEquals(0L, p.currentPosition)
        PlayerTransport.previous(p, knownDurationMs = 10_000)
        assertEquals(0, p.currentMediaItemIndex)
        PlayerTransport.previous(p, knownDurationMs = 10_000)
        assertEquals("The first song restarts", 0, p.currentMediaItemIndex); assertEquals(0L, p.currentPosition)
    }

    @Test fun seekIsClampedToTheKnownDuration() {
        val p = player(); p.setMediaItem(item("one"))
        PlayerTransport.seek(p, 99_000, knownDurationMs = 10_000); assertEquals(10_000L, p.currentPosition)
        PlayerTransport.seek(p, -5, knownDurationMs = 10_000); assertEquals(0L, p.currentPosition)
    }

    @Test fun seekBeforeTheDurationIsKnownIsNotDiscarded() {
        val p = player(); p.setMediaItem(item("one"))
        PlayerTransport.seek(p, 30_000, knownDurationMs = 0)
        assertEquals("An unknown duration must not turn every seek into a restart", 30_000L, p.currentPosition)
    }
}
