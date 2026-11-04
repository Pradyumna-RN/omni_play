package labs.rtensor.music.apple

import android.net.Uri
import android.util.Base64
import androidx.media3.common.*
import androidx.media3.common.util.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.*
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID

@UnstableApi
internal object AppleMediaSource {
    fun create(item: MediaItem, asset: AppleMediaAsset): MediaSource {
        val active = ThreadLocal<AppleSegment>()
        val factory = HlsMediaSource.Factory(DataSource.Factory { AppleDataSource(asset, active) })
            .setAllowChunklessPreparation(false)
        if (asset.native) factory.setExtractorFactory(NativeExtractorFactory(asset, active))
        else {
            val manager = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(AppleLicense(asset))
            factory.setDrmSessionManagerProvider { manager }
        }
        return factory.createMediaSource(item.buildUpon().setUri("music-apple://playlist/${asset.songId}")
            .setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build())
    }
}

@UnstableApi
internal class AppleDataSource(private val asset: AppleMediaAsset, private val active: ThreadLocal<AppleSegment>,
    private val upstream: DataSource.Factory = DefaultHttpDataSource.Factory().setUserAgent(AppleHttp.AGENT)
        .setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(12000).setReadTimeoutMs(15000)) : DataSource {
    private var delegate: DataSource? = null
    private var uri: Uri? = null
    override fun addTransferListener(listener: TransferListener) { /* No shared CDN cache or credential-bearing listener. */ }
    override fun open(spec: DataSpec): Long {
        check(asset.isCurrent()) { "Apple account changed. Start playback again." }
        require(spec.httpRequestHeaders.isEmpty())
        val value = spec.uri
        require(value.scheme == "music-apple" && value.userInfo == null && value.query == null && value.fragment == null)
        uri = value
        val source: DataSource; val mapped: DataSpec
        if (value.host == "playlist" && value.path == "/${asset.songId}") {
            active.remove()
            source = ByteArrayDataSource(asset.playlist.text.toByteArray(Charsets.UTF_8)); mapped = spec
        } else {
            require(value.host == "stream")
            val index = value.path?.removePrefix("/")?.toIntOrNull() ?: error("Invalid Apple media reference")
            val entry = asset.playlist.entries.getOrNull(index) ?: error("Unknown Apple media reference")
            require(value.path == "/$index" && AppleOrigins.isMedia(entry.url))
            if (!entry.initialization) active.set(entry) else active.remove()
            source = upstream.createDataSource()
            mapped = spec.buildUpon().setUri(entry.url).setKey(null).build()
        }
        delegate = source
        return try { source.open(mapped) } catch (error: Exception) { close(); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!asset.isCurrent()) throw IOException("Apple account changed. Start playback again.")
        return checkNotNull(delegate).read(buffer, offset, length)
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null; uri = null; active.remove() } }
}

@UnstableApi
private class AppleLicense(private val asset: AppleMediaAsset, private val http: AppleHttp = AppleHttp()) : MediaDrmCallback {
    private val provisioning = HttpMediaDrmCallback("", DefaultHttpDataSource.Factory())
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray = provisioning.executeProvisionRequest(uuid, request)
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        try {
            require(uuid == C.WIDEVINE_UUID && !asset.native && asset.isCurrent() && request.data.size <= 128 * 1024)
            val response = http.json(AppleHttp.LICENSE, asset.credentials, JSONObject()
                .put("challenge", Base64.encodeToString(request.data, Base64.NO_WRAP)).put("key-system", "com.widevine.alpha")
                .put("uri", checkNotNull(asset.playlist.webKeyUri)).put("adamId", asset.songId).put("isLibrary", false).put("user-initiated", true))
            val license = response.optString("license")
            require(license.length in 1..(512 * 1024) && asset.isCurrent())
            return Base64.decode(license, Base64.DEFAULT)
        } catch (_: Exception) {
            val spec = DataSpec(Uri.parse(AppleHttp.LICENSE))
            throw MediaDrmCallbackException(spec, spec.uri, emptyMap(), 0, IOException("Apple could not authorize AAC playback. Check the subscription and sign in again."))
        }
    }
}

@UnstableApi
private class NativeExtractorFactory(private val asset: AppleMediaAsset, private val active: ThreadLocal<AppleSegment>) : HlsExtractorFactory {
    private val delegate = DefaultHlsExtractorFactory()
    override fun createExtractor(uri: Uri, format: Format, captions: MutableList<Format>?, timestamp: TimestampAdjuster,
        headers: MutableMap<String, MutableList<String>>, input: ExtractorInput, player: PlayerId): HlsMediaChunkExtractor =
        Wrapped(delegate.createExtractor(uri, format, captions, timestamp, headers, input, player))
    private inner class Wrapped(private val inner: HlsMediaChunkExtractor) : HlsMediaChunkExtractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = output.track(id, type)
                return if (type == C.TRACK_TYPE_AUDIO) NativeTrackOutput(target, asset, active) else target
            }
        })
        override fun recreate(): HlsMediaChunkExtractor = Wrapped(inner.recreate())
    }
}

@UnstableApi
internal class NativeTrackOutput(private val output: TrackOutput, private val asset: AppleMediaAsset,
    private val active: ThreadLocal<AppleSegment>) : TrackOutput {
    private val pending = ByteArrayOutputStream()
    override fun format(format: Format) {
        require(format.sampleMimeType == asset.format.sampleMimeType && format.channelCount == asset.format.channelCount && format.sampleRate == asset.format.sampleRate)
        output.format(format.buildUpon().setDrmInitData(null).setCryptoType(C.CRYPTO_TYPE_NONE).build())
    }
    override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int {
        require(length >= 0 && part != TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        val buffer = ByteArray(minOf(length, 16384))
        val count = input.read(buffer, 0, buffer.size)
        if (count < 0) { if (allowEnd) return C.RESULT_END_OF_INPUT; throw EOFException() }
        append(buffer, count); return count
    }
    override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
        require(length in 0..data.bytesLeft() && length <= 262144 && part != TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        val bytes = ByteArray(length); data.readBytes(bytes, 0, length); append(bytes, length)
    }
    private fun append(bytes: ByteArray, count: Int) { require(pending.size() <= 262144 - count); pending.write(bytes, 0, count) }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
        require(offset == 0 && size == pending.size() && asset.isCurrent())
        val input = pending.toByteArray(); pending.reset()
        val encoded = if (flags and C.BUFFER_FLAG_ENCRYPTED != 0) {
            val metadata = checkNotNull(crypto)
            val entry = checkNotNull(active.get()) { "Apple segment key is unavailable." }
            val key = checkNotNull(entry.keyUri)
            require(entry.keyFormat == AppleHls.FAIRPLAY && asset.native)
            val kid = metadata.encryptionKey.joinToString("") { "%02x".format(it) }
            val bootstrap = kid in asset.prefetchKids
            AppleSample.decode(input, metadata, bootstrap) { encrypted ->
                checkNotNull(asset.nativeClient).decrypt(checkNotNull(asset.nativeSession), asset.songId, key, encrypted)
            }
        } else input
        output.sampleData(ParsableByteArray(encoded), encoded.size)
        output.sampleMetadata(timeUs, flags and C.BUFFER_FLAG_ENCRYPTED.inv(), encoded.size, 0, null)
        encoded.fill(0)
    }
}
