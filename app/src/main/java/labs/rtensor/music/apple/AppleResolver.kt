package labs.rtensor.music.apple

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.MusicProvider
import labs.rtensor.music.sources.ProviderStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
internal class AppleResolver(private val http: AppleHttp, private val runtime: AppleNativeAccess) {
    suspend fun resolve(track: Track, account: AppleCredentials, session: String?, source: String,
        current: () -> Boolean): List<ProviderStream> = withContext(Dispatchers.IO) {
        if (!current()) return@withContext emptyList()
        val region = account.storefront.ifBlank { http.storefront(account) }
        val search = "${AppleHttp.API}/catalog/$region/search"
        val url = search.toHttpUrl().newBuilder().addQueryParameter("term", "${track.title} ${track.artist}")
            .addQueryParameter("types", "songs").addQueryParameter("limit", "8").build().toString()
        val songs = http.jsonAsync(url, account).optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data") ?: JSONArray()
        val result = mutableListOf<ProviderStream>()
        for (i in 0 until minOf(songs.length(), 8)) {
            if (!current()) return@withContext emptyList()
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optString("id").takeIf { it.matches(Regex("[0-9]{1,24}")) } ?: continue
            val attributes = song.optJSONObject("attributes") ?: continue
            val title = attributes.optString("name"); val artist = attributes.optString("artistName")
            val duration = attributes.optLong("durationInMillis")
            val match = ProviderStream(id, MusicProvider.APPLE_MUSIC, "https://audio.itunes.apple.com/match", title, artist,
                duration, "audio/alac", 0, 0, 0)
            if (!match.matches(track)) continue
            val nativeJob = async {
            val found = mutableListOf<ProviderStream>()
            if (session != null) try {
                withTimeoutOrNull(10000) {
                runtime.ensure(session)
                val dispatch = runtime.playback(id)
                val masters = nativeUrls(dispatch, id) + nativeUrls(attributes.optJSONObject("extendedAssetUrls") ?: JSONObject(), id)
                for (master in masters.distinct().take(2)) {
                    val text = String(http.mediaAsync(master), Charsets.UTF_8)
                    val variants = AppleHls.variants(text, master).sortedWith(compareByDescending<AppleVariant> { it.codec in setOf("ec-3", "ec+3") }
                        .thenByDescending { it.codec == "alac" }.thenByDescending { it.rate }.thenByDescending { it.depth }.thenByDescending { it.bandwidth })
                    val choices = variants.ifEmpty { listOf(AppleVariant(master, "", "direct")) }
                    for (variant in choices.take(4)) try {
                        currentCoroutineContext().ensureActive()
                        val playlistText = if (variant.url == master) text else String(http.mediaAsync(variant.url), Charsets.UTF_8)
                        val playlist = AppleHls.playlist(playlistText, variant.url, native = true)
                        val init = http.initializationAsync(playlist.initialization)
                        val format = AppleHls.probe(init)
                        if (format.sampleMimeType !in setOf("audio/alac", "audio/eac3-joc", "audio/eac3", "audio/mp4a-latm")) continue
                        val identity = "$source:$id:${variant.codec}:${variant.group}:${format.sampleRate}:${format.channelCount}"
                        val asset = AppleMediaAsset(id, playlist, format, identity, account, session, runtime, AppleInitMetadata.prefetchKids(init), current)
                        found += stream(match, asset, source, variant.group, variant.depth)
                    } catch (error: CancellationException) { throw error } catch (_: Exception) { /* Try the next real rendition. */ }
                }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { /* AAC is a separate, authenticated fallback. */ }
            found
            }
            val webJob = async {
            val found = mutableListOf<ProviderStream>()
            try {
                withTimeoutOrNull(6000) {
                val response = http.jsonAsync(AppleHttp.PLAYBACK, account, JSONObject().put("salableAdamId", id))
                val rows = response.optJSONArray("songList") ?: JSONArray()
                for (rowIndex in 0 until minOf(rows.length(), 8)) {
                    val row = rows.getJSONObject(rowIndex)
                    val returnedId = row.optString("adamId").ifBlank { row.optString("songId") }
                    if (returnedId.isNotBlank() && returnedId != id) continue
                    val assets = row.optJSONArray("assets") ?: continue
                    val candidates = (0 until minOf(assets.length(), 32)).map { assets.getJSONObject(it) }
                        .filter { it.optString("flavor") in setOf("28:ctrp256", "28:ctrp64") }
                        .sortedByDescending { it.optString("flavor") == "28:ctrp256" }
                    for (item in candidates.take(1)) {
                        val media = item.optString("URL").ifBlank { item.optString("url") }
                        if (!AppleOrigins.isMedia(media)) continue
                        val playlist = AppleHls.playlist(String(http.mediaAsync(media), Charsets.UTF_8), media, native = false)
                        val format = AppleHls.probe(http.initializationAsync(playlist.initialization))
                        require(format.sampleMimeType == "audio/mp4a-latm" && format.channelCount in 1..2)
                        val flavor = item.optString("flavor")
                        val asset = AppleMediaAsset(id, playlist, format, "$source:$id:$flavor", account, isCurrent = current)
                        found += stream(match, asset, source, flavor, 0)
                    }
                }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { /* Unavailable editions are never claimed as playable. */ }
            found
            }
            result += nativeJob.await() + webJob.await()
            if (result.isNotEmpty()) break
        }
        if (current()) result else emptyList()
    }
    private fun stream(match: ProviderStream, asset: AppleMediaAsset, source: String, rendition: String, depth: Int): ProviderStream {
        val f = asset.format
        val bits = if (f.sampleMimeType == "audio/alac") f.initializationData.firstOrNull()?.let {
            if (it.size >= 24) (it[it.size - 19].toInt() and 255).takeIf { value -> value in setOf(16, 20, 24, 32) } else null
        } ?: depth else depth
        return match.copy(url = "https://audio.itunes.apple.com/music-account/${match.id}/playlist.m3u8", mime = f.sampleMimeType.orEmpty(),
            sampleRate = f.sampleRate.coerceAtLeast(0), channels = f.channelCount.coerceAtLeast(0), bitDepth = bits,
            atmos = f.sampleMimeType == "audio/eac3-joc", manifest = true, transportMime = "application/x-mpegURL",
            sourceId = source, representationId = rendition.ifBlank { asset.cacheIdentity }, appleAsset = asset)
    }
    internal fun nativeUrls(root: JSONObject, song: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(value: Any?, depth: Int, key: String = "") {
            require(depth <= 12)
            when (value) {
                is JSONObject -> {
                    val returnedId = value.optString("adamId").ifBlank { value.optString("songId") }
                    if (returnedId.isNotEmpty() && returnedId != song) return
                    require(value.length() <= 256)
                    value.keys().forEach { next -> walk(value.opt(next), depth + 1, next) }
                }
                is JSONArray -> { require(value.length() <= 256); for (i in 0 until value.length()) walk(value.opt(i), depth + 1, key) }
                is String -> if (AppleOrigins.isMedia(value) && (key.contains("hls", true) || value.substringBefore('?').endsWith(".m3u8"))) out += value
            }
        }
        walk(root, 0)
        return out.distinct().take(8)
    }
}
