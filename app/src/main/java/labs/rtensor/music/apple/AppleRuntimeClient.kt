package labs.rtensor.music.apple

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface AppleNativeAccess {
    suspend fun ensure(session: String): JSONObject
    suspend fun account(operation: Int, user: String = "", secret: String = ""): JSONObject
    suspend fun credentials(): AppleCredentials
    suspend fun playback(song: String): JSONObject
    fun decrypt(session: String, song: String, key: String, bytes: ByteArray): ByteArray
    fun close()
}

internal class AppleRuntimeClient(private val context: Context) : AppleNativeAccess {
    private val mutex = Mutex()
    @Volatile private var remote: IBinder? = null
    @Volatile private var activeSession: String? = null
    private var connection: ServiceConnection? = null
    @Volatile private var closing: IBinder? = null
    override suspend fun ensure(session: String): JSONObject = mutex.withLock {
        if (remote != null && activeSession != null && activeSession != session) close()
        closing?.let { previous ->
            withTimeout(5000) { while (previous.isBinderAlive) delay(30) }
            if (closing === previous) closing = null
        }
        val binder = remote?.takeIf { it.isBinderAlive } ?: withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<IBinder>()
            val next = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (connection !== this) return
                    if (service == null) ready.completeExceptionally(IOException("Apple engine could not start."))
                    else {
                        try {
                            service.linkToDeath({ if (remote === service) { remote = null; activeSession = null } }, 0)
                            remote = service; ready.complete(service)
                        } catch (_: RemoteException) { ready.completeExceptionally(IOException("Apple engine stopped while connecting.")) }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) { if (connection === this) { remote = null; activeSession = null } }
                override fun onNullBinding(name: ComponentName?) { ready.completeExceptionally(IOException("Apple engine is unavailable.")) }
                override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name); ready.completeExceptionally(IOException("Apple engine stopped.")) }
            }
            connection = next
            if (!context.bindService(Intent(context, AppleRuntimeService::class.java), next, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
                throw IOException("Apple engine could not start.")
            try { withTimeout(10_000) { ready.await() } }
            catch (error: Exception) { if (connection === next) { runCatching { context.unbindService(next) }; connection = null }; throw error }
        }
        val state = json(asyncCall(binder, AppleRuntimeService.INIT, Bundle().apply { putString("session", session) }))
        check(state.optBoolean("runtimeReady")) { "The imported Apple runtime could not load on this Android version." }
        activeSession = session
        state
    }
    override suspend fun account(operation: Int, user: String, secret: String): JSONObject = withContext(Dispatchers.IO) {
        val name = user.toByteArray(Charsets.UTF_8); val password = secret.toByteArray(Charsets.UTF_8)
        try { json(call(requireRemote(), AppleRuntimeService.ACCOUNT, Bundle().apply { putString("session", activeSession); putInt("operation", operation); putByteArray("user", name); putByteArray("secret", password) })) }
        finally { name.fill(0); password.fill(0) }
    }
    override suspend fun credentials(): AppleCredentials = withContext(Dispatchers.IO) {
        AppleCredentials.parse(json(call(requireRemote(), AppleRuntimeService.CREDENTIALS, Bundle().apply { putString("session", activeSession) })).put("native", true))
    }
    override suspend fun playback(song: String): JSONObject {
        require(song.matches(Regex("[0-9]{1,24}")))
        return json(asyncCall(requireRemote(), AppleRuntimeService.PLAYBACK, Bundle().apply { putString("session", activeSession); putString("song", song) }))
    }
    override fun decrypt(session: String, song: String, key: String, bytes: ByteArray): ByteArray {
        check(activeSession == session) { "The Apple playback session changed." }
        require(AppleOrigins.nativeKey(key) && song.matches(Regex("[0-9]{1,24}")) && bytes.size in 1..131072)
        return call(requireRemote(), AppleRuntimeService.DECRYPT, Bundle().apply { putString("session", session); putString("song", song); putString("key", key); putByteArray("sample", bytes) })
            .also { check(it.size == bytes.size) }
    }
    override fun close() {
        val old = remote; remote = null; activeSession = null
        if (old != null) { closing = old; runCatching { call(old, AppleRuntimeService.STOP, Bundle.EMPTY) } }
        connection?.let { runCatching { context.unbindService(it) } }; connection = null
    }
    private fun requireRemote(): IBinder = remote?.takeIf { it.isBinderAlive } ?: throw IOException("The Apple native engine stopped. Try playback again.")
    private fun json(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))
    private suspend fun asyncCall(binder: IBinder, operation: Int, values: Bundle): ByteArray = suspendCancellableCoroutine { continuation ->
        val task = calls.submit {
            try { val value = call(binder, operation, values); if (continuation.isActive) continuation.resume(value) }
            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
        continuation.invokeOnCancellation { task.cancel(true) }
    }
    private fun call(binder: IBinder, operation: Int, values: Bundle): ByteArray {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AppleRuntimeService.DESCRIPTOR); data.writeBundle(values)
            if (!binder.transact(operation, data, reply, 0)) throw IOException("Apple engine could not respond.")
            try { reply.readException() }
            catch (_: RuntimeException) { throw IOException("Apple playback could not continue. Check the account and native runtime, then retry.") }
            return reply.createByteArray()?.also { require(it.size <= 512 * 1024) } ?: throw IOException("Apple engine returned no data.")
        } catch (_: RemoteException) { throw IOException("The Apple native engine stopped. Check runtime compatibility and retry.") }
        finally { data.recycle(); reply.recycle() }
    }
    companion object { private val calls = java.util.concurrent.Executors.newFixedThreadPool(2) }
}
