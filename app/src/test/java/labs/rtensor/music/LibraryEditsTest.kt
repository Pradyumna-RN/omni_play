package labs.rtensor.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryEditsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val local = Track("local:old-id", "Recording", "Me", "content://audio/document/1", source = Source.LOCAL)
    private val alias = local.copy(id = "local:${local.url}")
    private val online = Track("yt:aaaaaaaaaaa", "Song", "Artist", "https://music.youtube.com/watch?v=aaaaaaaaaaa")
    private fun event(track: Track, at: Long) = PlayEvent(track, at, 30_000, false)

    @Before fun clear() { context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun removingLocalPurgesAliasesAcrossSavedHomeHistoryQueueAndPendingPlayback() {
        val initial = MusicState(saved = listOf(local, online), history = listOf(alias, online),
            playEvents = listOf(event(alias, 2), event(local, 1), event(online, 0)),
            current = alias, loadingTrack = local, queue = listOf(local, online, alias), queueIndex = 0,
            results = listOf(alias, online), collection = listOf(local), playing = true, buffering = true,
            positionMs = 12_000, durationMs = 90_000)
        val next = initial.withoutLocalRecording(local)
        for (tracks in listOf(next.saved, next.history, next.queue, next.results)) assertEquals(listOf(online), tracks)
        assertEquals(listOf(online), next.playEvents.map { it.track })
        assertTrue(next.collection.isEmpty()); assertNull(next.current); assertNull(next.loadingTrack)
        assertFalse(next.playing); assertFalse(next.buffering); assertEquals(0L, next.positionMs)
        assertEquals(-1, next.queueIndex)
    }

    @Test fun removingAnotherQueuedImportPreservesThePlayingSongAndItsPosition() {
        val before = MusicState(current = online, playing = true, positionMs = 18_000,
            queue = listOf(local, online, alias), queueIndex = 1)
        val after = before.withoutLocalRecording(local)
        assertEquals(online, after.current); assertTrue(after.playing); assertEquals(18_000L, after.positionMs)
        assertEquals(listOf(online), after.queue); assertEquals(0, after.queueIndex)
    }

    @Test fun forgettingHistoryRemovesAllPlaysAndHomeShortcutButKeepsSavedAndPlayback() {
        val oldId = online.copy(id = "aaaaaaaaaaa")
        val before = MusicState(saved = listOf(online), history = listOf(oldId, local),
            playEvents = listOf(event(online, 3), event(oldId, 2), event(local, 1)),
            current = online, playing = true, queue = listOf(online))
        val after = before.withoutHistory(online)
        assertEquals(listOf(local), after.history); assertEquals(listOf(local), after.playEvents.map { it.track })
        assertEquals(before.saved, after.saved); assertEquals(before.queue, after.queue); assertTrue(after.playing)
        assertTrue(after.withoutHistory().history.isEmpty()); assertTrue(after.withoutHistory().playEvents.isEmpty())
    }

    @Test fun removalSurvivesRestartRejectsStaleSavedWritesAndAllowsExplicitReimport() {
        val file = File.createTempFile("user-owned-recording", ".m4a").apply { writeText("original document") }
        val recording = local.copy(url = file.toURI().toString())
        val store = LibraryStore(context)
        store.writeTracks("saved", listOf(recording, online)); store.writeTracks("history", listOf(recording))
        store.removeLocal(recording)
        val reopened = LibraryStore(context)
        assertTrue(reopened.isRemoved(recording.copy(id = "different-local-id")))
        reopened.writeTracks("saved", listOf(recording, online))
        assertEquals(listOf(online), reopened.tracks("saved")); assertTrue(reopened.tracks("history").isEmpty())
        assertEquals("original document", file.readText())
        reopened.restoreLocal(recording); reopened.writeTracks("saved", listOf(recording, online))
        assertEquals(listOf(recording, online), LibraryStore(context).tracks("saved")); file.delete()
    }

    @Test fun uniqueIdentitiesMatchAcrossSavedHistoryAndLegacyAliases() {
        val store = LibraryStore(context)
        store.writeTracks("saved", listOf(local, alias, online, online.copy(id = "aaaaaaaaaaa")))
        assertEquals(listOf(local, online), store.tracks("saved"))
        val events = listOf(event(local, 1), event(alias, 4), event(online, 2), event(online.copy(id = "aaaaaaaaaaa"), 3))
        assertEquals(listOf(alias, online.copy(id = "aaaaaaaaaaa")), ListeningHistory.latestPerSong(events).map { it.track })
    }

    @Test fun upgradingPurgesPreviouslyUnheartedImportsButKeepsRegisteredFilesAndOnlineHistory() {
        val store = LibraryStore(context)
        val kept = local.copy(id = "local:kept", url = "content://audio/document/2")
        store.writeTracks("saved", listOf(kept))
        store.writeTracks("history", listOf(alias, kept, online))
        store.reconcileImports(listOf(local, kept))
        val reopened = LibraryStore(context)
        assertTrue(reopened.isRemoved(local)); assertFalse(reopened.isRemoved(kept)); assertFalse(reopened.isRemoved(online))
        assertEquals(listOf(kept, online), reopened.tracks("history"))
        assertEquals(listOf(kept), reopened.tracks("saved"))
    }

    @Test fun anImportStartedBeforeRemovalCannotResurrectTheRecordingButAFreshImportCan() {
        val store = LibraryStore(context)
        val pendingRevision = store.localRemovalRevision
        store.removeLocal(local)
        assertFalse(store.restoreLocal(alias, pendingRevision)); assertTrue(store.isRemoved(local))
        val unrelated = local.copy(id = "local:other", url = "content://audio/document/other")
        assertTrue(store.restoreLocal(unrelated, pendingRevision))
        assertTrue(store.restoreLocal(local, store.localRemovalRevision)); assertFalse(store.isRemoved(alias))
    }

    @Test fun aDelayedWriteCannotUndoClearOrRemovalAfterRestart() {
        val file = File.createTempFile("history-race", ".json")
        val history = ListeningHistory(file)
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            val late = executor.submit { release.await(); history.replace(listOf(event(local, 1)), 1) }
            history.replace(emptyList(), 2)
            release.countDown(); late.get(5, TimeUnit.SECONDS)
            assertTrue(ListeningHistory(file).events.isEmpty())
            history.replace(listOf(event(online, 4)), 4)
            history.replace(listOf(event(local, 1), event(online, 2)), 3)
            assertEquals(listOf(online), ListeningHistory(file).events.map { it.track })
        } finally { release.countDown(); executor.shutdownNow(); file.delete() }
    }
}
