package labs.rtensor.music.apple

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import labs.rtensor.music.BuildConfig
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.ProviderStream
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AppleAccountState(val connected: Boolean = false, val connecting: Boolean = false,
    val native: Boolean = false, val runtimeImported: Boolean = false, val needsCode: Boolean = false,
    val error: String? = null, val revision: Long = 0)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AppleAccount(private val context: Context, private val vault: AccountSession = AccountSession(context, "apple_music_account"),
    private val http: AppleHttp = AppleHttp(), private val runtime: AppleNativeAccess = AppleRuntimeClient(context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val files = AppleRuntimeFiles(context)
    private val generation = AtomicLong(0)
    @Volatile private var credentials: AppleCredentials? = null
    @Volatile private var nativeSession: String? = null
    private var pendingSession: String? = null
    private var authJob: Job? = null
    private val mutable = MutableStateFlow(AppleAccountState())
    val state = mutable.asStateFlow()
    val revision: Long get() = generation.get()
    init {
        runCatching { vault.load()?.let(::JSONObject) }.getOrNull()?.let { json ->
            credentials = runCatching { AppleCredentials.parse(json) }.getOrNull()
            nativeSession = json.optString("runtimeSession").takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        }
        mutable.value = AppleAccountState(connected = credentials != null, native = credentials?.native == true && nativeSession != null)
        scope.launch(Dispatchers.IO) { val installed = files.installed(); mutable.update { it.copy(runtimeImported = installed) } }
    }
    fun beginWeb(): Long {
        authJob?.cancel(); pendingSession = null
        val ticket = generation.incrementAndGet()
        mutable.value = mutable.value.copy(connecting = true, needsCode = false, error = null, revision = ticket)
        return ticket
    }
    fun completeWeb(ticket: Long, value: String) {
        // Android can end the app process while Apple's sign-in page is open, then deliver its
        // result to the restored screen. That ticket came from the previous process. If nothing
        // has happened to the account in this process yet, it is the user's latest sign-in.
        if (ticket > 0 && generation.get() == 0L && !mutable.value.connecting) return completeWeb(beginWeb(), value)
        if (ticket != generation.get()) return
        authJob = scope.launch {
            try {
                val raw = JSONObject(value).put("native", false)
                val source = AppleCredentials.parse(raw)
                val region = http.storefront(source)
                if (ticket != generation.get()) return@launch
                runtime.close()
                save(AppleCredentials(source.developerToken, source.musicUserToken, "Apple Music", region, false), null)
            } catch (error: TimeoutCancellationException) { failed(ticket, error) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (ticket == generation.get()) mutable.update { it.copy(connecting = false, error = "Apple sign-in could not be verified. Try again.") } }
        }
    }
    fun cancelWeb(ticket: Long) { if (ticket == generation.get()) cancel() }
    suspend fun importRuntime(uri: Uri) {
        check(BuildConfig.APPLE_NATIVE_INCLUDED) { "This build does not include the native Apple adapter." }
        files.import(uri)
        runtime.close()
        mutable.update { it.copy(runtimeImported = files.installed(), error = null) }
    }
    suspend fun prepareBundledRuntime() {
        check(BuildConfig.APPLE_NATIVE_INCLUDED) { "This build does not include the native Apple adapter." }
        files.installBundled()
        mutable.update { it.copy(runtimeImported = files.installed(), error = null) }
    }
    fun signInNative(user: String, password: String) {
        require(user.length in 3..320 && password.length in 1..1024)
        authJob?.cancel(); runtime.close()
        val ticket = generation.incrementAndGet()
        val session = UUID.randomUUID().toString(); pendingSession = session
        mutable.value = mutable.value.copy(connecting = true, needsCode = false, error = null, revision = ticket)
        authJob = scope.launch {
            try {
                signInStep(AppleSignInStep.RUNTIME) { runtime.ensure(session) }
                signInStep(AppleSignInStep.AUTHENTICATION) { runtime.account(1, user, password) }
                settle(ticket, session)
            } catch (error: TimeoutCancellationException) { failed(ticket, error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(ticket, error) }
        }
    }
    fun verificationCode(code: String) {
        require(code.matches(Regex("[0-9]{6}")))
        val session = pendingSession ?: return
        val ticket = generation.get()
        mutable.update { it.copy(connecting = true, needsCode = false, error = null) }
        authJob = scope.launch {
            try { signInStep(AppleSignInStep.VERIFICATION) { runtime.account(2, secret = code) }; settle(ticket, session) }
            catch (error: TimeoutCancellationException) { failed(ticket, error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(ticket, error) }
        }
    }
    private suspend fun settle(ticket: Long, session: String) = withTimeout(90_000) {
        while (ticket == generation.get()) {
            val status = signInStep(AppleSignInStep.AUTHENTICATION) { runtime.account(0) }
            when (status.optString("state")) {
                "awaiting_2fa" -> { mutable.update { it.copy(connecting = false, needsCode = true) }; return@withTimeout }
                "authenticated" -> {
                    val next = signInStep(AppleSignInStep.SESSION) { runtime.credentials() }
                    val region = signInStep(AppleSignInStep.SUBSCRIPTION) { http.storefront(next) }
                    if (ticket == generation.get()) save(AppleCredentials(next.developerToken, next.musicUserToken, next.name, region, true), session)
                    return@withTimeout
                }
                "failed", "logged_out" -> throw AppleSignInFailure(AppleSignInStep.AUTHENTICATION,
                    status.optInt("errorCode").takeIf { it != 0 })
            }
            delay(500)
        }
    }
    private fun failed(ticket: Long, error: Exception) {
        if (ticket != generation.get()) return
        runtime.close()
        val discarded = pendingSession; pendingSession = null
        val message = when (error) {
            is TimeoutCancellationException -> "Apple sign-in timed out. Try again."
            is AppleSignInFailure -> error.safeMessage
            else -> "Apple sign-in could not finish. Retry the connection."
        }
        mutable.update { it.copy(connecting = false, needsCode = false,
            error = message + if (it.connected && !it.native) " Your AAC connection is still available." else "") }
        cleanup(discarded)
    }
    private fun save(value: AppleCredentials, session: String?) {
        val json = value.json().apply { if (session != null) put("runtimeSession", session) }
        vault.save(json.toString())
        credentials = value; nativeSession = session; pendingSession = null
        val version = generation.incrementAndGet()
        mutable.value = AppleAccountState(connected = true, native = value.native && session != null,
            runtimeImported = files.installed(), revision = version)
    }
    fun cancel() {
        if (!mutable.value.connecting && !mutable.value.needsCode && pendingSession == null && authJob?.isActive != true) return
        val discarded = pendingSession
        authJob?.cancel(); runtime.close(); pendingSession = null
        val version = generation.incrementAndGet()
        mutable.update { it.copy(connecting = false, needsCode = false, error = null, revision = version) }
        cleanup(discarded)
    }
    fun disconnect() {
        val discarded = nativeSession
        cancel(); vault.clear(); credentials = null; nativeSession = null
        runtime.close(); cleanup(discarded)
        val version = generation.incrementAndGet()
        mutable.value = AppleAccountState(runtimeImported = files.installed(), revision = version)
    }
    private fun cleanup(session: String?) {
        if (session == null) return
        scope.launch(Dispatchers.IO) {
            delay(1000)
            if (session != nativeSession && session != pendingSession) java.io.File(context.filesDir, "apple-sessions/$session").deleteRecursively()
        }
    }
    suspend fun resolve(track: Track): List<ProviderStream> {
        val owner = credentials ?: return emptyList()
        val version = revision
        val session = nativeSession.takeUnless { state.value.connecting || state.value.needsCode }
        return AppleResolver(http, runtime).resolve(track, owner, if (owner.native) session else null, "apple-account:$version") {
            version == revision && credentials === owner
        }
    }
    companion object {
        @Volatile private var singleton: AppleAccount? = null
        fun get(context: Context): AppleAccount = singleton ?: synchronized(this) {
            singleton ?: AppleAccount(context.applicationContext).also { singleton = it }
        }
    }
}

/** Fixed diagnostics only: never forward a native exception, account ID or secret to the UI. */
internal enum class AppleSignInStep(val description: String) {
    RUNTIME("Apple audio engine could not start on this device"),
    AUTHENTICATION("Apple account authentication did not complete"),
    VERIFICATION("Apple verification code could not be submitted"),
    SESSION("Apple sign-in completed but its playback session could not be verified"),
    SUBSCRIPTION("Apple sign-in completed but music subscription access could not be verified")
}

internal class AppleSignInFailure(val step: AppleSignInStep, val providerCode: Int? = null) :
    Exception(step.description) {
    val safeMessage: String get() = step.description +
        (providerCode?.let { " (provider code $it)" } ?: "") + ". Retry the connection."
}

private suspend fun <T> signInStep(step: AppleSignInStep, action: suspend () -> T): T = try {
    action()
} catch (cancel: CancellationException) { throw cancel }
catch (failure: AppleSignInFailure) { throw failure }
catch (_: Exception) { throw AppleSignInFailure(step) }
