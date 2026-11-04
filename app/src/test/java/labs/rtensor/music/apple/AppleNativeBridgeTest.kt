package labs.rtensor.music.apple

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleNativeBridgeTest {
    private fun native() {
        if (System.getProperty("music.apple.expectNative") == "true") assertNotNull(NativeAppleBridge)
        else assumeTrue(runCatching { NativeAppleBridge }.isSuccess)
    }
    @Test fun actualJniPreservesUnicodeAndLargeIntegersOnAttachedNativeWorker() {
        native()
        val data = JSONObject().put("title", "Amsham – അംശം 🎧").put("integer", 9007199254740991L)
            .put("nested", JSONObject().put("null", JSONObject.NULL).put("value", true))
        for (thread in listOf(false, true)) {
            val result = JSONObject(String(NativeAppleBridge.jsonRoundTrip(data.toString().toByteArray(), thread), Charsets.UTF_8))
            assertEquals(data.getString("title"), result.getString("title"))
            assertEquals(data.getLong("integer"), result.getLong("integer"))
            assertTrue(result.getJSONObject("nested").isNull("null"))
        }
        assertThrows(java.io.IOException::class.java) { NativeAppleBridge.jsonRoundTrip("{} trailing".toByteArray(), true) }
    }
    @Test fun actualAdapterRefusesUnauthenticatedPlaybackAndCredentialExport() {
        native()
        val state = JSONObject(String(NativeAppleBridge.account(0, byteArrayOf(), byteArrayOf())))
        assertFalse(state.getBoolean("runtimeReady")); assertFalse(state.has("musicUserToken"))
        assertThrows(java.io.IOException::class.java) { NativeAppleBridge.credentials() }
        assertThrows(java.io.IOException::class.java) { NativeAppleBridge.playback("123456") }
        assertThrows(java.io.IOException::class.java) { NativeAppleBridge.decrypt("123456", "skd://itunes.apple.com/P123/s1/e1", ByteArray(16)) }
        assertThrows(java.io.IOException::class.java) { NativeAppleBridge.account(1, "fixture@example.test".toByteArray(), "synthetic-password".toByteArray()) }
    }
}
