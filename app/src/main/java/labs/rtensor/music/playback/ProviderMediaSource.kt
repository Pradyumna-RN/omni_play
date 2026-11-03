package labs.rtensor.music.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import labs.rtensor.music.data.Master
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.sources.PlaybackSources
import org.json.JSONObject
import java.io.IOException

/** Resolve before constructing the child source/cache: formats must never be
 * switched inside a byte-range request. The controller keeps the YouTube
 * catalogue item while the child player reads the selected recording. */
@UnstableApi
class ProviderMediaSourceFactory(private val fallback: MediaSource.Factory, private val remote: MediaSource.Factory,
    private val sources: PlaybackSources) : MediaSource.Factory by fallback {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val track = runCatching { mediaItem.mediaMetadata.extras?.getString("catalogue_track")?.let { Track.fromJson(JSONObject(it)) } }.getOrNull()
        if (track == null || track.source != Source.YOUTUBE || !sources.enabled) return fallback.createMediaSource(mediaItem)
        return ProviderMediaSource(mediaItem, track, fallback, remote, sources)
    }
}

@UnstableApi
private class ProviderMediaSource(private val item: MediaItem, private val track: Track,
    private val fallback: MediaSource.Factory, private val remote: MediaSource.Factory,
    private val sources: PlaybackSources) : CompositeMediaSource<Unit>() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var child: MediaSource? = null
    private var job: Job? = null
    private var released = false
    private var generation = 0
    private var failure: IOException? = null
    private var displayItem = item
    override fun getMediaItem(): MediaItem = displayItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        if (released) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        released = false; failure = null; displayItem = item
        val activeGeneration = ++generation
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(checkNotNull(Looper.myLooper()))
        val key = item.localConfiguration?.customCacheKey ?: item.mediaId
        val local = runCatching { item.mediaMetadata.extras?.getString("local_master")?.let { Master.fromJson(JSONObject(it)) } }.getOrNull()
        val fallbackLabel = item.mediaMetadata.extras?.getString("fallback_route") ?: item.mediaMetadata.extras?.getString("route") ?: "YouTube"
        job = scope.launch {
            try {
                val selected = sources.choose(track, key, local)
                handler.post {
                    if (released || generation != activeGeneration) return@post
                    try {
                        sources.selected(key, selected, fallbackLabel)
                        val label = selected?.requestedLabel ?: fallbackLabel
                        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putString("route", label) }
                        displayItem = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                        val physical = if (selected == null) item else item.buildUpon().setUri(selected.url)
                            .setMimeType(selected.transportMime)
                            .setCustomCacheKey(if (selected.transportMime in setOf("application/dash+xml", "application/x-mpegURL")) null else "$key:${selected.cacheKey}")
                            .build()
                        child = (if (selected == null) fallback else remote).createMediaSource(physical)
                        prepareChildSource(Unit, checkNotNull(child))
                    } catch (e: Exception) { failure = IOException("The connected source could not be prepared.", e) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { handler.post { if (!released && generation == activeGeneration) failure = IOException("The connected sources could not be resolved.") } }
        }
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) {
        refreshSourceInfo(object : ForwardingTimeline(timeline) {
            override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window =
                super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply { mediaItem = displayItem }
        })
    }
    override fun maybeThrowSourceInfoRefreshError() { failure?.let { throw it }; super.maybeThrowSourceInfoRefreshError() }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) { checkNotNull(child).releasePeriod(mediaPeriod) }
    override fun releaseSourceInternal() {
        released = true; generation++; job?.cancel(); scope.cancel(); super.releaseSourceInternal(); child = null
    }
}
