package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.playback.ProviderMediaSourceFactory
import labs.rtensor.music.sources.PlaybackSources
import labs.rtensor.music.sources.SourceSettings
import labs.rtensor.music.spotify.SpotifyItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

/** Late YouTube metadata (an artist) replaces the current media item. With a provider-routed
 * song that used to rebuild the source: the song restarted from 0:00 and resolved again. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderMetadataUpdateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = SpotifyItem("c".repeat(22), "track", "Afterimage", "", durationMs = 60000).track()
    private val matched = catalogue.copy(id = "yt:abcdefghijk", url = "https://www.youtube.com/watch?v=abcdefghijk")
    private val item = MediaItem.Builder().setMediaId(catalogue.id).setUri(catalogue.url).setCustomCacheKey("queue-id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(catalogue.title)
            .setExtras(Bundle().apply { putString("catalogue_track", catalogue.json().toString()); putString("fallback_route", "YouTube") }).build()).build()

    private class Silent : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "silent"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {}
        override fun isReady() = true
        override fun isEnded() = true
    }

    private fun advance(ms: Long) {
        repeat((ms / 20).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(2) }
    }

    private fun player(children: MutableList<MediaItem>, resolutions: () -> Unit): ExoPlayer {
        val base = DefaultMediaSourceFactory(context)
        val child = object : MediaSource.Factory by base {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                children += mediaItem
                return SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource()
            }
        }
        val sources = PlaybackSources(SourceSettings(context, AccountSession(context, "metadata-update-fixture") { SecretKeySpec(ByteArray(32) { 3 }, "AES") }))
        val factory = ProviderMediaSourceFactory(child, child, sources) { resolutions(); matched }
        return ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf(Silent()) }).setMediaSourceFactory(factory).build()
    }

    private fun withArtist(from: MediaItem, artist: String) = from.buildUpon()
        .setMediaMetadata(from.mediaMetadata.buildUpon().setArtist(artist).build()).build()

    @Test fun artistFoundWhilePlayingDoesNotRestartOrReResolveTheSong() {
        val children = CopyOnWriteArrayList<MediaItem>(); var resolutions = 0
        val p = player(children) { resolutions++ }
        try {
            p.setMediaItem(item); p.prepare(); p.play()
            advance(3_000)
            assertTrue(p.isPlaying)
            val before = p.currentPosition
            assertTrue("fixture should have played for a while", before > 1_000)
            // The ViewModel rebuilds from the controller's current item, i.e. the displayed one.
            p.replaceMediaItem(0, withArtist(checkNotNull(p.currentMediaItem), "Mira"))
            assertTrue("Position must survive a metadata edit", p.currentPosition >= before)
            advance(400)
            assertEquals("Mira", p.mediaMetadata.artist?.toString())
            assertTrue(p.currentPosition >= before)
            assertEquals("The audio must not be resolved again", 1, resolutions)
            assertEquals("The child source must not be rebuilt", 1, children.size)
        } finally { p.release() }
    }

    @Test fun aNewSourceAttemptStillRebuildsTheSource() {
        val children = CopyOnWriteArrayList<MediaItem>()
        val p = player(children) {}
        try {
            p.setMediaItem(item); p.prepare(); p.play()
            advance(1_000)
            val current = checkNotNull(p.currentMediaItem)
            val extras = Bundle(current.mediaMetadata.extras).apply { putInt("source_attempt", 1) }
            p.replaceMediaItem(0, current.buildUpon().setMediaMetadata(current.mediaMetadata.buildUpon().setExtras(extras).build()).build())
            advance(400)
            assertEquals("Source recovery depends on recreating the child", 2, children.size)
        } finally { p.release() }
    }
}
