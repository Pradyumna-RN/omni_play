package labs.rtensor.music.apple

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** In-app process boundary: vendor native failures cannot take down the music UI. */
internal class AppleRuntimeService : Service() {
    private val operations = Executors.newFixedThreadPool(2)
    @Volatile private var initializedSession: String? = null
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            check(Binder.getCallingUid() == Process.myUid()) { "Private service" }
            data.enforceInterface(DESCRIPTOR)
            if (reply == null) return false
            try {
                val arguments = data.readBundle(javaClass.classLoader) ?: Bundle.EMPTY
                if (code == STOP) {
                    reply.writeNoException(); reply.writeByteArray(ByteArray(0)); terminate(); return true
                }
                val task = operations.submit<ByteArray> {
                    if (code != INIT) check(initializedSession != null && arguments.getString("session") == initializedSession) { "The Apple session changed." }
                    when (code) {
                        INIT -> initialize(arguments.getString("session").orEmpty())
                        ACCOUNT -> {
                            val user = arguments.getByteArray("user") ?: ByteArray(0)
                            val secret = arguments.getByteArray("secret") ?: ByteArray(0)
                            try { NativeAppleBridge.account(arguments.getInt("operation"), user, secret) }
                            finally { user.fill(0); secret.fill(0); arguments.clear() }
                        }
                        CREDENTIALS -> NativeAppleBridge.credentials()
                        PLAYBACK -> NativeAppleBridge.playback(arguments.getString("song").orEmpty())
                        DECRYPT -> NativeAppleBridge.decrypt(arguments.getString("song").orEmpty(), arguments.getString("key").orEmpty(), arguments.getByteArray("sample") ?: ByteArray(0))
                        else -> throw IOException("Unknown native operation")
                    }
                }
                val output = try { task.get(if (code == INIT) 35 else 20, TimeUnit.SECONDS) }
                catch (error: java.util.concurrent.TimeoutException) { task.cancel(true); terminate(); throw IOException("The Apple engine did not respond in time.") }
                require(output.size <= 512 * 1024)
                reply.writeNoException(); reply.writeByteArray(output)
            } catch (_: Exception) { reply.writeException(IllegalStateException("Apple playback could not continue. Check the account and runtime, then retry.")) }
            catch (_: LinkageError) { reply.writeException(IllegalStateException("The Apple native engine is unavailable in this build or on this device.")) }
            return true
        }
    }
    @Synchronized private fun initialize(session: String): ByteArray {
        require(session.matches(Regex("[a-f0-9-]{36}")))
        if (initializedSession != null) {
            require(initializedSession == session) { "Restart the engine before changing accounts." }
            return NativeAppleBridge.account(0, ByteArray(0), ByteArray(0))
        }
        val runtime = AppleRuntimeFiles(this)
        val order = runtime.order()
        val storage = File(filesDir, "apple-sessions/$session").apply { check(isDirectory || mkdirs()) }
        File(storage, "mpl_db").mkdirs()
        fun part(value: String) = value.replace('/', ' ').filter { it >= ' ' && it != '\u007f' }.take(80)
        val locale = Locale.getDefault().toLanguageTag()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            .takeIf { it.matches(Regex("[a-fA-F0-9]{8,32}")) } ?: session.replace("-", "").take(16)
        val fingerprint = listOf("Music", "3.6.0-beta", "Android", Build.VERSION.RELEASE, Build.MODEL, "1109", locale, locale, androidId).joinToString("/") { part(it) }
        val result = NativeAppleBridge.initialize(runtime.directory.absolutePath, storage.absolutePath, fingerprint, order)
        if (JSONObject(String(result, Charsets.UTF_8)).optBoolean("runtimeReady")) initializedSession = session
        return result
    }
    override fun onBind(intent: Intent?) = binder
    private fun terminate() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (Application.getProcessName() == "$packageName:apple_native") Process.killProcess(Process.myPid())
        }, 150)
    }
    override fun onDestroy() { operations.shutdownNow(); terminate(); super.onDestroy() }
    companion object {
        const val DESCRIPTOR = "labs.rtensor.music.apple.runtime.v1"
        const val INIT = 1; const val ACCOUNT = 2; const val CREDENTIALS = 3; const val PLAYBACK = 4; const val DECRYPT = 5; const val STOP = 6
    }
}
