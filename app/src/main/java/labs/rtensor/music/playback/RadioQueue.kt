package labs.rtensor.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import org.json.JSONObject

/** Appends provider recommendations to the actual player queue. History is not
 * a candidate pool. A new selection invalidates any older in-flight request. */
@UnstableApi
internal class RadioQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val fetch: suspend (Track, Collection<String>) -> List<Track>,
    private val mediaItem: (Track) -> MediaItem,
    private val status: (loading: Boolean, error: String?) -> Unit,
    private val providerLabel: () -> String = { "YouTube Music" },
) {
    private var job: Job? = null
    private var generation = 0L
    private val tried = mutableSetOf<String>()

    fun reset() {
        generation++
        job?.cancel(); job = null; tried.clear()
        status(false, null)
    }

    fun retry() {
        if (job?.isActive == true) return
        track(player.currentMediaItem)?.let { tried.remove(it.libraryKey()) }
        request(force = true)
    }

    /** Keep current playback and user-queued items; replace only our future radio tail. */
    fun changeProvider() {
        reset()
        for (index in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(index).mediaMetadata.extras?.getString(GENERATED) != null) player.removeMediaItem(index)
        }
        request()
    }

    fun request(force: Boolean = false) {
        if (!enabled() || job?.isActive == true || player.mediaItemCount == 0 || player.mediaItemCount >= LIMIT) return
        if (!force && player.currentMediaItemIndex < player.mediaItemCount - 2) return
        val seed = track(player.currentMediaItem)?.takeIf { it.source == Source.YOUTUBE } ?: return
        val key = seed.libraryKey()
        if (!tried.add(key)) return
        val version = generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            status(true, null)
            try {
                val exclude = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                val candidates = fetch(seed, exclude)
                ensureActive()
                if (version != generation) return@launch
                if (!enabled() || player.mediaItemCount == 0 || player.mediaItemCount >= LIMIT) {
                    tried.remove(key); status(false, null)
                    return@launch
                }
                // Re-read after the request: the user may have queued another song meanwhile.
                val queued = (0 until player.mediaItemCount).mapNotNull { track(player.getMediaItemAt(it))?.libraryKey() }.toSet()
                val added = candidates.asSequence().filter { it.source == Source.YOUTUBE }
                    .filter { it.libraryKey() != key && it.libraryKey() !in queued }
                    .distinctBy { it.libraryKey() }.take((LIMIT - player.mediaItemCount).coerceAtLeast(0)).toList()
                if (added.isEmpty()) {
                    tried.remove(key)
                    status(false, "${providerLabel()} has no more recommendations for this song.")
                } else {
                    // A slow answer can arrive after the last song finished. Media3 stays ended when
                    // songs are appended then, so continue with the first recommendation ourselves.
                    val finished = player.playbackState == Player.STATE_ENDED && player.currentMediaItemIndex == player.mediaItemCount - 1
                    val firstAdded = player.mediaItemCount
                    player.addMediaItems(added.map { candidate ->
                        val item = mediaItem(candidate)
                        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply { putString(GENERATED, providerLabel()) }
                        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                    })
                    if (finished && player.playWhenReady) player.seekToDefaultPosition(firstAdded)
                    status(false, null)
                }
            } catch (e: CancellationException) {
                if (version == generation) { tried.remove(key); status(false, null) }
                throw e
            } catch (_: Exception) {
                if (version == generation) {
                    tried.remove(key)
                    status(false, "Couldn’t load ${providerLabel()} recommendations. Check the account or connection and retry.")
                }
            }
        }
        job = next
        next.start()
    }

    private fun track(item: MediaItem?): Track? = runCatching {
        item?.mediaMetadata?.extras?.getString("catalogue_track")?.let { Track.fromJson(JSONObject(it)) }
    }.getOrNull()

    private companion object { const val LIMIT = 100; const val GENERATED = "music_radio_provider" }
}
