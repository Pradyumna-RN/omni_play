package labs.rtensor.music.apple

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import labs.rtensor.music.data.AccountSession
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleAccountTest {
    @Test fun privateWorkerErrorsRoundTripAsSupportedBinderExceptions() {
        val controller = org.robolectric.Robolectric.buildService(AppleRuntimeService::class.java).create()
        val service = controller.get()
        val input = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
        try {
            input.writeInterfaceToken(AppleRuntimeService.DESCRIPTOR)
            input.writeBundle(android.os.Bundle().apply { putString("session", "invalid") })
            assertTrue(service.onBind(null).transact(AppleRuntimeService.INIT, input, reply, 0))
            val failure = assertThrows(IllegalStateException::class.java) { reply.readException() }
            assertTrue(failure.message.orEmpty().contains("Apple playback could not continue"))
            input.setDataSize(0); input.setDataPosition(0); reply.setDataSize(0); reply.setDataPosition(0)
            AppleRuntimeService::class.java.getDeclaredField("initializedSession").apply { isAccessible = true }.set(service, "new-session")
            input.writeInterfaceToken(AppleRuntimeService.DESCRIPTOR)
            input.writeBundle(android.os.Bundle().apply { putString("session", "old-session"); putString("song", "123") })
            assertTrue(service.onBind(null).transact(AppleRuntimeService.PLAYBACK, input, reply, 0))
            assertThrows(IllegalStateException::class.java) { reply.readException() }
        } finally { input.recycle(); reply.recycle(); controller.destroy() }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun vault() = AccountSession(context, "apple-actor-test") { SecretKeySpec(ByteArray(32) { 9 }, "AES") }.also { it.clear() }
    private class Runtime : AppleNativeAccess {
        var status = "awaiting_2fa"; var closed = 0; var code = ""; var session = ""
        override suspend fun ensure(session: String): JSONObject { this.session = session; return JSONObject().put("runtimeReady", true) }
        override suspend fun account(operation: Int, user: String, secret: String): JSONObject {
            if (operation == 2) { code = secret; status = "authenticated" }
            return JSONObject().put("state", status)
        }
        override suspend fun credentials() = AppleCredentials("header.payload.signature", "synthetic-user-token", storefront = "us", native = true)
        override suspend fun playback(song: String) = JSONObject()
        override fun decrypt(session: String, song: String, key: String, bytes: ByteArray) = error("No media expected")
        override fun close() { closed++ }
    }
    private fun http(before: () -> Unit = {}): AppleHttp = AppleHttp(OkHttpClient.Builder().addInterceptor { chain ->
        before()
        assertEquals("amp-api.music.apple.com", chain.request().url.host)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"data":[{"id":"us"}]}""".toResponseBody()).build()
    }.build())
    private fun until(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Account transition did not settle", condition())
    }
    @Test fun verificationCodeCompletesOnlyThePendingNativeSessionAndPersistsEncrypted() {
        val vault = vault(); val runtime = Runtime(); val account = AppleAccount(context, vault, http(), runtime)
        account.signInNative("fixture@example.test", "synthetic-password")
        until { account.state.value.needsCode }
        assertNull(vault.load()); assertFalse(account.state.value.connected)
        account.verificationCode("123456")
        until { account.state.value.connected }
        assertEquals("123456", runtime.code); assertTrue(account.state.value.native)
        val stored = JSONObject(vault.load()!!)
        assertEquals(runtime.session, stored.getString("runtimeSession")); assertFalse(stored.has("password")); assertFalse(stored.has("code"))
        account.disconnect(); assertNull(vault.load()); assertFalse(account.state.value.connected); assertTrue(runtime.closed > 0)
    }
    @Test fun staleWebCallbackCannotReconnectAnAccountAfterDisconnect() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val vault = vault(); val account = AppleAccount(context, vault, http { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }, Runtime())
        val ticket = account.beginWeb()
        val credentials = AppleCredentials("header.payload.signature", "synthetic-user-token")
        account.completeWeb(ticket, credentials.json().toString())
        until { entered.count == 0L }
        account.disconnect(); release.countDown()
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertNull(vault.load()); assertFalse(account.state.value.connected); assertFalse(account.state.value.connecting)
        account.completeWeb(ticket, credentials.json().toString())
        shadowOf(Looper.getMainLooper()).idle(); assertNull(vault.load())
    }
    @Test fun nativeCancellationDiscardsLateCodesAndLeavesNoSavedAccount() {
        val vault = vault(); val runtime = Runtime(); val account = AppleAccount(context, vault, http(), runtime)
        account.signInNative("fixture@example.test", "synthetic-password")
        until { account.state.value.needsCode }
        account.cancel(); account.verificationCode("123456")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", runtime.code); assertFalse(account.state.value.needsCode); assertNull(vault.load())
        assertThrows(IllegalArgumentException::class.java) { account.verificationCode("x12345") }
    }
}
