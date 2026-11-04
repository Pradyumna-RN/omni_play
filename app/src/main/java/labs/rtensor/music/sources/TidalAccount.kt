package labs.rtensor.music.sources

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import labs.rtensor.music.data.AccountSession
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.Track
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Independent account adapter. Tokens never leave the official account/API hosts;
 * media playback uses a separate, unauthenticated transport. No client secret is
 * bundled. The public client identifier is not proof of provider approval. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TidalAccount(
    private val vault: AccountSession,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
    private val clientId: String = PUBLIC_CLIENT_ID,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val refreshLock = Mutex()
    private var envelope = runCatching { JSONObject(vault.load() ?: "{}") }.getOrElse { JSONObject() }
    private var generation = 0L
    private val mutable = MutableStateFlow(AccountState(connected = session() != null))
    val state = mutable.asStateFlow()
    val revision: Long get() = synchronized(lock) { generation }

    private fun session(): JSONObject? = envelope.optJSONObject("session")
    private fun persist() { if (envelope.length() == 0) vault.clear() else vault.save(envelope.toString()) }
    private fun random(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))

    fun begin(): String = synchronized(lock) {
        generation++
        val verifier = random(32)
        val state = random(24)
        val unique = random(16)
        envelope.put("pending", JSONObject().put("verifier", verifier).put("state", state).put("unique", unique).put("expires", now() + 600_000))
        persist()
        mutable.value = mutable.value.copy(connecting = true, error = null)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        "https://login.tidal.com/authorize".toHttpUrl().newBuilder().apply {
            mapOf("client_id" to clientId, "response_type" to "code", "redirect_uri" to REDIRECT,
                "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state,
                "client_unique_key" to unique, "appMode" to "android", "scope" to "r_usr", "restrict_signup" to "true")
                .forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
    }

    fun cancel() = synchronized(lock) {
        generation++
        envelope.remove("pending"); persist()
        mutable.value = mutable.value.copy(connecting = false)
    }

    fun disconnect() = synchronized(lock) {
        generation++; envelope = JSONObject(); vault.clear()
        mutable.value = AccountState(revision = generation)
    }

    suspend fun complete(callback: String) {
        if (synchronized(lock) { !envelope.has("pending") }) return
        try {
            val (pending, epoch, code) = synchronized(lock) {
                require(isCallback(callback)) { "This is not a TIDAL sign-in response." }
                val pending = envelope.optJSONObject("pending") ?: error("Start TIDAL sign-in in Music first.")
                require(now() < pending.getLong("expires")) { "Sign-in expired. Start again." }
                val uri = ("https://callback.invalid/?" + URI(callback).rawQuery.orEmpty()).toHttpUrl()
                val returned = uri.queryParameterValues("state").singleOrNull().orEmpty()
                require(MessageDigest.isEqual(returned.toByteArray(), pending.getString("state").toByteArray())) { "Sign-in response could not be verified." }
                require(!uri.queryParameterNames.contains("error")) { "TIDAL sign-in was cancelled or declined." }
                val code = uri.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 4096 }
                    ?: error("TIDAL did not return a sign-in code.")
                envelope.remove("pending"); persist()
                Triple(JSONObject(pending.toString()), generation, code)
            }
            val token = form(mapOf("client_id" to clientId, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to REDIRECT, "code_verifier" to pending.getString("verifier"), "client_unique_key" to pending.getString("unique")))
            val saved = tokenSession(token, null)
            synchronized(lock) { check(generation == epoch) { "Sign-in was cancelled." } }
            val profile = api("sessions", emptyMap(), saved.getString("access"))
            val country = profile.optString("countryCode").uppercase()
            require(country.matches(Regex("[A-Z]{2}"))) { "TIDAL did not return an account region." }
            saved.put("country", country)
            synchronized(lock) {
                check(generation == epoch) { "Sign-in was cancelled." }
                generation++; envelope.put("session", saved); persist()
                mutable.value = AccountState(connected = true, name = "TIDAL", revision = generation)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { synchronized(lock) {
            mutable.value = mutable.value.copy(connecting = false, error = safeMessage(e))
        } }
    }

    /** Only full, clear responses can become candidates. Search metadata remains
     * separate from the logical YouTube item and does not replace its feed. */
    suspend fun resolve(track: Track): List<ProviderStream> {
        if (!state.value.connected) return emptyList()
        val epoch = revision
        val result = mutableListOf<ProviderStream>()
        var failure: Exception? = null
        try {
            // Preserve candidates already found if a later edition is slow.
            // The outer source selector gives this adapter a 6.5-second budget.
            withTimeoutOrNull(6000) {
                val country = synchronized(lock) { session()?.optString("country") }.orEmpty()
                val found = authenticated("search/tracks", mapOf("query" to "${track.artist} ${track.title}", "limit" to "20", "countryCode" to country))
                val items = found.optJSONArray("items") ?: return@withTimeoutOrNull
                var matches = 0
                for (i in 0 until minOf(items.length(), 20)) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: continue
                    val version = item.optString("version")
                    if (version.isNotBlank()) item.put("title", "${item.optString("title")} ($version)")
                    if (SourceStreamDecoder.decode(item, track, MusicProvider.TIDAL, false)?.matches(track) != true) continue
                    if (matches++ >= 3) break
                    val modes = item.optJSONArray("audioModes")
                    if (modes != null && (0 until modes.length()).any { modes.optString(it) == "DOLBY_ATMOS" }) {
                        result += resolveAtmos(item, id, track)
                    }
                    try {
                        val response = authenticated("tracks/$id/playbackinfopostpaywall", mapOf("countryCode" to country,
                            "audioquality" to "HI_RES_LOSSLESS", "playbackmode" to "STREAM", "assetpresentation" to "FULL"))
                        require(response.optString("assetPresentation") == "FULL") { "TIDAL did not return the full recording." }
                        TidalManifests.decode(item, response, API).forEach { candidate ->
                            SourceStreamDecoder.decode(candidate.metadata, track, MusicProvider.TIDAL)
                                ?.copy(streamKeys = candidate.streamKeys, sourceId = "native:tidal")?.let(result::add)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failure = e }
                    if (result.size >= 12 || result.any { it.verifiedAtmosCodec }) break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failure = e }
        synchronized(lock) {
            if (generation != epoch) return emptyList()
            if (result.isNotEmpty()) mutable.value = mutable.value.copy(error = null)
            else failure?.let { mutable.value = mutable.value.copy(error = safeMessage(it)) }
        }
        return result.take(12)
    }

    private suspend fun resolveAtmos(item: JSONObject, id: String, track: Track): List<ProviderStream> {
        try {
            val response = authenticated("trackManifests/$id", mapOf("formats" to "EAC3_JOC", "adaptive" to "false",
                "manifestType" to "MPEG_DASH", "uriScheme" to "HTTPS", "usage" to "PLAYBACK"), openApi = true)
            val attributes = response.optJSONObject("data")?.optJSONObject("attributes") ?: return emptyList()
            val formats = attributes.optJSONArray("formats") ?: return emptyList()
            if ((0 until formats.length()).none { formats.optString(it) == "EAC3_JOC" }) return emptyList()
            if (attributes.has("drmData") && !attributes.isNull("drmData")) return emptyList()
            val url = attributes.optString("uri").toHttpUrl()
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return emptyList()
            // Account authorization stays on TIDAL's API. The signed manifest
            // and segments are read without that bearer token or account cookies.
            val text = requestText(Request.Builder().url(url).build())
            val data = JSONObject().put("assetPresentation", "FULL").put("audioMode", "DOLBY_ATMOS")
                .put("manifest", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP))
            return TidalManifests.decode(item, data, url.toString(), externalManifest = true).mapNotNull {
                SourceStreamDecoder.decode(it.metadata, track, MusicProvider.TIDAL)?.copy(streamKeys = it.streamKeys, sourceId = "native:tidal")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return emptyList() } // An unavailable Atmos mix never poisons the lossless alternative.
    }

    private suspend fun authenticated(path: String, params: Map<String, String>, openApi: Boolean = false): JSONObject {
        val token = accessToken()
        return try { api(path, params, token, openApi) }
        catch (e: AccountHttpError) {
            if (e.status != 401) throw e
            api(path, params, accessToken(force = true, rejected = token), openApi)
        }
    }

    private suspend fun accessToken(force: Boolean = false, rejected: String? = null): String = refreshLock.withLock {
        val (saved, epoch) = synchronized(lock) { Pair(session()?.let { JSONObject(it.toString()) } ?: error("Sign in to TIDAL to continue."), generation) }
        if ((!force || (rejected != null && rejected != saved.getString("access"))) && saved.getLong("expires") > now() + 30_000) return@withLock saved.getString("access")
        require(saved.optString("refresh").isNotBlank()) { "Your TIDAL session expired. Sign in again." }
        // Public-client refresh only. A rejected refresh asks for sign-in; it
        // never falls back to a borrowed app secret or a third-party token service.
        val token = form(mapOf("grant_type" to "refresh_token", "refresh_token" to saved.getString("refresh"), "client_id" to clientId))
        val updated = tokenSession(token, saved)
        synchronized(lock) {
            check(generation == epoch) { "TIDAL was disconnected." }
            envelope.put("session", updated); persist()
            mutable.value = mutable.value.copy(error = null)
        }
        updated.getString("access")
    }

    private fun tokenSession(response: JSONObject, old: JSONObject?): JSONObject {
        fun token(key: String) = response.optString(key).also { require(it.length <= 8192 && it.none { c -> c == '\r' || c == '\n' }) }
        val access = token("access_token"); require(access.isNotBlank()) { "TIDAL did not return an access token." }
        require(response.optString("token_type", "Bearer").equals("Bearer", true))
        val lifetime = response.optLong("expires_in"); require(lifetime in 1..31_536_000)
        return JSONObject().put("access", access).put("refresh", token("refresh_token").ifBlank { old?.optString("refresh").orEmpty() })
            .put("expires", now() + lifetime * 1000).put("country", old?.optString("country").orEmpty())
    }

    private suspend fun form(params: Map<String, String>): JSONObject = request(Request.Builder()
        .url("https://auth.tidal.com/v1/oauth2/token").post(FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()).build())
    private suspend fun api(path: String, params: Map<String, String>, token: String, openApi: Boolean = false): JSONObject = request(Request.Builder()
        .url((if (openApi) OPEN_API else API).toHttpUrl().newBuilder().addPathSegments(path).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
        .header("Authorization", "Bearer $token").build())

    private suspend fun request(request: Request): JSONObject = JSONObject(requestText(request))
    private suspend fun requestText(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { r ->
                        if (!r.isSuccessful) throw AccountHttpError(r.code)
                        val body = r.body ?: throw IOException()
                        if (body.contentLength() > 1_000_000) throw IOException()
                        val text = body.charStream().use { reader ->
                            val out = StringBuilder(); val chunk = CharArray(4096)
                            while (true) { val n = reader.read(chunk); if (n < 0) break; if (out.length + n > 1_000_000) throw IOException(); out.append(chunk, 0, n) }
                            out.toString()
                        }
                        text
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private class AccountHttpError(val status: Int) : IOException()
    private fun safeMessage(e: Exception): String = when (e) {
        is AccountHttpError -> when (e.status) {
            400, 401 -> "TIDAL could not authorize this session. Sign in again."
            403 -> "TIDAL did not allow this recording or quality for the account."
            429 -> "TIDAL is busy. Try again shortly."
            else -> "TIDAL returned HTTP ${e.status}. Try again."
        }
        is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { it.length <= 120 } ?: "TIDAL sign-in could not be completed."
        else -> "TIDAL could not be reached. Try again."
    }

    companion object {
        const val REDIRECT = "tidal://login/auth"
        private const val API = "https://api.tidal.com/v1/"
        private const val OPEN_API = "https://openapi.tidal.com/v2/"
        // Public OAuth client identifier used by independent native account clients.
        // This is NOT a client secret or an RTensorLabs developer registration.
        private const val PUBLIC_CLIENT_ID = "6BDSRdpK9hqEBTgU"
        fun isCallback(value: String): Boolean = runCatching {
            val u = URI(value)
            u.scheme == "tidal" && u.host == "login" && u.rawPath == "/auth" && u.rawUserInfo == null && u.port == -1 && u.rawFragment == null
        }.getOrDefault(false)
        @Volatile private var instance: TidalAccount? = null
        fun get(context: Context): TidalAccount = instance ?: synchronized(this) {
            instance ?: TidalAccount(AccountSession(context.applicationContext, "tidal_account")).also { instance = it }
        }
    }
}
