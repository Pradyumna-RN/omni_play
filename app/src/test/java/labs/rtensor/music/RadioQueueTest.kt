package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.RadioQueue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = song('a')
    private fun song(id: Char) = Track("yt:${id.toString().repeat(11)}", "Song $id", "Artist $id", "https://music.youtube.com/watch?v=${id.toString().repeat(11)}")
    private fun item(t: Track) = MediaItem.Builder().setMediaId(t.id).setUri(t.url).setMediaMetadata(
        MediaMetadata.Builder().setExtras(Bundle().apply { putString("catalogue_track", t.json().toString()) }).build()).build()
    private fun player() = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { ByteArrayDataSource(byteArrayOf()) })).build()
    private fun ids(p: ExoPlayer) = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    @Test fun providerResultsSupplyUpNextInProviderOrderWithLiveQueueDeduplication() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<List<Track>>(); val states = mutableListOf<Pair<Boolean, String?>>()
        var requested: Track? = null
        val radio = RadioQueue(p, scope, { true }, { t, exclude -> requested = t; assertEquals(listOf(seed.id), exclude.toList()); result.await() }, ::item,
            { loading, error -> states += loading to error })
        try {
            p.setMediaItem(item(seed)); p.seekTo(42000); radio.request(force = true)
            assertEquals(seed, requested); assertTrue(states.last().first)
            val manual = song('b'); p.addMediaItem(item(manual))
            val first = song('c'); val second = song('d')
            result.complete(listOf(seed.copy(id = seed.id.removePrefix("yt:")), manual, first,
                first.copy(id = first.id.removePrefix("yt:")), second, song('e').copy(source = Source.LOCAL)))
            assertEquals(listOf(seed.id, manual.id, first.id, second.id), ids(p))
            assertEquals(42000L, p.currentPosition); assertEquals(false to null, states.last())
        } finally { scope.cancel(); p.release() }
    }

    @Test fun aLateRecommendationResponseCannotEnterANewSongSession() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<List<Track>>(); val fresh = CompletableDeferred<List<Track>>()
        val states = mutableListOf<Pair<Boolean, String?>>()
        val next = song('b')
        val radio = RadioQueue(p, scope, { true }, { t, _ -> withContext(NonCancellable) { if (t.id == seed.id) old.await() else fresh.await() } }, ::item,
            { loading, error -> states += loading to error })
        try {
            p.setMediaItem(item(seed)); radio.request(true)
            radio.reset(); p.setMediaItem(item(next)); radio.request(true)
            old.complete(listOf(song('x')))
            assertEquals(listOf(next.id), ids(p)); assertTrue(states.last().first)
            fresh.complete(listOf(song('c')))
            assertEquals(listOf(next.id, song('c').id), ids(p)); assertEquals(false to null, states.last())
        } finally { old.complete(emptyList()); fresh.complete(emptyList()); scope.cancel(); p.release() }
    }

    @Test fun failureKeepsPlaybackAndRetryFetchesRecommendationsWithoutHistoryFallback() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val states = mutableListOf<Pair<Boolean, String?>>()
        var calls = 0
        val radio = RadioQueue(p, scope, { true }, { _, _ -> if (++calls == 1) error("offline") else listOf(song('b')) }, ::item,
            { loading, error -> states += loading to error })
        try {
            p.setMediaItem(item(seed)); p.seekTo(22000); p.play(); radio.request(true)
            assertEquals(listOf(seed.id), ids(p)); assertNotNull(states.last().second)
            assertTrue(p.playWhenReady); assertEquals(22000L, p.currentPosition)
            radio.retry()
            assertEquals(listOf(seed.id, song('b').id), ids(p)); assertEquals(false to null, states.last())
            assertEquals(22000L, p.currentPosition); assertTrue(p.playWhenReady)
        } finally { scope.cancel(); p.release() }
    }

    @Test fun explicitCollectionsStayOrderedAndRadioOnlyExtendsTheirEnd() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val radio = RadioQueue(p, scope, { true }, { _, _ -> calls++; listOf(song('d')) }, ::item, { _, _ -> })
        try {
            val collection = listOf(seed, song('b'), song('c'))
            p.setMediaItems(collection.map(::item)); radio.request()
            assertEquals(0, calls); assertEquals(collection.map { it.id }, ids(p))
            p.seekToDefaultPosition(1); radio.request()
            assertEquals(1, calls); assertEquals((collection + song('d')).map { it.id }, ids(p))
        } finally { scope.cancel(); p.release() }
    }

    @Test fun disablingAutoplayCancelsPendingRadioAndLocalFilesNeverRequestIt() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<List<Track>>(); var enabled = true; var calls = 0
        val radio = RadioQueue(p, scope, { enabled }, { _, _ -> calls++; pending.await() }, ::item, { _, _ -> })
        try {
            p.setMediaItem(item(seed)); radio.request(true)
            enabled = false; radio.reset(); pending.complete(listOf(song('b'))); radio.request(true)
            assertEquals(1, calls); assertEquals(listOf(seed.id), ids(p))
            enabled = true
            p.setMediaItem(item(seed.copy(id = "local:recording", url = "content://audio/recording", source = Source.LOCAL)))
            radio.request(true); assertEquals(1, calls)
        } finally { pending.complete(emptyList()); scope.cancel(); p.release() }
    }
    @Test fun switchingProviderKeepsCurrentPositionAndManualQueueButReplacesGeneratedTail() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var provider = "YouTube Music"
        val radio = RadioQueue(p, scope, { true }, { _, _ -> listOf(if (provider == "Spotify") song('d') else song('c')) }, ::item,
            { _, _ -> }, { provider })
        try {
            p.setMediaItems(listOf(seed, song('b')).map(::item)); p.seekTo(23000); p.play(); radio.request(true)
            assertEquals(listOf(seed.id, song('b').id, song('c').id), ids(p))
            assertEquals("YouTube Music", p.getMediaItemAt(2).mediaMetadata.extras!!.getString("music_radio_provider"))
            provider = "Spotify"; radio.changeProvider()
            assertEquals(listOf(seed.id, song('b').id, song('d').id), ids(p))
            assertEquals(23000L, p.currentPosition); assertTrue(p.playWhenReady)
            assertEquals("Spotify", p.getMediaItemAt(2).mediaMetadata.extras!!.getString("music_radio_provider"))
            assertNull(p.getMediaItemAt(1).mediaMetadata.extras!!.getString("music_radio_provider"))
        } finally { scope.cancel(); p.release() }
    }
    @Test fun oldProviderCannotAppendAfterSwitchEvenWhenRequestIgnoresCancellation() {
        val p = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val late = CompletableDeferred<List<Track>>(); var provider = "YouTube Music"
        val radio = RadioQueue(p, scope, { true }, { _, _ -> if (provider == "Spotify") listOf(song('d')) else withContext(NonCancellable) { late.await() } }, ::item,
            { _, _ -> }, { provider })
        try {
            p.setMediaItem(item(seed)); radio.request(true)
            provider = "Spotify"; radio.changeProvider(); late.complete(listOf(song('x')))
            assertEquals(listOf(seed.id, song('d').id), ids(p))
        } finally { late.complete(emptyList()); scope.cancel(); p.release() }
    }

}
