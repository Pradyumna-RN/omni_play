package labs.rtensor.music.spotify

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.*
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.*
import labs.rtensor.music.playback.MusicPlaybackService
import labs.rtensor.music.playback.ProviderMediaSourceFactory
import labs.rtensor.music.sources.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpotifyPlaybackRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = SpotifyItem("a".repeat(22), "track", "Afterimage", "Mira", durationMs = 180000).track()
    private val matched = catalogue.copy(id = "yt:abcdefghijk", url = "https://www.youtube.com/watch?v=abcdefghijk")
    private fun registry() = PlaybackSources(SourceSettings(context, AccountSession(context, "spotify-route-fixture") { SecretKeySpec(ByteArray(32) { 7 }, "AES") }))
    private fun item(master: Master? = null) = MediaItem.Builder().setMediaId(catalogue.id).setUri(master?.uri ?: catalogue.url)
        .setCustomCacheKey("queue-id").setMediaMetadata(MediaMetadata.Builder().setTitle(catalogue.title).setArtist(catalogue.artist)
            .setExtras(Bundle().apply { putString("catalogue_track", catalogue.json().toString()); putString("fallback_route", "YouTube"); master?.let { putString("local_master", it.json().toString()) } }).build()).build()
    private fun factory(seen: MutableList<MediaItem>): MediaSource.Factory {
        val base = DefaultMediaSourceFactory(context)
        return object : MediaSource.Factory by base {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                seen += mediaItem
                return SilenceMediaSource.Factory().setDurationUs(180_000_000).createMediaSource()
            }
        }
    }
    private fun prepare(source: MediaSource, checks: (Timeline?) -> Unit) {
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null, PlayerId.UNSET)
        try {
            val until = System.nanoTime() + 5_000_000_000
            while (timeline == null && System.nanoTime() < until) {
                shadowOf(Looper.getMainLooper()).idle()
                try { source.maybeThrowSourceInfoRefreshError() } catch (failure: java.io.IOException) { throw failure }
                Thread.sleep(10)
            }
            source.maybeThrowSourceInfoRefreshError(); checks(timeline)
        } finally { source.releaseSource(caller) }
    }
    @Test fun spotifySelectionResolvesOnlyWhenPreparedEvenWithNoOtherProviderConfigured() {
        val physical = CopyOnWriteArrayList<MediaItem>(); var resolutions = 0
        val fake = factory(physical)
        val source = ProviderMediaSourceFactory(fake, fake, registry()) { input -> assertEquals(catalogue, input); resolutions++; matched }.createMediaSource(item())
        assertEquals(0, resolutions); assertTrue(physical.isEmpty())
        prepare(source) { timeline ->
            assertNotNull(timeline); assertEquals(1, resolutions)
            val delivered = physical.single()
            assertEquals(matched.url, delivered.localConfiguration!!.uri.toString())
            assertEquals("queue-id", delivered.localConfiguration!!.customCacheKey)
            assertEquals(catalogue.id, delivered.mediaId)
            val restored = Track.fromJson(org.json.JSONObject(delivered.mediaMetadata.extras!!.getString("catalogue_track")!!))
            assertEquals(catalogue.libraryKey(), restored.libraryKey()); assertEquals(matched.url, restored.url)
        }
    }
    @Test fun selectedLocalLosslessRecordingSkipsYoutubeMatchingEntirely() {
        val physical = CopyOnWriteArrayList<MediaItem>(); val fake = factory(physical)
        val master = Master("content://owned/afterimage.flac", catalogue.title, catalogue.artist, 180000, 6, 96000, 24, "audio/flac", false)
        val source = ProviderMediaSourceFactory(fake, fake, registry()) { error("A matching local file needs no YouTube search") }.createMediaSource(item(master))
        prepare(source) { assertNotNull(it); assertEquals(master.uri, physical.single().localConfiguration!!.uri.toString()) }
    }
    @Test fun absentMatchFailsWithActionableMessageBeforeAnyAudioIsRequested() {
        val physical = CopyOnWriteArrayList<MediaItem>(); val fake = factory(physical)
        val source = ProviderMediaSourceFactory(fake, fake, registry()) { throw SourceException("SPOTIFY_MATCH", "No verified audio match for this Spotify recording.") }.createMediaSource(item())
        val error = runCatching { prepare(source) {} }.exceptionOrNull()
        assertTrue(error is java.io.IOException); assertTrue(error!!.message!!.contains("No verified audio match")); assertTrue(physical.isEmpty())
    }
    @Test fun sessionAcceptsOnlyCanonicalSpotifyPlaceholdersWithValidDiscoveryMetadata() {
        assertTrue(MusicPlaybackService.spotifyCatalogueItem(item()))
        for (url in listOf("http://open.spotify.com/track/${catalogue.spotifyId}", "https://open.spotify.com.attacker.test/track/${catalogue.spotifyId}", "https://open.spotify.com/track/${catalogue.spotifyId}?token=x", "https://open.spotify.com/track/${"b".repeat(22)}")) {
            assertFalse(url, MusicPlaybackService.spotifyCatalogueItem(item().buildUpon().setUri(url).build()))
        }
        assertFalse(MusicPlaybackService.spotifyCatalogueItem(item().buildUpon().setMediaMetadata(MediaMetadata.EMPTY).build()))
    }
    @Test fun matchingFailureSurvivesMediaSessionCauseSerializationAndClearsForTheNextSong() {
        val expected = SourceException("SPOTIFY_MATCH", "No verified audio match for this Spotify recording.")
        labs.rtensor.music.playback.AudioPipeline.reportFailure(java.io.IOException("Source error", expected))
        val original = androidx.media3.common.PlaybackException("Source error", expected, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val received = androidx.media3.common.PlaybackException.fromBundle(original.toBundle())
        assertFalse(generateSequence(received as Throwable) { it.cause }.any { it is SourceException })
        assertSame(expected, labs.rtensor.music.playback.AudioPipeline.catalogueProblem)
        labs.rtensor.music.playback.AudioPipeline.clearReport()
        assertNull(labs.rtensor.music.playback.AudioPipeline.catalogueProblem)
        labs.rtensor.music.playback.AudioPipeline.reportFailure(java.io.IOException("offline"))
        assertNull(labs.rtensor.music.playback.AudioPipeline.catalogueProblem)
    }

}
