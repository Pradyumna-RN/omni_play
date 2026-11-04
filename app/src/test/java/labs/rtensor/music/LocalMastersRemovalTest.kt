package labs.rtensor.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.LocalMasters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMastersRemovalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val calls = AtomicInteger()

    @Before fun registerSlowFolder() {
        File(context.filesDir, "masters.json").delete()
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                if (calls.getAndIncrement() == 0) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return MatrixCursor(projection!!)
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "recordings" })
        ShadowContentResolver.registerProviderInternal("recordings", provider)
    }

    @Test fun aScanAlreadyReadingCannotRestoreTheFolderAfterClear() {
        val masters = LocalMasters(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scan = executor.submit<List<*>> { masters.index(Uri.parse("content://recordings/tree/old")) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            masters.clear(); release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { scan.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            val restarted = LocalMasters(context)
            assertNull(restarted.folder); assertTrue(restarted.entries.isEmpty())
            assertFalse(File(context.filesDir, "masters.json").exists())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun anOlderScanCannotOverwriteTheNewFolderSelection() {
        val masters = LocalMasters(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<List<*>> { masters.index(Uri.parse("content://recordings/tree/old")) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val replacement = Uri.parse("content://recordings/tree/new")
            masters.index(replacement)
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { old.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertEquals(replacement.toString(), LocalMasters(context).folder)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
