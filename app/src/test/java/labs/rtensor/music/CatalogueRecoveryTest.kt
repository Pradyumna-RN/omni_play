package labs.rtensor.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.LocalMasters
import labs.rtensor.music.data.SourceException
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.SourceConnection
import labs.rtensor.music.spotify.SpotifyCatalogueException
import labs.rtensor.music.updates.AppRelease
import labs.rtensor.music.updates.UpdateState
import labs.rtensor.music.updates.checked
import labs.rtensor.music.updates.updateFailureMessage
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.UnknownHostException

/** Recovery paths for catalogue pages, account sign-out, updates, source setup and owned folders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogueRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun song(n: Int) = Track("yt:song${n.toString().padStart(7, '0')}", "Song $n", "Artist", "https://music.youtube.com/watch?v=song$n")

    @Before fun clean() {
        File(context.filesDir, "masters.json").delete()
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit()
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun clearingTheSearchKeepsAPlaybackErrorButDropsASearchError() {
        val playback = MusicState(query = "abc", searched = true, error = "Playback failed", errorDiagnostic = "d", errorOrigin = ErrorOrigin.PLAYBACK).clearedSearch()
        assertEquals("", playback.query); assertFalse(playback.searched)
        assertEquals("Playback failed", playback.error); assertEquals("d", playback.errorDiagnostic)
        val search = MusicState(query = "abc", searched = true, error = CatalogueMessages.SEARCH, errorOrigin = ErrorOrigin.SEARCH).clearedSearch()
        assertNull(search.error); assertEquals("", search.errorDiagnostic)
        assertEquals(ErrorOrigin.COLLECTION, MusicState().collectionPageFailed(0, "x").errorOrigin)
    }

    @Test fun aFailedLoadMoreKeepsLoadedSongsAndCanBeTappedAgain() {
        val loaded = MusicState(collection = listOf(song(1), song(2)), collectionBusy = true)
        val failed = loaded.collectionPageFailed(50, CatalogueMessages.COLLECTION)
        assertEquals(listOf(song(1), song(2)), failed.collection)
        assertTrue("Load more must stay available to retry the page", failed.collectionHasMore)
        assertFalse(failed.collectionBusy); assertEquals(CatalogueMessages.COLLECTION, failed.error)
        // A failed first page has nothing to continue from.
        assertFalse(MusicState(collectionBusy = true).collectionPageFailed(0, "x").collectionHasMore)
    }

    @Test fun pagesAppendWithoutDuplicatesAndAFirstPageReplacesTheList() {
        val first = MusicState(collection = listOf(song(9))).collectionPageLoaded(0, listOf(song(1), song(2)), hasMore = true)
        assertEquals(listOf(song(1), song(2)), first.collection); assertTrue(first.collectionHasMore)
        val second = first.collectionPageLoaded(2, listOf(song(2), song(3)), hasMore = false)
        assertEquals(listOf(song(1), song(2), song(3)), second.collection)
        assertFalse(second.collectionHasMore); assertFalse(second.collectionBusy)
    }

    @Test fun errorsDescribeTheScreenThatFailedButKeepProviderExplanations() {
        assertEquals("No results.", catalogueErrorMessage(SourceException("EMPTY", "No results."), CatalogueMessages.SEARCH))
        assertEquals(SpotifyCatalogueException("AUTH_REQUIRED").message, catalogueErrorMessage(SpotifyCatalogueException("AUTH_REQUIRED"), CatalogueMessages.FEED))
        // A parse failure during search is not "this recording".
        assertEquals(CatalogueMessages.SEARCH, catalogueErrorMessage(JSONException("No value for tracks"), CatalogueMessages.SEARCH))
        assertEquals(CatalogueMessages.COLLECTION, catalogueErrorMessage(SourceException("X", ""), CatalogueMessages.COLLECTION))
    }

    @Test fun choosingAGoogleProfileWithoutAYoutubeAccountDoesNotSignOutOrStopPlayback() {
        assertFalse(youtubeSignOutNeeded(AccountState(), storedSession = false))
        assertFalse(youtubeSignOutNeeded(AccountState(error = "Reconnect"), storedSession = false))
        assertTrue(youtubeSignOutNeeded(AccountState(connected = true), storedSession = false))
        assertTrue(youtubeSignOutNeeded(AccountState(connecting = true), storedSession = false))
        // A stored session may not have reported "connected" yet after a cold start.
        assertTrue(youtubeSignOutNeeded(AccountState(), storedSession = true))
    }

    @Test fun storedSessionIsDetectedWithoutDecrypting() {
        val vault = AccountSession(context, "catalogue-recovery-test") { error("The key must not be needed to check presence") }
        vault.clear(); assertFalse(vault.isStored())
        context.getSharedPreferences("catalogue-recovery-test", Context.MODE_PRIVATE).edit().putString("credentials", "sealed").commit()
        assertTrue(vault.isStored())
        vault.clear(); assertFalse(vault.isStored())
    }

    @Test fun recheckingKeepsAVerifiedDownloadOfTheSameRelease() {
        val release = AppRelease("0.3.33", "https://github.com/a.apk", "https://github.com/SHA256SUMS", "music-debug.apk", 10)
        val ready = UpdateState(release = release, apkPath = "/cache/updates/music-update.apk", message = "Verified update. Ready to install.")
        val again = ready.copy(checking = true).checked(release) { true }
        assertEquals("/cache/updates/music-update.apk", again.apkPath); assertFalse(again.checking)
        assertEquals("Verified update. Ready to install.", again.message)
        // A newer release, a deleted file or no release at all needs a fresh download.
        assertNull(ready.checked(release.copy(version = "0.3.34")) { true }.apkPath)
        assertNull(ready.checked(release) { false }.apkPath)
        assertEquals("You’re up to date.", ready.checked(null) { true }.message)
        assertEquals("Version 0.3.33 is available.", UpdateState().checked(release).message)
    }

    @Test fun updateFailuresNeverShowLibraryInternals() {
        assertEquals("Could not reach GitHub. Check your connection and try again.",
            updateFailureMessage(UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname"), "fallback"))
        assertEquals("fallback", updateFailureMessage(IllegalArgumentException("Failed requirement."), "fallback"))
        assertEquals("fallback", updateFailureMessage(JSONException("Value <html> of type java.lang.String"), "fallback"))
        assertEquals("The update checksum did not match. The download was discarded.",
            updateFailureMessage(IllegalStateException("The update checksum did not match. The download was discarded."), "fallback"))
    }

    @Test fun anInvalidServerAddressExplainsItselfInTheSourceForm() {
        // connectSource shows IllegalArgumentException messages; anything else becomes a generic connection failure.
        val failure = assertThrows(IllegalArgumentException::class.java) { SourceConnection.validateUrl("not a server") }
        assertEquals("Enter a valid HTTPS server URL.", failure.message)
        assertThrows(IllegalArgumentException::class.java) { SourceConnection.validateUrl("http://example.test") }
    }

    @Test fun aFolderWhoseGrantIsGoneIsNotUsedForPlaybackAfterRestart() {
        val tree = "content://recordings/tree/owned"
        File(context.filesDir, "masters.json").writeText("""[{"uri":"$tree/document/a.flac","title":"Song","artist":"Artist","mime":"audio/flac","channels":2,"sampleRate":96000}]""")
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().putString("masters_folder", tree).commit()
        val revoked = LocalMasters(context)
        assertTrue(revoked.accessLost); assertTrue(revoked.entries.isEmpty()); assertEquals(tree, revoked.folder)

        context.contentResolver.takePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val granted = LocalMasters(context)
        assertFalse(granted.accessLost); assertEquals(1, granted.entries.size)
        granted.clear(); assertFalse(granted.accessLost)
    }

    @Test fun lastingAccessIsCheckedForImportedDocumentsOnly() {
        val document = "content://recordings/document/owned.flac"
        assertFalse(LocalMasters.hasReadGrant(context, document))
        context.contentResolver.takePersistableUriPermission(Uri.parse(document), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(LocalMasters.hasReadGrant(context, document))
        assertTrue("App-private files need no grant", LocalMasters.hasReadGrant(context, Uri.fromFile(File(context.cacheDir, "x.flac")).toString()))
    }

    @Test fun choosingADifferentFolderGivesBackTheOldFolderGrant() {
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = android.database.MatrixCursor(projection!!)
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "recordings" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("recordings", provider)
        val old = Uri.parse("content://recordings/tree/old"); val next = Uri.parse("content://recordings/tree/next")
        listOf(old, next).forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val masters = LocalMasters(context)
        masters.index(old); masters.index(old)
        assertTrue("Rescanning the same folder keeps its grant", LocalMasters.hasReadGrant(context, old.toString()))
        masters.index(next)
        assertFalse(LocalMasters.hasReadGrant(context, old.toString())); assertTrue(LocalMasters.hasReadGrant(context, next.toString()))
        assertEquals(next.toString(), LocalMasters(context).folder); assertFalse(LocalMasters(context).accessLost)
    }

    @Test fun releasingAFolderGrantRemovesOnlyThatGrant() {
        val old = "content://recordings/tree/old"; val kept = "content://recordings/tree/new"
        listOf(old, kept).forEach { context.contentResolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        LocalMasters(context).releaseGrant(old)
        assertFalse(LocalMasters.hasReadGrant(context, old)); assertTrue(LocalMasters.hasReadGrant(context, kept))
    }
}
