package labs.rtensor.music

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.SourceException
import labs.rtensor.music.data.Track
import labs.rtensor.music.playback.CarLibrary
import labs.rtensor.music.playback.MusicLibraryCallback
import labs.rtensor.music.playback.PlayableItems
import labs.rtensor.music.recommendations.RecommendationEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAutoLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searched = mutableListOf<String>()
    private val liveHomeCalls = AtomicInteger()
    private var liveHome: () -> List<Track> = { emptyList() }
    private var searchResults: (String) -> List<Track> = { emptyList() }
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var callback: MusicLibraryCallback

    private val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.google.android.projection.gearhead", 1, 10_001, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle())
    private val otherApp = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.example.reader", 2, 10_002, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle())
    private val trustedOtherApp = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.android.systemui", 3, 10_003, MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle())

    private fun yt(n: Int, title: String = "Song $n", artist: String = "Artist $n") = "%011d".format(n).let { id ->
        Track("yt:$id", title, artist, "https://www.youtube.com/watch?v=$id", "https://i.ytimg.com/vi/$id/hq.jpg", 200_000L + n)
    }
    private val local = Track("local:doc", "Home recording", "Me", "content://audio/document/doc", source = Source.LOCAL)
    private val spotify = Track("spotify:${"b".repeat(22)}", "Afterimage", "Mira", "https://open.spotify.com/track/${"b".repeat(22)}",
        "https://i.scdn.co/image/x", 180_000, spotifyId = "b".repeat(22))

    private val store get() = LibraryStore(context)

    @Before fun setUp() {
        context.getSharedPreferences("music_library", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "listening-history.json").delete()
        File(context.filesDir, "masters.json").delete()
        val library = CarLibrary(context, scope,
            catalogueSearch = { q -> synchronized(searched) { searched += q }; searchResults(q) },
            liveHome = { liveHomeCalls.incrementAndGet(); liveHome() })
        callback = MusicLibraryCallback(context.packageName, library, scope)
        player = ExoPlayer.Builder(context).build()
        session = MediaLibrarySession.Builder(context, player, callback).setId("auto-test-${System.nanoTime()}").build()
    }

    @After fun tearDown() {
        session.release(); player.release(); scope.cancel()
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return future.get()
    }

    private fun rootParams(limit: Int? = null, recent: Boolean = false) = LibraryParams.Builder().setRecent(recent)
        .setExtras(Bundle().apply { limit?.let { putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, it) } }).build()
    private fun root(params: LibraryParams? = rootParams(), browser: MediaSession.ControllerInfo = car) = await(callback.onGetLibraryRoot(session, browser, params))
    private fun children(parent: String, page: Int = 0, size: Int = 100, browser: MediaSession.ControllerInfo = car) =
        await(callback.onGetChildren(session, browser, parent, page, size, null))
    private fun ids(result: LibraryResult<out List<MediaItem>>) = result.value!!.map { it.mediaId }
    private fun play(mediaId: String, controller: MediaSession.ControllerInfo = car) =
        await(callback.onSetMediaItems(session, controller, mutableListOf(MediaItem.Builder().setMediaId(mediaId).build()), C.INDEX_UNSET, C.TIME_UNSET))
    private fun voice(query: String) = await(callback.onSetMediaItems(session, car, mutableListOf(MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()), C.INDEX_UNSET, C.TIME_UNSET))
    private fun catalogueTrack(item: MediaItem) = Track.fromJson(JSONObject(item.mediaMetadata.extras!!.getString("catalogue_track")!!))
    private fun failure(future: ListenableFuture<*>): Throwable = runCatching { await(future) }.exceptionOrNull()?.cause ?: error("Expected a failure")

    @Test fun manifestDeclaresAMediaBrowserForAndroidAuto() {
        val pm = context.packageManager
        for (action in listOf("android.media.browse.MediaBrowserService", "androidx.media3.session.MediaLibraryService", "androidx.media3.session.MediaSessionService")) {
            val services = pm.queryIntentServices(android.content.Intent(action).setPackage(context.packageName), 0)
            assertEquals(action, listOf("labs.rtensor.music.playback.MusicPlaybackService"), services.map { it.serviceInfo.name })
        }
        val meta = pm.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
        val descriptor = meta.getInt("com.google.android.gms.car.application")
        assertNotEquals(0, descriptor)
        val parser = context.resources.getXml(descriptor)
        var uses: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "uses") uses = parser.getAttributeValue(null, "name")
        }
        assertEquals("media", uses)
        assertNotEquals(0, meta.getInt("com.google.android.gms.car.notification.SmallIcon"))
    }

    @Test fun rootOffersBrowsableTabsWithCarContentStylesAndSearch() {
        val result = root()
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.mediaMetadata.isBrowsable!!)
        assertFalse(result.value!!.mediaMetadata.isPlayable!!)
        val extras = result.params!!.extras
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        assertTrue(extras.getBoolean("android.media.browse.SEARCH_SUPPORTED"))

        val tabs = children(CarLibrary.ROOT).value!!
        assertEquals(listOf("Home", "Saved", "Recent"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, tabs[0].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, tabs[1].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        // A subscription resolves the tab itself first.
        assertEquals(LibraryResult.RESULT_SUCCESS, await(callback.onGetItem(session, car, "car:tab:saved")).resultCode)
    }

    @Test fun rootRespectsTheCarsTabLimitAndDeclinesRecentRoots() {
        root(rootParams(limit = 2))
        assertEquals(listOf("car:tab:home", "car:tab:saved"), ids(children(CarLibrary.ROOT)))
        val recent = root(rootParams(recent = true))
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, recent.resultCode)
    }

    @Test fun historyOffHidesRecentAndItsSongs() {
        store.writeTracks("history", listOf(yt(1)))
        store.trackHistory = false
        assertEquals(listOf("car:tab:home", "car:tab:saved"), ids(children(CarLibrary.ROOT)))
        val recent = children("car:tab:recent").value!!
        assertEquals(1, recent.size)
        assertFalse(recent[0].mediaMetadata.isPlayable!!)
        assertEquals("Listening history is off", recent[0].mediaMetadata.title.toString())
        assertNotEquals(LibraryResult.RESULT_SUCCESS, await(callback.onGetItem(session, car, "car:tab:recent")).resultCode)
        assertTrue(failure(callback.onSetMediaItems(session, car, mutableListOf(MediaItem.Builder()
            .setMediaId("car:song:recent:${yt(1).libraryKey()}").build()), 0, 0)) is labs.rtensor.music.playback.CarRequestException)
    }

    @Test fun savedSongsCarryCarMetadataAndPaginate() {
        val saved = (1..25).map { yt(it) }
        store.writeTracks("saved", saved)
        val first = children("car:tab:saved", 0, 10).value!!
        assertEquals(10, first.size)
        val song = first[0]
        assertEquals("car:song:saved:${saved[0].libraryKey()}", song.mediaId)
        assertEquals("Song 1", song.mediaMetadata.title.toString())
        assertEquals("Artist 1", song.mediaMetadata.artist.toString())
        assertEquals(saved[0].artwork, song.mediaMetadata.artworkUri.toString())
        assertTrue(song.mediaMetadata.isPlayable!!)
        assertFalse(song.mediaMetadata.isBrowsable!!)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, song.mediaMetadata.mediaType)
        assertNull("Browse rows never carry a playable URI", song.localConfiguration)
        assertEquals(saved.subList(20, 25).map { "car:song:saved:${it.libraryKey()}" }, ids(children("car:tab:saved", 2, 10)))
        assertTrue(children("car:tab:saved", 3, 10).value!!.isEmpty())
        assertEquals(SessionError.ERROR_BAD_VALUE, children("car:tab:saved", -1, 10).resultCode)
        assertEquals(SessionError.ERROR_BAD_VALUE, children("car:tab:saved", 0, 0).resultCode)
        // A legacy browser asks for everything at once.
        assertEquals(25, children("car:tab:saved", 0, Int.MAX_VALUE).value!!.size)
        assertEquals(SessionError.ERROR_BAD_VALUE, children("car:tab:unknown").resultCode)
    }

    @Test fun emptyListsExplainWhatToDoOnThePhone() {
        val saved = children("car:tab:saved").value!!.single()
        assertEquals("No saved songs yet", saved.mediaMetadata.title.toString())
        assertEquals("Save songs in Music on your phone.", saved.mediaMetadata.subtitle.toString())
        assertFalse(saved.mediaMetadata.isPlayable!!); assertFalse(saved.mediaMetadata.isBrowsable!!)
        assertEquals("Nothing played yet", children("car:tab:recent").value!!.single().mediaMetadata.title.toString())
        // Without a cached or live feed, Home asks the driver to open Music on the phone.
        liveHome = { throw SourceException("NET", "offline") }
        val home = children("car:tab:home").value!!.single()
        assertEquals("No recommendations yet", home.mediaMetadata.title.toString())
        assertFalse(home.mediaMetadata.isPlayable!!)
        assertTrue(failure(callback.onSetMediaItems(session, car, mutableListOf(MediaItem.Builder().setMediaId(home.mediaId).build()), 0, 0)) != null)
        // Spotify's catalogue runs in its sign-in process; the car never starts it.
        store.recommendationEngine = RecommendationEngine.SPOTIFY
        val calls = liveHomeCalls.get()
        assertEquals("Open Music on your phone to load your Spotify recommendations.", children("car:tab:home").value!!.single().mediaMetadata.subtitle.toString())
        assertEquals(calls, liveHomeCalls.get())
    }

    @Test fun homeUsesThePhonesRecommendationsBeforeALiveFeed() {
        store.writeTracks(LibraryStore.HOME_RECOMMENDATIONS, listOf(yt(7), spotify))
        assertEquals(listOf(yt(7), spotify).map { "car:song:home:${it.libraryKey()}" }, ids(children("car:tab:home")))
        assertEquals(0, liveHomeCalls.get())
        store.writeTracks(LibraryStore.HOME_RECOMMENDATIONS, emptyList())
        liveHome = { listOf(yt(8), yt(9)) }
        assertEquals(listOf(yt(8), yt(9)).map { "car:song:home:${it.libraryKey()}" }, ids(children("car:tab:home")))
        // The live feed is kept, so tapping a song does not fetch it again.
        val calls = liveHomeCalls.get()
        val items = play("car:song:home:${yt(9).libraryKey()}")
        assertEquals(1, items.startIndex)
        assertEquals(calls, liveHomeCalls.get())
    }

    @Test fun phoneHomeStateBecomesTheCarsHomeList() {
        val feed = listOf(labs.rtensor.music.data.FeedSection("Quick picks", listOf(
            labs.rtensor.music.data.FeedEntry("e1", "Feed song", "", "", "song", "", "00000000042", 1000, "Feed artist"),
            labs.rtensor.music.data.FeedEntry("p1", "A playlist", "", "", "playlist", "VL1", ""))))
        val tracks = carHomeTracks(MusicState(forYou = listOf(yt(1), local), feed = feed))
        assertEquals(listOf("Song 1", "Feed song"), tracks.map { it.title })
        assertEquals("https://www.youtube.com/watch?v=00000000042", tracks[1].url)
    }

    @Test fun recentCombinesRecentlyPlayedAndHistoryWithoutRemovedImports() {
        store.writeTracks("saved", listOf(local))
        store.writeTracks("history", listOf(yt(1), local))
        ListeningHistory(context).add(PlayEvent(yt(2), 1_000, 60_000, false))
        ListeningHistory(context).add(PlayEvent(yt(1), 2_000, 60_000, false))
        store.removeLocal(local)
        assertEquals(listOf(yt(1), yt(2)).map { "car:song:recent:${it.libraryKey()}" }, ids(children("car:tab:recent")))
    }

    @Test fun choosingASongQueuesItsListThroughThePhonesPlayableItems() {
        val saved = listOf(yt(1), local, spotify, yt(4))
        store.writeTracks("saved", saved)
        val result = play("car:song:saved:${spotify.libraryKey()}")
        assertEquals(2, result.startIndex)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
        assertEquals(saved, result.mediaItems.map(::catalogueTrack))
        result.mediaItems.zip(saved).forEach { (item, track) ->
            val expected = PlayableItems.build(track, PlaybackRoute(null))
            assertEquals(expected.mediaId, item.mediaId)
            assertEquals(expected.localConfiguration!!.uri, item.localConfiguration!!.uri)
            assertEquals(expected.mediaMetadata.title, item.mediaMetadata.title)
            assertEquals(expected.mediaMetadata.artworkUri, item.mediaMetadata.artworkUri)
            assertTrue("Car items pass the same check as the phone's own items", PlayableItems.permitted(item))
        }
        // A long Saved list keeps the chosen song inside the 100-song queue.
        val many = (1..150).map { yt(it) }
        store.writeTracks("saved", many)
        val late = play("car:song:saved:${many[120].libraryKey()}")
        assertEquals(many[120], catalogueTrack(late.mediaItems[late.startIndex]))
        assertTrue(late.mediaItems.size <= 100)
        // Add to queue resolves just that song.
        val added = await(callback.onAddMediaItems(session, car, mutableListOf(MediaItem.Builder().setMediaId("car:song:saved:${many[3].libraryKey()}").build())))
        assertEquals(listOf(many[3]), added.map(::catalogueTrack))
    }

    @Test fun aSongRemovedSinceBrowsingDoesNotPlayOrClearTheQueue() {
        store.writeTracks("saved", listOf(yt(1)))
        val id = ids(children("car:tab:saved")).single()
        store.writeTracks("saved", emptyList())
        assertTrue(failure(callback.onSetMediaItems(session, car, mutableListOf(MediaItem.Builder().setMediaId(id).build()), 0, 0))
            is labs.rtensor.music.playback.CarRequestException)
    }

    @Test fun onlyMusicAndTheCarMayBrowseOrPlayByBrowseId() {
        store.writeTracks("saved", listOf(yt(1)))
        assertTrue(callback.onConnect(session, car).isAccepted)
        assertFalse(callback.onConnect(session, otherApp).isAccepted)
        assertTrue(callback.onConnect(session, trustedOtherApp).isAccepted)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, root(browser = trustedOtherApp).resultCode)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, children("car:tab:saved", browser = trustedOtherApp).resultCode)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, await(callback.onSearch(session, trustedOtherApp, "x", null)).resultCode)
        val byId = mutableListOf(MediaItem.Builder().setMediaId("car:song:saved:${yt(1).libraryKey()}").build())
        assertTrue(await(callback.onAddMediaItems(session, trustedOtherApp, byId)).isEmpty())
        // The car never supplies its own URI to play.
        val uri = mutableListOf(MediaItem.Builder().setMediaId("x").setUri("https://www.youtube.com/watch?v=00000000001").build())
        assertTrue(await(callback.onAddMediaItems(session, car, uri)).isEmpty())
        // The phone's own controller keeps its rules.
        val own = MediaSession.ControllerInfo.createTestOnlyControllerInfo(context.packageName, 4, android.os.Process.myUid(), 1_000_000, 1, false, Bundle())
        val phone = PlayableItems.build(yt(1), PlaybackRoute(null))
        assertEquals(listOf(phone), await(callback.onAddMediaItems(session, own, mutableListOf(phone, MediaItem.Builder().setUri("https://example.com/a.mp3").build()))))
    }

    @Test fun searchListsSavedMatchesThenTheCatalogueAndPlaysAResult() {
        store.writeTracks("saved", listOf(yt(1, "Blue Monday", "New Order"), yt(2, "Other", "Someone")))
        searchResults = { listOf(yt(1, "Blue Monday", "New Order"), yt(3, "Blue Monday (Live)", "New Order")) }
        assertEquals(LibraryResult.RESULT_SUCCESS, await(callback.onSearch(session, car, "blue monday", null)).resultCode)
        val results = await(callback.onGetSearchResult(session, car, "blue monday", 0, 10, null)).value!!
        assertEquals(listOf(1, 3).map { "car:song:search:${yt(it).libraryKey()}" }, results.map { it.mediaId })
        assertEquals("In your library", results[0].mediaMetadata.extras!!.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
        assertEquals("Results", results[1].mediaMetadata.extras!!.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
        assertEquals(listOf("blue monday"), synchronized(searched) { searched.toList() })
        assertEquals(1, await(callback.onGetSearchResult(session, car, "blue monday", 1, 1, null)).value!!.size)
        val played = play(results[1].mediaId)
        assertEquals(yt(3, "Blue Monday (Live)", "New Order"), catalogueTrack(played.mediaItems[played.startIndex]))
    }

    @Test fun aFailedSearchGivesTheDriverAMessage() {
        searchResults = { throw SourceException("NET", "YouTube took too long to respond. Check your connection and retry.") }
        val failed = await(callback.onGetSearchResult(session, car, "anything", 0, 10, null))
        assertEquals(SessionError.ERROR_IO, failed.resultCode)
        assertEquals("YouTube took too long to respond. Check your connection and retry.", failed.sessionError!!.message)
        // Saved matches still answer while the catalogue is unreachable.
        store.writeTracks("saved", listOf(yt(5, "Anything Goes", "Band")))
        assertEquals(1, await(callback.onGetSearchResult(session, car, "anything", 0, 10, null)).value!!.size)
    }

    @Test fun voiceRequestsSearchOrPlaySaved() {
        searchResults = { listOf(yt(11), yt(12)) }
        val found = voice("play something")
        assertEquals(0, found.startIndex)
        assertEquals(listOf(yt(11), yt(12)), found.mediaItems.map(::catalogueTrack))
        assertTrue(failure(callback.onSetMediaItems(session, car, mutableListOf(MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build()).build()), C.INDEX_UNSET, C.TIME_UNSET)) != null)
        store.writeTracks("saved", listOf(yt(21), yt(22)))
        assertEquals(listOf(yt(21), yt(22)), voice("").mediaItems.map(::catalogueTrack))
    }

    @Test fun aMediaBrowserCanBrowseSearchAndStartPlayback() {
        store.writeTracks("saved", listOf(yt(1), yt(2)))
        searchResults = { listOf(yt(3)) }
        val browser = await(MediaBrowser.Builder(context, session.token).buildAsync())
        try {
            val root = await(browser.getLibraryRoot(rootParams(limit = 4)))
            assertEquals(CarLibrary.ROOT, root.value!!.mediaId)
            assertEquals(listOf("car:tab:home", "car:tab:saved", "car:tab:recent"), ids(await(browser.getChildren(CarLibrary.ROOT, 0, 10, null))))
            val saved = await(browser.getChildren("car:tab:saved", 0, 10, null)).value!!
            assertEquals(2, saved.size)
            await(browser.search("song 3", null))
            val results = await(browser.getSearchResult("song 3", 0, 10, null)).value!!
            assertEquals("car:song:search:${yt(3).libraryKey()}", results.single().mediaId)
            browser.setMediaItem(MediaItem.Builder().setMediaId(saved[1].mediaId).build())
            val deadline = System.currentTimeMillis() + 10_000
            while (player.mediaItemCount == 0 && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(listOf(yt(1), yt(2)), (0 until player.mediaItemCount).map { catalogueTrack(player.getMediaItemAt(it)) })
            assertEquals(1, player.currentMediaItemIndex)
        } finally { browser.release() }
    }
}
