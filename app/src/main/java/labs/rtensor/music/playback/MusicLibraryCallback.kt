package labs.rtensor.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Session callback for the phone UI, the media notification and the car.
 *
 * The phone's own controller keeps its existing rules: it may queue local documents, YouTube
 * recordings and pending Spotify entries, and other apps may not add items. Android Auto and
 * Android Automotive may also browse the library and play songs by browse ID; they never supply
 * a URI of their own. Library callbacks run on the session thread and return futures.
 */
@UnstableApi
internal class MusicLibraryCallback(
    private val appPackage: String,
    private val car: CarLibrary,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        if (controller.packageName != appPackage && !controller.isTrusted && !isCar(session, controller)) return MediaSession.ConnectionResult.reject()
        return super.onConnect(session, controller)
    }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        if (carRequest(mediaSession, controller, mediaItems))
            return future(mediaSession, controller) { car.resolveEach(mediaItems).toMutableList() }
        if (controller.packageName != appPackage) return Futures.immediateFuture(mutableListOf())
        return Futures.immediateFuture(mediaItems.filter(PlayableItems::permitted).toMutableList())
    }

    override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (carRequest(mediaSession, controller, mediaItems))
            return future(mediaSession, controller) { car.play(mediaItems, startIndex, startPositionMs) }
        return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        if (!mayBrowse(session, browser)) Futures.immediateFuture(denied()) else Futures.immediateFuture(car.root(params, key(browser)))

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        if (!mayBrowse(session, browser)) Futures.immediateFuture(denied()) else future(null, null) { car.item(mediaId) }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        if (!mayBrowse(session, browser)) Futures.immediateFuture(denied())
        else future(null, null) { car.children(parentId, page, pageSize, params, key(browser)) }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        if (!mayBrowse(session, browser)) return Futures.immediateFuture(denied())
        // Answer at once; the result count follows when the search finishes.
        scope.launch {
            val count = try { car.search(query) } catch (e: CancellationException) { throw e } catch (_: Exception) { 0 }
            runCatching { session.notifySearchResultChanged(browser, query, count, params) }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        if (!mayBrowse(session, browser)) Futures.immediateFuture(denied())
        else future(null, null) { car.searchPage(query, page, pageSize, params) }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

    /** Saved songs and history are private: only Music itself and the car may list them. */
    private fun mayBrowse(session: MediaSession, controller: MediaSession.ControllerInfo) =
        controller.packageName == appPackage || isCar(session, controller)

    private fun carRequest(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>) =
        items.isNotEmpty() && items.all(CarLibrary::isRequest) && mayBrowse(session, controller)

    private fun key(browser: MediaSession.ControllerInfo) = "${browser.packageName}/${browser.uid}"

    private fun <T : Any> denied(): LibraryResult<T> = LibraryResult.ofError<T>(SessionError(SessionError.ERROR_PERMISSION_DENIED, "Music's library is not available to this app."))

    /** Run [block] on the session's scope. A failed car request leaves the queue alone and tells the driver why. */
    private fun <T> future(session: MediaSession?, controller: MediaSession.ControllerInfo?, block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) {
                if (session != null && controller != null) {
                    val error = (e as? CarRequestException)?.let { SessionError(it.code, it.message.orEmpty()) }
                        ?: SessionError(SessionError.ERROR_UNKNOWN, "This song could not start. Try again.")
                    runCatching { session.sendError(controller, error) }
                }
                result.setException(e)
            }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }
}
