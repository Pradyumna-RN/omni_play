package labs.rtensor.music.sources

import android.content.Context
import androidx.media3.common.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import labs.rtensor.dsp.DownmixReport
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.music.data.Master
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Track
import java.util.concurrent.ConcurrentHashMap

data class SourcePlaybackInfo(val stream: ProviderStream?, val fallbackLabel: String, val actualFormat: Format? = null) {
    fun label(render: DownmixReport?): String {
        val remote = stream ?: return fallbackLabel
        val f = actualFormat ?: return remote.requestedLabel
        val codec = when (f.sampleMimeType) { "audio/flac" -> "FLAC"; "audio/alac" -> "ALAC"; "audio/eac3-joc" -> "Atmos"; "audio/eac3" -> "E-AC-3"; "audio/mp4a-latm" -> "AAC"; else -> "Audio" }
        val multichannel = render?.inputPositions?.size?.let { it > 2 } == true
        val route = when {
            multichannel && render?.listeningMode == ListeningMode.SPATIAL -> "${render.inputPositions.size} ch → headphones"
            multichannel && render?.processingEnabled == true -> "${render.inputPositions.size} ch → DMR"
            multichannel -> "${render!!.inputPositions.size} ch · original front L/R"
            render == null -> "checking decoded channels"
            f.sampleMimeType == "audio/eac3-joc" -> "stereo decode · DMR unavailable"
            else -> "stereo · unchanged"
        }
        val rate = f.sampleRate.takeIf { it > 0 }?.let { " ${it / 1000f} kHz" }.orEmpty()
        return "${remote.provider.label} · $codec$rate · $route"
    }
}

/** One shared source registry for the foreground player and its UI. No network
 * request runs on the UI or audio-render thread. A resolved URL is pinned to the
 * lifetime of its MediaSource; a seek never selects a different encoding. */
class PlaybackSources(val settings: SourceSettings, val tidal: TidalAccount? = null,
    private val clientFactory: (SourceConnection) -> SourceServerClient = { SourceServerClient(it) }) {
    private val mutable = MutableStateFlow<Map<String, SourcePlaybackInfo>>(emptyMap())
    val state = mutable.asStateFlow()
    private val rejected = ConcurrentHashMap<String, MutableSet<String>>()
    private data class Resolved(val streams: List<ProviderStream>, val time: Long, val settings: List<SourceConnection>, val tidalRevision: Long)
    private val resolved = object : LinkedHashMap<String, Resolved>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resolved>) = size > 32
    }
    val enabled: Boolean get() = tidal?.state?.value?.connected == true || settings.connections.value.any { it.enabled }

    suspend fun choose(track: Track, key: String, local: Master?): ProviderStream? = coroutineScope {
        if (rejected[key].orEmpty().size >= 6) return@coroutineScope null
        val profiles = settings.connections.value.filter { it.enabled }
        val tidalRevision = tidal?.revision ?: 0L
        val cached = synchronized(resolved) { resolved[key]?.takeIf { System.currentTimeMillis() - it.time < 120_000 && it.settings == profiles && it.tidalRevision == tidalRevision } }
        val streams = cached?.streams ?: (profiles.map { connection -> async {
            withTimeoutOrNull(4500) { runCatching { clientFactory(connection).resolve(track) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            } }.orEmpty()
        } } + listOfNotNull(tidal?.takeIf { it.state.value.connected }?.let { account -> async {
            withTimeoutOrNull(6500) { account.resolve(track) }.orEmpty()
        } })).flatMap { it.await() }.also { candidates -> synchronized(resolved) { resolved[key] = Resolved(candidates, System.currentTimeMillis(), profiles, tidalRevision) } }
        var remote = SourceQuality.best(track, streams.filter { it.cacheKey !in rejected[key].orEmpty() })
        if (local != null && remote != null) {
            val comparator = PlaybackRoute.qualityOrder
            if (comparator.compare(local, remote.asMaster()) >= 0) remote = null
        }
        remote
    }

    @Synchronized fun selected(key: String, stream: ProviderStream?, fallback: String) {
        mutable.value = (mutable.value + (key to SourcePlaybackInfo(stream, fallback))).entries.toList().takeLast(128).associate { it.key to it.value }
    }
    /** False means the returned bytes contradict the selected representation.
     * Recreate the source instead of silently accepting an AAC/stereo downgrade. */
    @Synchronized fun format(key: String, format: Format): Boolean {
        val current = mutable.value[key] ?: return true
        mutable.value = mutable.value + (key to current.copy(actualFormat = format))
        val expected = current.stream ?: return true
        val actualMime = format.sampleMimeType
        if (expected.lossless && actualMime != null && actualMime !in setOf("audio/flac", "audio/alac", "audio/raw", "audio/wav", "audio/x-wav")) return false
        if (expected.verifiedAtmosCodec && actualMime != null && actualMime != "audio/eac3-joc") return false
        if (expected.verifiedAtmosCodec) {
            if (expected.channels > 2 && format.channelCount in 1..2) return false
        } else if (expected.channels > 0 && format.channelCount > 0 && expected.channels != format.channelCount) return false
        if (expected.sampleRate > 0 && format.sampleRate > 0 && expected.sampleRate != format.sampleRate) return false
        val previous = current.actualFormat
        return previous == null ||
            ((previous.sampleMimeType == null || actualMime == null || previous.sampleMimeType == actualMime) &&
                (previous.channelCount <= 0 || format.channelCount <= 0 || previous.channelCount == format.channelCount) &&
                (previous.sampleRate <= 0 || format.sampleRate <= 0 || previous.sampleRate == format.sampleRate))
    }
    fun reject(key: String): Boolean {
        val current = mutable.value[key]?.stream ?: return false
        if (rejected.size > 128) rejected.keys.firstOrNull { it != key }?.let(rejected::remove)
        val entries = rejected.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
        return entries.size < 6 && entries.add(current.cacheKey)
    }
    fun isRemote(key: String) = mutable.value[key]?.stream != null
    fun label(key: String, render: DownmixReport?) = mutable.value[key]?.label(render)
    companion object {
        @Volatile private var instance: PlaybackSources? = null
        fun get(context: Context): PlaybackSources = instance ?: synchronized(this) {
            instance ?: PlaybackSources(SourceSettings(context.applicationContext), tidal = TidalAccount.get(context)).also { instance = it }
        }
    }
}
