package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.RadioQueue
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Radio that arrives after the last song already finished (slow network) must still continue playback. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioContinuationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var exo: ExoPlayer? = null

    private fun song(id: Char) = Track("yt:${id.toString().repeat(11)}", "Song $id", "Artist $id", "https://music.youtube.com/watch?v=${id.toString().repeat(11)}")
    private fun item(t: Track) = MediaItem.Builder().setMediaId(t.id).setUri(t.url).setMediaMetadata(
        MediaMetadata.Builder().setExtras(Bundle().apply { putString("catalogue_track", t.json().toString()) }).build()).build()

    private class Silent : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "silent"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {}
        override fun isReady() = true
        override fun isEnded() = true
    }

    private fun player(): ExoPlayer {
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            // The first song is short so it really ends; recommended songs are long.
            override fun createMediaSource(mediaItem: MediaItem): MediaSource = Keep(mediaItem, SilenceMediaSource.Factory()
                .setDurationUs(if (mediaItem.mediaId == song('a').id) 200_000 else 5_000_000).setTag(mediaItem.mediaId).createMediaSource())
        }
        return ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf(Silent()) }).setMediaSourceFactory(factory).build().also { exo = it }
    }

    /** Real silence that keeps the queued item (and its catalogue extras), as the app's own source does. */
    private class Keep(private val item: MediaItem, child: MediaSource) : WrappingMediaSource(child) {
        override fun getMediaItem() = item
        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) = refreshSourceInfo(object : ForwardingTimeline(newTimeline) {
            override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long) =
                super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply { mediaItem = item }
        })
    }

    private fun runUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    @After fun release() { scope.cancel(); exo?.release() }

    @Test fun recommendationsThatArriveAfterTheLastSongEndedStartPlaying() {
        val p = player()
        val result = CompletableDeferred<List<Track>>()
        val states = mutableListOf<Pair<Boolean, String?>>()
        val radio = RadioQueue(p, scope, { true }, { _, _ -> result.await() }, ::item, { l, e -> states += l to e })
        p.setMediaItem(item(song('a'))); p.prepare(); p.play()
        radio.request(force = true)
        runUntil("the only song to end") { p.playbackState == Player.STATE_ENDED }
        result.complete(listOf(song('b'), song('c')))
        assertEquals(states.toString(), 3, p.mediaItemCount)
        runUntil("the first recommended song to play") { p.isPlaying }
        assertEquals(1, p.currentMediaItemIndex)
    }

    @Test fun aPausedFinishedQueueStaysPausedWhenRecommendationsArrive() {
        val p = player()
        val result = CompletableDeferred<List<Track>>()
        val radio = RadioQueue(p, scope, { true }, { _, _ -> result.await() }, ::item, { _, _ -> })
        p.setMediaItem(item(song('a'))); p.prepare(); p.play()
        radio.request(force = true)
        runUntil("the only song to end") { p.playbackState == Player.STATE_ENDED }
        p.pause()
        result.complete(listOf(song('b')))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertFalse(p.playWhenReady); assertFalse(p.isPlaying)
    }
}
