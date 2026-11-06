package labs.rtensor.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import kotlinx.coroutines.*
import java.io.File
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import labs.rtensor.music.sources.PlaybackSources
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import labs.rtensor.music.MainActivity
import labs.rtensor.music.data.ResolvedAudio
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.YouTubeLink
import labs.rtensor.music.data.YouTubeSource
import kotlinx.coroutines.runBlocking
import java.io.IOException

@UnstableApi
class MusicPlaybackService : MediaLibraryService() {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    /** Library requests from the car; cancelled with the service. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioCache: SimpleCache? = null
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null
    private var preloadKey: String? = null
    private val paths = PlaybackPaths()
    private data class CachedAudio(val audio: ResolvedAudio, val createdAt: Long)
    private val resolvedStreams = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedAudio>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAudio>) = size > 32
        }
    )

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(PlayerNotification(this))
        val youtube = YouTubeSource(this)
        val spotifyMatches = labs.rtensor.music.spotify.SpotifyTrackMatcher.get(this)
        val http = PlaybackDataSource.httpFactory()
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, DataSource.Factory { GoogleVideoDataSource(http.createDataSource()) })) { spec ->
            val catalogueUrl = YouTubeLink.normalize(spec.uri.toString())
            if (catalogueUrl == null) spec else {
                // Media3 calls this on its loading thread, never the UI/audio-render thread.
                // Resolve only the item about to play, so a long queue cannot expire in advance.
                val cacheKey = spec.key ?: catalogueUrl
                val required = paths.format(cacheKey).takeIf { spec.position > 0 }
                val now = android.os.SystemClock.elapsedRealtime()
                val cached = resolvedStreams[cacheKey]?.takeIf { (required == null || (it.audio.formatId == required.formatId && it.audio.mimeType == required.mimeType && it.audio.contentLength == required.contentLength)) && it.audio.playbackPath !in paths.rejected(cacheKey) && now - it.createdAt < 600_000 && (Uri.parse(it.audio.url).getQueryParameter("expire")?.toLongOrNull()?.let { expiry -> expiry * 1000 - System.currentTimeMillis() > 60_000 } ?: true) }
                val audio = cached?.audio ?: try {
                    runBlocking { youtube.resolve(Track(catalogueUrl, "", "", catalogueUrl), rejectedPaths = paths.rejected(cacheKey), requiredFormat = required) }.also {
                        resolvedStreams[cacheKey] = CachedAudio(it, now)
                    }
                } catch (e: Exception) { throw IOException("YouTube audio resolution failed", e) }
                if (required != null && (audio.formatId != required.formatId || audio.mimeType != required.mimeType || audio.contentLength != required.contentLength))
                    throw IOException("The audio representation changed during a seek")
                paths.record(cacheKey, audio.playbackPath)
                if (spec.position == 0L) paths.rememberFormat(cacheKey, audio)
                spec.withUri(Uri.parse(audio.url)).withAdditionalHeaders(audio.headers).buildUpon().setCustomData(audio).build()
            }
        }
        val data = DataSource.Factory {
            PlaybackDataSource(resolving.createDataSource(), retryDenied = { spec ->
                val canonical = YouTubeLink.normalize(spec.uri.toString())
                val key = spec.key ?: canonical.orEmpty()
                if (canonical == null || !canonical.contains("/watch?v=") || !paths.rejectCurrent(key)) false
                else { resolvedStreams.remove(key); true }
            }) { spec, status ->
                val catalogueUrl = YouTubeLink.normalize(spec.uri.toString())
                if (catalogueUrl?.contains("/watch?v=") == true) {
                    resolvedStreams.remove(spec.key ?: catalogueUrl)
                    val key = spec.key ?: catalogueUrl
                    youtube.runtime.reportPlaybackHttpFailure(catalogueUrl.substringAfter("?v="), status, paths.rejected(key) + listOfNotNull(paths.current(key)))
                }
            }
        }
        val cache = SimpleCache(File(cacheDir, "playback-audio"), LeastRecentlyUsedCacheEvictor(128L * 1024 * 1024))
        audioCache = cache
        val cachedData = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(data)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val sources = PlaybackSources.get(this)
        // Account-server authorization is never attached to a returned CDN URL.
        // Adaptive segments use their own URLs, without an item-wide cache key.
        val remoteData = DefaultDataSource.Factory(this, PlaybackDataSource.httpFactory().setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000))
        val remoteCached = DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(remoteData).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR))
        val remoteAdaptive = DefaultMediaSourceFactory(remoteData)
        val remoteFactory = object : MediaSource.Factory by remoteCached {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                (if (mediaItem.localConfiguration?.mimeType in setOf("application/dash+xml", "application/x-mpegURL", "application/vnd.apple.mpegurl")) remoteAdaptive else remoteCached)
                    .createMediaSource(mediaItem)
        }
        val renderers = AudioPipeline.privateRenderers(this) ?: object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(AudioPipeline.processors())
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(ProviderMediaSourceFactory(DefaultMediaSourceFactory(cachedData), remoteFactory, sources,
                offline = labs.rtensor.music.data.Downloads.get(this)::offlineUri, local = DefaultMediaSourceFactory(this),
                resolveCatalogue = spotifyMatches::resolve))
            // A deeper forward buffer (as in open-source YouTube Music players) rides out
            // slow media-server ranges; 50 s of audio is well under 1 MB.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(50_000, 50_000, 750, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // Keep Wi-Fi awake too, so the screen-off buffer refills without stalling.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .apply {
                if (AudioPipeline.hasPrivateRenderer || labs.rtensor.dsp.NativeDownmixEngine.wasPackaged) {
                    setTrackSelector(DefaultTrackSelector(this@MusicPlaybackService).apply {
                        parameters = buildUponParameters().setConstrainAudioChannelCountToDeviceCapabilities(false).build()
                    })
                }
            }
            .build()
        fun tryNextConnectedSource(key: String): Boolean {
            val item = player.currentMediaItem ?: return false
            if (item.localConfiguration?.customCacheKey != key) return false
            if (!sources.reject(key)) return false
            // Recreate the whole child source. A failed FLAC/Atmos response
            // must never be replaced with another format inside a range read.
            val index = player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0)
            val playing = player.playWhenReady
            val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply {
                putInt("source_attempt", getInt("source_attempt") + 1)
            }
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build())
            player.seekTo(index, position); player.prepare(); player.playWhenReady = playing
            return true
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                if (eventTime.windowIndex != player.currentMediaItemIndex) return
                val key = player.currentMediaItem?.localConfiguration?.customCacheKey ?: return
                val selected = sources.state.value[key]?.stream?.cacheKey
                if (!sources.format(key, format)) android.os.Handler(mainLooper).post {
                    if (selected != null && sources.state.value[key]?.stream?.cacheKey == selected) tryNextConnectedSource(key)
                }
            }
        })
        var confirmedKey: String? = null
        fun confirmPlaying() {
            if (!player.isPlaying) return
            val config = player.currentMediaItem?.localConfiguration ?: return
            val key = config.customCacheKey ?: return
            val path = paths.current(key) ?: return
            if (key == confirmedKey || path in paths.rejected(key)) return
            val canonical = YouTubeLink.normalize(config.uri.toString()) ?: return
            confirmedKey = key
            preloadScope.launch {
                try { youtube.confirmPlayback(canonical.substringAfter("?v="), path) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Playback remains independent of preference hints. */ }
            }
        }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Classify here: the controller only receives the top-level cause.
                AudioPipeline.reportFailure(error)
                player.currentMediaItem?.localConfiguration?.customCacheKey?.let(::tryNextConnectedSource)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { AudioPipeline.clearReport(); confirmPlaying() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                confirmPlaying()
                if (!isPlaying || !player.hasNextMediaItem()) return
                val nextItem = player.getMediaItemAt(player.nextMediaItemIndex)
                val next = nextItem.localConfiguration ?: return
                val nextExtras = nextItem.mediaMetadata.extras
                if (nextExtras?.containsKey("local_master") == true) return
                val url = YouTubeLink.normalize(next.uri.toString())
                val catalogueTrack = nextExtras?.getString("catalogue_track")?.let { runCatching { Track.fromJson(org.json.JSONObject(it)) }.getOrNull() }
                if (url == null && catalogueTrack?.let(labs.rtensor.music.spotify.SpotifyTrackMatcher::pending) != true) return
                val key = next.customCacheKey ?: next.uri.toString()
                if (preloadKey == key) return
                preloadKey = key; preloadJob?.cancel()
                // Start only after current audio plays; never hold the next song's bytes or proof open.
                preloadJob = preloadScope.launch {
                    try {
                        if (sources.enabled) {
                            val extras = nextExtras
                            val track = extras?.getString("catalogue_track")?.let { Track.fromJson(org.json.JSONObject(it)) }
                            val local = extras?.getString("local_master")?.let { labs.rtensor.music.data.Master.fromJson(org.json.JSONObject(it)) }
                            if (track != null && sources.choose(track, key, local) != null) return@launch
                        }
                        val playbackUrl = url ?: spotifyMatches.resolve(checkNotNull(catalogueTrack)).url
                        val audio = youtube.resolve(Track(playbackUrl, "", "", playbackUrl), background = true)
                        if (audio.playbackPath !in paths.rejected(key)) resolvedStreams[key] = CachedAudio(audio, android.os.SystemClock.elapsedRealtime())
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Resolve normally on selection. */ }
                }
            }
        })
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val car = CarLibrary(this, mainScope, catalogueSearch = youtube::search, liveHome = {
            youtube.home().sections.flatMap { it.items }.filter { it.videoId.isNotBlank() }.map { it.asTrack() }
        })
        session = MediaLibraryService.MediaLibrarySession.Builder(this, SessionPlayer(player), MusicLibraryCallback(packageName, car, mainScope))
            .setSessionActivity(intent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    companion object {
        internal fun spotifyCatalogueItem(item: MediaItem): Boolean = PlayableItems.spotifyCatalogueItem(item)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        preloadScope.cancel(); mainScope.cancel(); audioCache?.release(); audioCache = null
        resolvedStreams.clear(); paths.clear()
        super.onDestroy()
    }
}
