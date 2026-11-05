package labs.rtensor.music.spotify

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Use AOSP's actual ResultReceiver.send; Robolectric's default shadow skips its Binder. */
@Implements(ResultReceiver::class)
class FrameworkResultReceiverShadow {
    @RealObject private lateinit var receiver: ResultReceiver
    @Implementation fun send(code: Int, data: Bundle?) {
        Shadow.directlyOn<Unit, ResultReceiver>(receiver, ResultReceiver::class.java, "send",
            ClassParameter.from(Integer.TYPE, code), ClassParameter.from(Bundle::class.java, data))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], shadows = [FrameworkResultReceiverShadow::class])
class SpotifyCatalogueClientTest {
    @Test fun productionClientAndServiceCompleteABinderReplyWithoutLoadingTheAnonymousClientClass() {
        val context: Application = ApplicationProvider.getApplicationContext()
        val service = Robolectric.buildService(SpotifyCatalogueService::class.java).create()
        val worker = service.get()
        val binder = worker.onBind(Intent(context, SpotifyCatalogueService::class.java))
        ShadowBinder.setCallingUid(worker.applicationInfo.uid)
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, SpotifyCatalogueService::class.java), binder)
        val client = SpotifyCatalogueClient(context)
        var result: JSONObject? = null; var failure: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pending = scope.launch {
            try { result = client.request("disconnect") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        try {
            repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
            assertNull(failure)
            assertTrue("The service must reply to the real client rather than timing out after sign-in", pending.isCompleted)
            assertNotNull(result); assertEquals(0, result!!.length())
        } finally { scope.cancel(); client.close(); service.destroy(); ShadowBinder.reset() }
    }
    @Test fun unverifiedAccountsAndTimeoutErrorsDoNotKeepTheSettingsSpinnerRunning() = runBlocking {
        val context: Application = ApplicationProvider.getApplicationContext()
        val api = object : SpotifyCatalogueAccess {
            override suspend fun request(operation: String, args: JSONObject): JSONObject = throw SpotifyCatalogueException("TIMEOUT")
        }
        val account = SpotifyRecommendations(context, api)
        account.connecting(); assertTrue(account.state.value.connecting)
        assertFalse(account.verify())
        assertFalse(account.state.value.connecting); assertFalse(account.state.value.connected)
        assertTrue(account.state.value.error!!.contains("too long"))
    }
    @Test fun cancelledOrFailedSignInLeavesAVisibleReasonAndANewAttemptClearsIt() = runBlocking {
        val context: Application = ApplicationProvider.getApplicationContext()
        val api = object : SpotifyCatalogueAccess {
            override suspend fun request(operation: String, args: JSONObject): JSONObject = JSONObject().put("name", "Listener")
        }
        val account = SpotifyRecommendations(context, api)
        account.connecting(); account.cancel()
        assertFalse(account.state.value.connecting)
        assertEquals("Spotify sign-in was cancelled before it finished. Tap Spotify to try again.", account.state.value.error)
        account.connecting(); assertNull(account.state.value.error)
        account.cancel("the sign-in page could not load")
        assertEquals("Spotify sign-in didn't finish: the sign-in page could not load. Tap Spotify to try again.", account.state.value.error)
        // A verified result after a fresh attempt replaces the message.
        account.connecting(); assertTrue(account.verify())
        assertNull(account.state.value.error); assertTrue(account.state.value.connected)
    }
}
