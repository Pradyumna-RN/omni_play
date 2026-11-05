package labs.rtensor.music.spotify

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface SpotifyCatalogueAccess {
    suspend fun request(operation: String, args: JSONObject = JSONObject()): JSONObject
    fun close() {}
}

internal class SpotifyCatalogueClient(context: Context) : SpotifyCatalogueAccess {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var remote: IBinder? = null
    private var connection: ServiceConnection? = null
    private suspend fun bind(): IBinder = mutex.withLock {
        remote?.takeIf { it.isBinderAlive }?.let { return@withLock it }
        withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<IBinder>()
            val next = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (connection !== this) return
                    if (binder == null) ready.completeExceptionally(IOException("Spotify catalogue could not start."))
                    else { remote = binder; ready.complete(binder) }
                }
                override fun onServiceDisconnected(name: ComponentName?) { if (connection === this) remote = null }
                override fun onNullBinding(name: ComponentName?) { ready.completeExceptionally(IOException("Spotify catalogue is unavailable.")) }
            }
            connection?.let { runCatching { context.unbindService(it) } }
            connection = next
            check(context.bindService(Intent(context, SpotifyCatalogueService::class.java), next, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
            try { withTimeoutOrNull(10_000) { ready.await() } ?: throw SpotifyCatalogueException("TIMEOUT") }
            catch (e: Exception) { if (connection === next) { runCatching { context.unbindService(next) }; connection = null }; throw e }
        }
    }
    override suspend fun request(operation: String, args: JSONObject): JSONObject {
        val binder = bind()
        return withTimeoutOrNull(40_000) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val id = UUID.randomUUID().toString()
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (!continuation.isActive) return
                        if (resultCode != 0) continuation.resumeWithException(SpotifyCatalogueException(resultData?.getString("error").orEmpty()))
                        else try {
                            val raw = resultData?.getString("result") ?: throw IOException()
                            require(raw.length <= 524288)
                            continuation.resume(JSONObject(raw))
                        } catch (_: Exception) { continuation.resumeWithException(IOException("Spotify returned invalid catalogue data.")) }
                    }
                }
                continuation.invokeOnCancellation { runCatching { transact(binder, SpotifyCatalogueService.CANCEL, id) } }
                try { transact(binder, SpotifyCatalogueService.REQUEST, id, operation, args.toString(), receiver) }
                catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IOException("Spotify catalogue stopped. Reconnect Spotify.")) }
            }
        } ?: throw SpotifyCatalogueException("TIMEOUT")
    }
    private fun transact(binder: IBinder, code: Int, id: String, operation: String = "", json: String = "", receiver: ResultReceiver? = null) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SpotifyCatalogueService.DESCRIPTOR); data.writeString(id)
            if (code == SpotifyCatalogueService.REQUEST) { data.writeString(operation); data.writeString(json); SpotifyCatalogueProtocol.writeReceiver(data, checkNotNull(receiver)) }
            check(binder.transact(code, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
    override fun close() {
        remote = null
        connection?.let { runCatching { context.unbindService(it) } }; connection = null
    }
}
