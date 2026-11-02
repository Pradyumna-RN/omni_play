package labs.rtensor.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import labs.rtensor.music.MainActivity
import labs.rtensor.music.data.ResolvedAudio
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.YouTubeLink
import labs.rtensor.music.data.YouTubeSource
import kotlinx.coroutines.runBlocking
import java.io.IOException

@UnstableApi
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private data class CachedAudio(val audio: ResolvedAudio, val createdAt: Long)
    private val resolvedStreams = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedAudio>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAudio>) = size > 32
        }
    )

    override fun onCreate() {
        super.onCreate()
        val youtube = YouTubeSource(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Music/0.3 (Android)")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val catalogueUrl = YouTubeLink.normalize(spec.uri.toString())
            if (catalogueUrl == null) spec else {
                // Media3 calls this on its loading thread, never the UI/audio-render thread.
                // Resolve only the item about to play, so a long queue cannot expire in advance.
                val cacheKey = spec.key ?: catalogueUrl
                val now = android.os.SystemClock.elapsedRealtime()
                val cached = resolvedStreams[cacheKey]?.takeIf { now - it.createdAt < 180_000 }
                val audio = cached?.audio ?: try {
                    runBlocking { youtube.resolve(Track(catalogueUrl, "", "", catalogueUrl)) }.also {
                        resolvedStreams[cacheKey] = CachedAudio(it, now)
                    }
                } catch (e: Exception) { throw IOException("YouTube audio resolution failed", e) }
                spec.withUri(Uri.parse(audio.url)).withAdditionalHeaders(audio.headers)
            }
        }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(AudioPipeline.processors())
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(intent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    return super.onConnect(session, controller)
                }

                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                    if (controller.packageName != packageName) return Futures.immediateFuture(mutableListOf())
                    val permitted = mediaItems.filter {
                        val uri = it.localConfiguration?.uri
                        uri?.scheme == "content" || (uri?.scheme == "https" && YouTubeLink.normalize(uri.toString())?.contains("/watch?v=") == true)
                    }
                    return Futures.immediateFuture(permitted.toMutableList())
                }
            }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        resolvedStreams.clear()
        super.onDestroy()
    }
}
