package labs.rtensor.music.apple

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import labs.rtensor.music.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Uses the explicit external build assets; no vendor fixture is committed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class BundledAppleRuntimeTest {
    @Test fun bundledRuntimeInstallsAutomaticallyAndUsesOnlyThePlaybackDependencyClosure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!BuildConfig.APPLE_RUNTIME_INCLUDED) {
            assertTrue(context.assets.list("apple-runtime").orEmpty().isEmpty())
            return@runBlocking
        }
        for (arch in listOf("arm64-v8a", "x86_64")) {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf(arch))
            val runtime = AppleRuntimeFiles(context)
            try {
                assertFalse(runtime.installed())
                runtime.installBundled()
                assertTrue(runtime.installed());assertTrue(runtime.verify())
                assertEquals(19, runtime.directory.listFiles()!!.size) // 18 images and the verification marker
                val order = runtime.order()
                assertEquals(17, order.size)
                assertFalse(order.contains("libicutest_sv_apple.so"))
                assertTrue(order.contains("libandroidappmusic.so"))
                runtime.installBundled() // a second visit requires no document selection
                assertTrue(runtime.verify())
                assertTrue(File(context.filesDir, "apple-runtime").listFiles().orEmpty().none { it.name.startsWith("pending-") })
            } finally { runtime.directory.deleteRecursively() }
        }
    }
    @Test fun aTamperedInstalledImageIsRejectedAndPendingFilesAreRemoved() = runBlocking {
        if (!BuildConfig.APPLE_RUNTIME_INCLUDED) return@runBlocking
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val context = ApplicationProvider.getApplicationContext<Context>();val runtime = AppleRuntimeFiles(context)
        try {
            runtime.installBundled();assertTrue(runtime.verify())
            val image = File(runtime.directory, "libCoreFP.so")
            assertTrue(image.setWritable(true));image.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(runtime.verify())
            var rejected = false
            try { runtime.installBundled() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(File(context.filesDir, "apple-runtime").listFiles().orEmpty().none { it.name.startsWith("pending-") })
        } finally { runtime.directory.deleteRecursively() }
    }
}
