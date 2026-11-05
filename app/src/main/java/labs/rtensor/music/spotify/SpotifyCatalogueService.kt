package labs.rtensor.music.spotify

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Private read-only metadata worker. Its cookie directory is separate from Google and Apple. */
internal class SpotifyCatalogueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val main = Handler(Looper.getMainLooper())
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code !in setOf(REQUEST, CANCEL)) return super.onTransact(code, data, reply, flags)
            check(getCallingUid() == applicationInfo.uid)
            data.enforceInterface(DESCRIPTOR)
            val id = data.readString().orEmpty()
            require(id.matches(Regex("[a-f0-9-]{36}")))
            if (code == CANCEL) main.post { jobs.remove(id)?.cancel() }
            else {
                val operation = data.readString().orEmpty()
                val json = data.readString().orEmpty()
                require(json.length <= 16384 && operation in setOf("profile", "home", "radio", "search", "collection", "disconnect"))
                @Suppress("DEPRECATION") val receiver = data.readParcelable<ResultReceiver>(ResultReceiver::class.java.classLoader)
                    ?: return false
                main.post {
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val runtime = SpotifyWebRuntime.get(applicationContext)
                            val result = if (operation == "disconnect") { runtime.disconnect(); JSONObject() }
                                else runtime.call(operation, JSONObject(json))
                            receiver.send(0, Bundle().apply { putString("result", result.toString()) })
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            receiver.send(1, Bundle().apply { putString("error", (e as? SpotifyCatalogueException)?.code ?: "CATALOGUE_UNAVAILABLE") })
                        } finally { jobs.remove(id) }
                    }
                    jobs[id] = job; job.start()
                }
            }
            reply?.writeNoException()
            return true
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        scope.cancel(); SpotifyWebRuntime.get(applicationContext).close(); super.onDestroy()
    }
    companion object {
        const val DESCRIPTOR = "labs.rtensor.music.spotify.Catalogue"
        const val REQUEST = IBinder.FIRST_CALL_TRANSACTION
        const val CANCEL = REQUEST + 1
    }
}
