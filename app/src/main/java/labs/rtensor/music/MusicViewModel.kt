package labs.rtensor.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.SourceException
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.YouTubeSource
import labs.rtensor.music.playback.MusicPlaybackService

data class MusicState(
    val current: Track? = null,
    val loadingTrack: Track? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val resolving: Boolean = false,
    val playerReady: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val query: String = "",
    val searched: Boolean = false,
    val searching: Boolean = false,
    val results: List<Track> = emptyList(),
    val saved: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val volume: Float = 1f,
    val error: String? = null,
)

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    private val youtube = YouTubeSource(application)
    private val mutable = MutableStateFlow(MusicState(
        saved = library.tracks("saved"), history = library.tracks("history"),
        theme = library.theme, reducedMotion = library.reducedMotion,
    ))
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private val controllerFuture = MediaController.Builder(
        application, SessionToken(application, ComponentName(application, MusicPlaybackService::class.java))
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer(includeQueue = true) }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayer()
            if (isPlaying) mutable.value.current?.let(::recordHistory)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayer()
        }
        override fun onPlayerError(error: PlaybackException) {
            val sourceError = generateSequence(error as Throwable) { it.cause }.filterIsInstance<SourceException>().firstOrNull()
            mutable.update { it.copy(error = sourceError?.message ?: "Playback stopped. Check your connection and tap Retry to refresh the audio link.", resolving = false, buffering = false) }
        }
    }

    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                syncPlayer(includeQueue = true)
                mutable.update { it.copy(playerReady = true) }
            }.onFailure {
                mutable.update { it.copy(error = "The audio player could not start. Close and reopen the app.") }
            }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            while (isActive) { syncPlayer(); delay(200) }
        }
    }

    private fun syncPlayer(includeQueue: Boolean = false) {
        val c = controller ?: return
        val restored = runCatching {
            c.currentMediaItem?.mediaMetadata?.extras?.getString("catalogue_track")?.let { Track.fromJson(org.json.JSONObject(it)) }
        }.getOrNull()
        val queue = if (!includeQueue) mutable.value.queue else (0 until c.mediaItemCount).mapNotNull { index ->
            runCatching { c.getMediaItemAt(index).mediaMetadata.extras?.getString("catalogue_track")?.let { Track.fromJson(org.json.JSONObject(it)) } }.getOrNull()
        }
        mutable.update { it.copy(
            current = restored ?: it.current,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0,
            volume = c.volume,
        ) }
    }

    fun setQuery(query: String) { mutable.update { it.copy(query = query.take(400)) } }

    fun search() {
        val query = mutable.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true, searched = true, error = null, results = emptyList()) }
            try {
                val results = youtube.search(query)
                mutable.update { it.copy(results = results, searching = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(searching = false, error = sourceMessage(e)) }
            }
        }
    }

    fun play(track: Track, queue: List<Track> = listOf(track)) {
        val c = controller ?: run { mutable.update { it.copy(error = "The audio player is still starting. Try again in a moment.") }; return }
        val ordered = queue.take(100).let { if (it.any { t -> t.id == track.id }) it else listOf(track) + it }
        val selectedIndex = ordered.indexOfFirst { it === track }.takeIf { it >= 0 }
            ?: ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = ordered.map { item ->
            val extras = Bundle().apply { putString("catalogue_track", item.json().toString()) }
            val metadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist)
                .setArtworkUri(item.artwork.takeIf(String::isNotBlank)?.let(Uri::parse)).setExtras(extras).build()
            MediaItem.Builder().setMediaId(item.id).setUri(item.url)
                .setCustomCacheKey(java.util.UUID.randomUUID().toString())
                .setMediaMetadata(metadata).build()
        }
        mutable.update { it.copy(current = track, loadingTrack = null, error = null, queue = ordered, queueIndex = selectedIndex, positionMs = 0, durationMs = 0) }
        c.setMediaItems(items, selectedIndex, 0)
        c.prepare()
        c.play()
    }

    fun toggle() {
        mutable.value.loadingTrack?.let { if (!mutable.value.resolving) play(it, mutable.value.queue); return }
        val c = controller ?: return
        if (c.isPlaying || c.playWhenReady) c.pause()
        else if (c.playerError != null || c.playbackState == Player.STATE_ENDED) retry()
        else c.play()
    }

    fun retry() {
        val s = mutable.value
        (s.queue.getOrNull(s.queueIndex) ?: s.current)?.let { play(it, s.queue) }
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        val s = mutable.value
        if (s.positionMs > 3000) { seek(0); return }
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem() ?: seek(0)
    }

    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, mutable.value.durationMs)); syncPlayer() }
    fun volume(value: Float) { controller?.volume = value.coerceIn(0f, 1f); syncPlayer() }

    fun save(track: Track) {
        val old = mutable.value.saved
        val next = if (old.any { it.id == track.id }) old.filterNot { it.id == track.id } else listOf(track) + old
        library.writeTracks("saved", next)
        mutable.update { it.copy(saved = next) }
    }

    private fun recordHistory(track: Track) {
        val history = (listOf(track) + mutable.value.history.filterNot { it.id == track.id }).take(40)
        if (history == mutable.value.history) return
        library.writeTracks("history", history)
        mutable.update { it.copy(history = history) }
    }

    fun openLocal(uri: Uri) {
        val app = getApplication<Application>()
        val title = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Local recording"
        play(Track("local:$uri", title.substringBeforeLast('.', title), "On your device", uri.toString(), source = Source.LOCAL))
    }

    fun theme(value: String) {
        if (value !in setOf("light", "dark", "system")) return
        library.theme = value; mutable.update { it.copy(theme = value) }
    }
    fun reducedMotion(value: Boolean) { library.reducedMotion = value; mutable.update { it.copy(reducedMotion = value) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun clearHistory() { library.writeTracks("history", emptyList()); mutable.update { it.copy(history = emptyList()) } }

    private fun sourceMessage(e: Exception): String =
        if (e is SourceException) e.message.orEmpty() else "Could not load this recording. Check your connection and retry."

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
