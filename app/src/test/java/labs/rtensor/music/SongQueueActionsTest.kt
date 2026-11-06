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
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.RadioQueue
import labs.rtensor.music.playback.SongQueueActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongQueueActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun song(letter: Char) = Track("yt:${letter.toString().repeat(11)}", "Song $letter", "Artist", "https://www.youtube.com/watch?v=${letter.toString().repeat(11)}")
    private fun item(t: Track, generated: Boolean = false) = MediaItem.Builder().setMediaId(t.id).setUri(t.url).setMediaMetadata(
        MediaMetadata.Builder().setExtras(Bundle().apply {
            putString("catalogue_track", t.json().toString())
            if (generated) { putString("music_radio_provider", "YouTube Music"); putString("music_radio_seed", song('a').libraryKey()) }
        }).build()).build()
    private fun player() = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { ByteArrayDataSource(byteArrayOf()) })).build()
    private fun ids(player: ExoPlayer) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Test fun manualChoicesComeBeforeTheRadioTailWithoutChangingPlayback() {
        val player = player()
        try {
            player.setMediaItems(listOf(item(song('a')), item(song('b')), item(song('c'), true), item(song('d'), true)))
            player.seekTo(32_000); player.play()
            SongQueueActions.add(player, item(song('e')), false)
            assertEquals(listOf('a', 'b', 'e', 'c', 'd').map { song(it).id }, ids(player))
            SongQueueActions.add(player, item(song('d')), true)
            assertEquals(listOf('a', 'd', 'b', 'e', 'c').map { song(it).id }, ids(player))
            assertEquals(32_000L, player.currentPosition); assertTrue(player.playWhenReady)
            assertNull(player.getMediaItemAt(1).mediaMetadata.extras?.getString("music_radio_provider"))
        } finally { player.release() }
    }
    @Test fun aManualNextSongStartsItsOwnRadioAndRetainsOtherExplicitChoices() {
        val player = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<Track>()
        val radio = RadioQueue(player, scope, { true }, { seed, _ -> requested += seed; listOf(song('x'), song('y'), song('z'), song('w')) }, ::item, { _, _ -> })
        try {
            player.setMediaItems(listOf(item(song('a')), item(song('b')), item(song('c'), true), item(song('d'), true)))
            player.seekTo(1, 4_000); radio.followCurrent()
            assertEquals(listOf(song('b')), requested)
            assertEquals(listOf('a', 'b', 'x', 'y', 'z', 'w').map { song(it).id }, ids(player))
            assertEquals(4_000L, player.currentPosition)
            player.seekTo(2, 0); radio.followCurrent()
            assertEquals(1, requested.size) // a generated song retains its provider's sequence
        } finally { scope.cancel(); player.release() }
    }
    @Test fun anOldInFlightRadioCannotSupplyTheNewManuallyChosenSong() {
        val player = player(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<List<Track>>()
        val fresh = CompletableDeferred<List<Track>>()
        val radio = RadioQueue(player, scope, { true }, { seed, _ -> withContext(NonCancellable) { if (seed == song('a')) old.await() else fresh.await() } }, ::item, { _, _ -> })
        try {
            player.setMediaItem(item(song('a'))); radio.request(true)
            SongQueueActions.add(player, item(song('b')), true)
            player.seekTo(1, 0); radio.followCurrent()
            old.complete(listOf(song('c')))
            assertEquals(listOf(song('a').id, song('b').id), ids(player))
            fresh.complete(listOf(song('x'), song('y')))
            assertEquals(listOf('a', 'b', 'x', 'y').map { song(it).id }, ids(player))
        } finally { old.complete(emptyList()); fresh.complete(emptyList()); scope.cancel(); player.release() }
    }
}
