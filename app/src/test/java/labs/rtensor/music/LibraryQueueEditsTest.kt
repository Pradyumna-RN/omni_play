package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.LibraryQueueEdits
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryQueueEditsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val local = Track("local:1", "Recording", "Me", "content://audio/document/1", source = Source.LOCAL)
    private val online = Track("yt:aaaaaaaaaaa", "Song", "Artist", "https://music.youtube.com/watch?v=aaaaaaaaaaa")
    private fun item(track: Track, uri: String = track.url) = MediaItem.Builder().setMediaId(track.id).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("catalogue_track", track.json().toString()) }).build()).build()
    private fun player(factory: DataSource.Factory = DataSource.Factory { ByteArrayDataSource(byteArrayOf()) }) =
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()

    @Test fun deletingCurrentImportStopsPlaybackAndRemovesEveryQueuedAlias() {
        val player = player()
        try {
            player.setMediaItems(listOf(item(local), item(online), item(local.copy(id = "local:alias"))))
            player.play()
            LibraryQueueEdits.reconcile(player, { it.source == Source.LOCAL && it.url == local.url }, setOf(local.url), ::item)
            assertEquals(1, player.mediaItemCount); assertEquals(online.id, player.currentMediaItem!!.mediaId)
            assertFalse("Removing the playing import must stop it, not start another song", player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun deletingTheOnlyImportLeavesNoPlayableOrRestorableMediaItem() {
        val player = player()
        try {
            player.setMediaItem(item(local)); player.play()
            LibraryQueueEdits.reconcile(player, { it.url == local.url }, setOf(local.url), ::item)
            assertEquals(0, player.mediaItemCount); assertNull(player.currentMediaItem)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun deletingAQueuedImportDoesNotInterruptThePlayingOnlineSong() {
        val player = player()
        try {
            player.setMediaItems(listOf(item(online), item(local))); player.seekTo(12_345); player.play()
            LibraryQueueEdits.reconcile(player, { it.url == local.url }, setOf(local.url), ::item)
            assertEquals(1, player.mediaItemCount); assertEquals(online.id, player.currentMediaItem!!.mediaId)
            assertEquals(12_345L, player.currentPosition); assertTrue(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun clearingFolderReplacesPinnedLocalMastersAndPreservesSongPositionAndPause() {
        val player = player()
        val second = online.copy(id = "yt:bbbbbbbbbbb", url = "https://music.youtube.com/watch?v=bbbbbbbbbbb")
        try {
            player.setMediaItems(listOf(item(online, local.url), item(second, local.url)))
            player.seekTo(9_876)
            LibraryQueueEdits.reconcile(player, { false }, setOf(local.url), ::item)
            assertEquals(online.url, player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(second.url, player.getMediaItemAt(1).localConfiguration!!.uri.toString())
            assertEquals(9_876L, player.currentPosition); assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun removalCancelsAnAlreadyOpenLocalReadInsteadOfOnlyHidingTheSong() {
        val opened = CountDownLatch(1); val closed = CountDownLatch(1); val unblock = CountDownLatch(1)
        val data = ByteArrayDataSource(checkNotNull(javaClass.getResourceAsStream("/audio/seek-fixture.m4a")).readBytes())
        val player = player(DataSource.Factory {
            object : DataSource by data {
                override fun open(spec: DataSpec): Long = data.open(spec).also { opened.countDown() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    try { unblock.await() } catch (e: InterruptedException) { throw IOException("Cancelled local read", e) }
                    return data.read(buffer, offset, length)
                }
                override fun close() { data.close(); closed.countDown() }
            }
        })
        try {
            player.setMediaItem(item(local)); player.prepare(); player.play()
            assertTrue("Real Media3 loader must have opened the local source", opened.await(5, TimeUnit.SECONDS))
            LibraryQueueEdits.reconcile(player, { it.url == local.url }, setOf(local.url), ::item)
            assertTrue("Removing the import must close its in-flight read", closed.await(5, TimeUnit.SECONDS))
            assertNull(player.currentMediaItem); assertFalse(player.playWhenReady)
        } finally { unblock.countDown(); player.release() }
    }
}
