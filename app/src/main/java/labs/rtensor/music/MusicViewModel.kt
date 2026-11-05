package labs.rtensor.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import labs.rtensor.dsp.ListeningMode
import labs.rtensor.music.data.AccentChoice
import labs.rtensor.music.data.LibraryStore
import labs.rtensor.music.data.LocalAudioImporter
import labs.rtensor.music.data.LocalMasters
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.ListeningHistory
import labs.rtensor.music.data.PlayEvent
import labs.rtensor.music.data.Recommender
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.SourceException
import labs.rtensor.music.data.YouTubeMetadata
import labs.rtensor.music.data.needsArtist
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.YouTubeSource
import labs.rtensor.music.auth.GoogleProfile
import labs.rtensor.music.auth.GoogleProfileStore
import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.HomeFeed
import labs.rtensor.music.updates.GitHubUpdater
import labs.rtensor.music.updates.UpdateState
import labs.rtensor.music.updates.checked
import labs.rtensor.music.updates.updateFailureMessage
import labs.rtensor.music.playback.MusicPlaybackService
import labs.rtensor.music.playback.AudioPipeline
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.playback.CatalogueTrackCache
import labs.rtensor.music.playback.LibraryQueueEdits
import labs.rtensor.music.playback.PlayerTransport
import labs.rtensor.music.playback.RadioQueue
import labs.rtensor.music.sources.*
import labs.rtensor.music.recommendations.RecommendationEngine
import labs.rtensor.music.spotify.SpotifyRecommendations
import labs.rtensor.music.spotify.SpotifyTrackMatcher

object ErrorOrigin { const val PLAYBACK = "playback"; const val SEARCH = "search"; const val COLLECTION = "collection" }

data class MusicState(
    val importMessage: String? = null,
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
    val accent: AccentChoice = AccentChoice.DEFAULT,
    val reducedMotion: Boolean = false,
    val developerOptions: Boolean = false,
    val recommendationEngine: RecommendationEngine = RecommendationEngine.YOUTUBE,
    val spotifyAccount: AccountState = AccountState(),
    val error: String? = null,
    val errorDiagnostic: String = "",
    /** What produced [error], so Retry repeats that: [ErrorOrigin.SEARCH], [ErrorOrigin.COLLECTION] or [ErrorOrigin.PLAYBACK]. */
    val errorOrigin: String = "",
    val account: AccountState = AccountState(),
    val googleProfile: GoogleProfile? = null,
    val googleBusy: Boolean = false,
    val googleError: String? = null,
    val feed: List<FeedSection> = emptyList(),
    val feedBusy: Boolean = false,
    val feedHasMore: Boolean = false,
    /** Songs "More from your feed" added below Home's recommendations, in the order they arrived. */
    val feedMore: List<Track> = emptyList(),
    /** The last "More from your feed" found nothing new, so the button is gone. */
    val feedEnded: Boolean = false,
    val feedError: String? = null,
    /** False when an account is connected but YouTube answered the feed as signed out. */
    val feedPersonalized: Boolean = true,
    val playEvents: List<PlayEvent> = emptyList(),
    val forYou: List<Track> = emptyList(),
    val forYouBusy: Boolean = false,
    val autoplay: Boolean = true,
    val radioLoading: Boolean = false,
    val radioError: String? = null,
    val trackHistory: Boolean = true,
    val explore: List<FeedSection> = emptyList(),
    val exploreBusy: Boolean = false,
    val exploreError: String? = null,
    val collection: List<Track> = emptyList(),
    val collectionEntry: FeedEntry? = null,
    val collectionBusy: Boolean = false,
    val collectionHasMore: Boolean = false,
    val update: UpdateState = UpdateState(),
    val audioProcessing: AudioProcessingState = AudioProcessingState(),
    /** How the current song is played: the user's own Atmos or lossless file, or YouTube. */
    val routeLabel: String = "",
    val mastersSummary: String = "",
    val mastersBusy: Boolean = false,
    val playbackSources: List<SourceSummary> = emptyList(),
    val sourceBusy: Boolean = false,
    val sourceMessage: String? = null,
    val tidalAccount: labs.rtensor.music.data.AccountState = labs.rtensor.music.data.AccountState(),
    val appleAccount: labs.rtensor.music.apple.AppleAccountState = labs.rtensor.music.apple.AppleAccountState(),
)

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    private val listening = ListeningHistory(application)
    private val initialEvents = listening.events.also { library.reconcileImports(it.map { event -> event.track }) }
    private val googleProfiles = GoogleProfileStore(application)
    private val youtube = YouTubeSource(application)
    private val updater = GitHubUpdater(application)
    private val masters = LocalMasters(application)
    private val sources = PlaybackSources.get(application)
    private val spotify = SpotifyRecommendations(application)
    private val spotifyMatches = SpotifyTrackMatcher.get(application)
    private var recommendationRevision = 0L
    private var collectionOffset: Int? = null
    /** The collection page most recently requested, so Retry repeats that page. */
    private var collectionRequestOffset = 0
    private val routes = mutableMapOf<String, PlaybackRoute>()
    private fun route(track: Track) = routes.getOrPut(track.id) { PlaybackRoute.choose(track, masters.entries.filterNot { it.uri in library.removedLocalUris }) }
    private val mutable = MutableStateFlow(MusicState(
        saved = library.tracks("saved"), history = library.tracks("history"), googleProfile = googleProfiles.load(),
        theme = library.theme, accent = library.accent, reducedMotion = library.reducedMotion, playEvents = initialEvents.filterNot { library.isRemoved(it.track) },
        developerOptions = library.developerOptions,
        recommendationEngine = library.recommendationEngine,
        spotifyAccount = spotify.state.value,
        autoplay = library.autoplay, trackHistory = library.trackHistory,
        mastersSummary = if (masters.accessLost) MASTERS_ACCESS_LOST else summary(masters.entries, masters.folder != null),
        playbackSources = sources.settings.connections.value.map { it.summary() },
    ))
    val state = mutable.asStateFlow()
    private val mutablePosition = MutableStateFlow(0L)
    val playbackPosition = mutablePosition.asStateFlow()
    val audioLevels = AudioPipeline.levels
    fun setMetering(enabled: Boolean) { AudioPipeline.setMetering(enabled && mutable.value.developerOptions) }
    fun developerOptions(enabled: Boolean) {
        library.developerOptions = enabled
        mutable.update { it.copy(developerOptions = enabled) }
        if (!enabled) AudioPipeline.setMetering(false)
    }
    private val catalogueCache = CatalogueTrackCache()
    private val metadata = mutableMapOf<String, YouTubeMetadata>()
    private fun enrich(track: Track) = metadata[track.id]?.enrich(track) ?: track
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var feedJob: Job? = null
    private var collectionJob: Job? = null
    private var exploreJob: Job? = null
    private var radio: RadioQueue? = null
    private var forYouJob: Job? = null
    private var mastersJob: Job? = null
    private var historyRevision = 0L
    private var suppressedHistoryKey: String? = null
    private var observedHistoryKey: String? = null
    private var forYouRotation = 0
    /** The listen in progress: which song, when it started, and how long it has actually played. */
    private var listen: Triple<Track, Long, Long>? = null
    private var lastTick = 0L
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    /** A song chosen before the player connected; it starts as soon as the controller is ready. */
    private var pendingPlay: Pair<Track, List<Track>>? = null
    private var reconnects = 0
    private var cleared = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer(includeQueue = true) }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayer()
            if (isPlaying) mutable.value.current?.let(::recordHistory)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayer()
            if (playbackState == Player.STATE_READY) mutable.update { it.copy(error = null, errorDiagnostic = "") }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayer(includeQueue = true)
            radio?.request()
            mutable.update { it.copy(error = null, errorDiagnostic = "") }
        }
        override fun onPlayerError(error: PlaybackException) {
            syncPlayer(includeQueue = true)
            val sourceError = generateSequence(error as Throwable) { it.cause }.filterIsInstance<SourceException>().firstOrNull() ?: AudioPipeline.catalogueProblem
            val provider = youtube.runtime.problem.value?.takeIf { it.videoId == mutable.value.current?.id?.removePrefix("yt:") }
            // The service classified the full cause chain before this callback arrived.
            val audio = AudioPipeline.state.value.failure
            mutable.update { it.copy(error = sourceError?.message ?: provider?.message ?: audio?.message ?: playbackErrorMessage(error),
                errorDiagnostic = sourceError?.diagnostic ?: provider?.let { p -> "${p.code}: ${p.diagnostic}" } ?: audio?.detail ?: error.errorCodeName,
                resolving = false, buffering = false, errorOrigin = ErrorOrigin.PLAYBACK) }
        }
    }

    init {
        AudioPipeline.selectProcessing(library.processedAudio)
        viewModelScope.launch {
            AudioPipeline.state.collect { audio -> mutable.update { it.copy(audioProcessing = audio, routeLabel = currentRoute(audio)) } }
        }
        viewModelScope.launch { sources.state.collect { mutable.update { it.copy(routeLabel = currentRoute(it.audioProcessing)) } } }
        viewModelScope.launch { sources.settings.connections.collect { profiles -> mutable.update { it.copy(playbackSources = profiles.map { p -> p.summary() }) } } }
        viewModelScope.launch { sources.tidal?.state?.collect { account -> mutable.update { it.copy(tidalAccount = account) } } }
        viewModelScope.launch { sources.apple?.state?.collect { account -> mutable.update { it.copy(appleAccount = account) } } }
        viewModelScope.launch {
            spotify.state.collect { account ->
                val changed = account.connected != mutable.value.spotifyAccount.connected || account.revision != mutable.value.spotifyAccount.revision
                mutable.update { it.copy(spotifyAccount = account) }
                if (changed && mutable.value.recommendationEngine == RecommendationEngine.SPOTIFY) reloadRecommendations()
            }
        }
        connectController()
        viewModelScope.launch {
            // Keep the car's Home tab in step with the recommendations this phone loaded.
            state.map(::carHomeTracks).distinctUntilChanged().collect { tracks ->
                if (tracks.isNotEmpty()) withContext(Dispatchers.IO) { library.writeTracks(LibraryStore.HOME_RECOMMENDATIONS, tracks) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                controller?.let { c ->
                    trackListening(c)
                    // Only the small timeline subtrees observe this clock.
                    mutablePosition.value = c.currentPosition.coerceAtLeast(0)
                }
                delay(200)
            }
        }
        viewModelScope.launch {
            youtube.runtime.account.collect { account ->
                val changed = account.connected != mutable.value.account.connected || account.revision != mutable.value.account.revision
                val refresh = changed && mutable.value.recommendationEngine == RecommendationEngine.YOUTUBE
                mutable.update { it.copy(account = account, feed = if (refresh) emptyList() else it.feed, feedMore = if (refresh) emptyList() else it.feedMore,
                    feedEnded = it.feedEnded && !refresh, explore = if (refresh) emptyList() else it.explore) }
                if (refresh) { clearCarHome(); refreshFeed(); refreshExplore() }
            }
        }
        viewModelScope.launch {
            youtube.runtime.metadata.collect { item ->
                if (item != null) {
                    metadata["yt:${item.videoId}"] = item
                    val before = mutable.value
                    mutable.update { it.copy(current = it.current?.let(::enrich), history = it.history.map(::enrich), saved = it.saved.map(::enrich), queue = it.queue.map(::enrich), results = it.results.map(::enrich), collection = it.collection.map(::enrich)) }
                    controller?.let { c ->
                        for (index in 0 until c.mediaItemCount) {
                            val old = c.getMediaItemAt(index)
                            val updated = mutable.value.queue.getOrNull(index)?.takeIf { it.id == "yt:${item.videoId}" } ?: continue
                            if (old.mediaMetadata.artist?.toString() == updated.artist) continue
                            val extras = Bundle(old.mediaMetadata.extras ?: Bundle()).apply { putString("catalogue_track", updated.json().toString()) }
                            c.replaceMediaItem(index, old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setArtist(updated.artist).setExtras(extras).build()).build())
                        }
                    }
                    if (before.history != mutable.value.history) library.writeTracks("history", mutable.value.history)
                    if (before.saved != mutable.value.saved) library.writeTracks("saved", mutable.value.saved)
                }
            }
        }
        viewModelScope.launch {
            mutable.value.history.firstOrNull()?.takeIf { it.needsArtist() }?.let {
                try { youtube.metadata(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retry with playback metadata. */ }
            }
        }
        refreshFeed()
        refreshForYou()
        if (listening.events != mutable.value.playEvents) persistHistory()
        checkUpdates()
    }

    /** Connect (or reconnect) to the playback service. A command must never be lost silently:
     * a song chosen meanwhile is queued, and a lost connection is rebuilt. */
    private fun connectController() {
        if (cleared || controllerFuture != null) return
        val app = getApplication<Application>()
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) { controllerLost(controller) }
            }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            runCatching { future.get() }.onSuccess { c ->
                if (cleared) { c.release(); return@onSuccess }
                attachController(c)
            }.onFailure {
                controllerFuture = null; pendingPlay = null
                mutable.update { it.copy(playerReady = false, loadingTrack = null, error = "The audio player could not start. Close and reopen the app.", errorOrigin = ErrorOrigin.PLAYBACK) }
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun attachController(c: MediaController) {
        controller = c
        radio = RadioQueue(c, viewModelScope, enabled = { mutable.value.autoplay }, fetch = ::providerRadio,
            mediaItem = ::mediaItem, status = { loading, error -> mutable.update { it.copy(radioLoading = loading, radioError = error) } },
            providerLabel = { mutable.value.recommendationEngine.label })
        c.addListener(listener)
        library.reconcileImports((0 until c.mediaItemCount).mapNotNull { index ->
            catalogueCache.read(c.getMediaItemAt(index).mediaMetadata.extras?.getString("catalogue_track"))
        })
        // A song kept by a still-running service may point at a lossless file that is no longer
        // indexed or readable; rebuild it on the normal source chain instead of failing.
        val knownMasters = masters.entries.map { it.uri }.toSet()
        val staleMasters = (0 until c.mediaItemCount).mapNotNull { index ->
            val item = c.getMediaItemAt(index)
            item.localConfiguration?.uri?.toString()?.takeIf { item.mediaMetadata.extras?.containsKey("local_master") == true && it !in knownMasters }
        }.toSet()
        LibraryQueueEdits.reconcile(c, library::isRemoved, library.removedLocalUris + staleMasters, ::mediaItem)
        syncPlayer(includeQueue = true)
        mutable.update { it.copy(playerReady = true, error = it.error?.takeUnless { e -> e == PLAYER_STARTING }) }
        pendingPlay?.let { (track, queue) -> pendingPlay = null; play(track, queue) }
    }

    private fun controllerLost(c: MediaController) {
        if (controller !== c) return
        c.removeListener(listener)
        radio?.reset(); radio = null
        controller = null; controllerFuture = null
        mutable.update { it.copy(playerReady = false, playing = false, buffering = false) }
        // The service stopped or was killed; bind again so the controls keep working.
        if (!cleared && reconnects++ < 3) connectController()
    }

    /** Run a transport command, or say why it cannot run yet. */
    private inline fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c == null) {
            mutable.update { it.copy(error = PLAYER_STARTING, errorOrigin = ErrorOrigin.PLAYBACK) }
            if (controllerFuture == null) { reconnects = 0; connectController() }
            return
        }
        action(c)
    }

    fun refreshFeed(more: Boolean = false) {
        if (more && (mutable.value.feedBusy || !mutable.value.feedHasMore)) return
        feedJob?.cancel()
        val revision = recommendationRevision
        val engine = mutable.value.recommendationEngine
        feedJob = viewModelScope.launch {
            mutable.update { it.copy(feedBusy = true, feedError = null) }
            try {
                val fetch: suspend (Boolean) -> HomeFeed = { next -> if (engine == RecommendationEngine.SPOTIFY) spotify.home() else youtube.home(next) }
                if (more) {
                    val added = loadMoreHomeFeed(mutable.value) { fetch(true) }
                    if (revision == recommendationRevision) mutable.update { it.withMoreHomeFeed(added).copy(feedBusy = false) }
                    return@launch
                }
                val feed = fetch(false)
                if (revision != recommendationRevision) return@launch
                mutable.update { it.withHomeFeed(feed, engine == RecommendationEngine.SPOTIFY || !it.account.connected || feed.personalized).copy(feedBusy = false) }
                if (mutable.value.forYou.isEmpty()) refreshForYou()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == recommendationRevision) mutable.update { it.copy(feedBusy = false, feedError = catalogueErrorMessage(e, CatalogueMessages.FEED)) } }
        }
    }

    fun refreshExplore() {
        exploreJob?.cancel()
        val revision = recommendationRevision
        val engine = mutable.value.recommendationEngine
        exploreJob = viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreError = null) }
            try {
                if (engine == RecommendationEngine.SPOTIFY) {
                    val feed = spotify.home()
                    if (revision == recommendationRevision) mutable.update { it.copy(explore = feed.sections, exploreBusy = false) }
                    return@launch
                }
                val events = mutable.value.playEvents
                val seeds = Recommender.seeds(events, mutable.value.saved, count = 2)
                // Personal rows come from YouTube Music's "Related" page for your favourites;
                // they are optional, so Explore still loads when they fail.
                val personal = seeds.mapIndexed { index, seed ->
                    async { runCatching { personalRows(seed, youtube.related(seed), events, first = index == 0) }.getOrElse { e -> if (e is CancellationException) throw e; emptyList() } }
                }
                val feed = youtube.explore()
                val rows = personal.awaitAll().flatten()
                // Nothing appears twice: personal rows win, then YouTube's own order.
                val seen = HashSet<String>()
                val sections = (rows + feed.sections).mapNotNull { section ->
                    section.copy(items = section.items.filter { seen.add(it.id) }).takeIf { it.items.isNotEmpty() }
                }
                if (revision == recommendationRevision) mutable.update { it.copy(explore = sections, exploreBusy = false) }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == recommendationRevision) mutable.update { it.copy(exploreBusy = false, exploreError = catalogueErrorMessage(e, CatalogueMessages.FEED)) } }
        }
    }

    /** Retitle a song's "Related" page as Explore rows; only the first favourite adds artists and playlists. */
    private fun personalRows(seed: Track, related: List<FeedSection>, events: List<PlayEvent>, first: Boolean): List<FeedSection> =
        related.mapNotNull { section ->
            val songs = section.items.any { it.videoId.isNotBlank() }
            val artists = section.items.all { it.kind == "artist" }
            val artist = ListeningHistory.artists(seed.artist).firstOrNull()
            when {
                songs -> FeedSection("Because you like ${seed.title}", Recommender.freshSongs(section.items, events).take(20))
                !first -> null
                artists -> FeedSection(artist?.let { "Artists like $it" } ?: "Artists for you", section.items)
                else -> FeedSection(section.title, section.items)
            }
        }.filter { it.items.isNotEmpty() }

    fun browse(entry: FeedEntry) = browsePage(entry, 0)
    fun moreCollection() { collectionOffset?.let { offset -> mutable.value.collectionEntry?.let { browsePage(it, offset) } } }
    private fun browsePage(entry: FeedEntry, offset: Int) {
        collectionJob?.cancel()
        // A new entry never continues the previous entry's paging.
        if (offset == 0) collectionOffset = null
        collectionRequestOffset = offset
        collectionJob = viewModelScope.launch {
            mutable.update { it.copy(collectionEntry = entry, collection = if (offset == 0) emptyList() else it.collection,
                collectionBusy = true, collectionHasMore = false, error = null) }
            try {
                val spotifyEntry = entry.kind.startsWith("spotify_")
                val page = if (spotifyEntry) spotify.collection(entry, offset) else null
                val tracks = if (page != null) spotify.tracks(page) else youtube.collection(entry)
                collectionOffset = page?.optInt("nextOffset", -1)?.takeIf { it > offset }
                mutable.update { it.collectionPageLoaded(offset, tracks, collectionOffset != null) }
            }
            catch (e: CancellationException) {
                // A provider can abandon a stale request with CancellationException (Spotify does
                // after an account change). Only a cancelled job may end without leaving a state.
                if (!isActive) throw e
                mutable.update { it.collectionPageFailed(offset, CatalogueMessages.COLLECTION) }
            }
            catch (e: Exception) { mutable.update { it.collectionPageFailed(offset, catalogueErrorMessage(e, CatalogueMessages.COLLECTION)) } }
        }
    }
    /** Retry the page that failed: a failed "Load more" keeps the songs already shown. */
    fun retryCollection() { mutable.value.collectionEntry?.let { browsePage(it, collectionRequestOffset) } }

    fun recommendationEngine(engine: RecommendationEngine) {
        if (engine == RecommendationEngine.SPOTIFY && !spotify.state.value.connected) return
        if (engine == mutable.value.recommendationEngine) return
        library.recommendationEngine = engine
        mutable.update { it.copy(recommendationEngine = engine) }
        reloadRecommendations()
    }
    /** Another account or provider: the car must not keep showing the old recommendations. */
    private fun clearCarHome() { library.writeTracks(LibraryStore.HOME_RECOMMENDATIONS, emptyList()) }
    private fun reloadRecommendations() {
        recommendationRevision++
        clearCarHome()
        feedJob?.cancel(); exploreJob?.cancel(); forYouJob?.cancel()
        mutable.update { it.copy(feed = emptyList(), feedMore = emptyList(), feedEnded = false, explore = emptyList(), forYou = emptyList(), feedHasMore = false,
            feedError = null, exploreError = null, feedBusy = false, exploreBusy = false, forYouBusy = false) }
        radio?.changeProvider()
        refreshFeed(); refreshExplore(); refreshForYou()
    }
    private suspend fun providerRadio(seed: Track, exclude: Collection<String> = emptyList()): List<Track> =
        if (mutable.value.recommendationEngine == RecommendationEngine.SPOTIFY) spotify.radio(seed, exclude)
        else youtube.radio(spotifyMatches.resolve(seed), exclude)
    fun beginSpotifySignIn() { spotify.connecting() }
    /** Every way out of Spotify sign-in leaves a result in Settings: connected, or a reason. */
    fun completeSpotifySignIn(success: Boolean, problem: String? = null) {
        if (!success) { spotify.cancel(problem); return }
        viewModelScope.launch {
            if (spotify.verify()) {
                // The account collector may still be queued when verify returns on the main thread.
                mutable.update { it.copy(spotifyAccount = spotify.state.value) }
                recommendationEngine(RecommendationEngine.SPOTIFY)
            }
        }
    }
    fun disconnectSpotify() {
        recommendationEngine(RecommendationEngine.YOUTUBE)
        collectionJob?.cancel()
        if (mutable.value.collectionEntry?.kind?.startsWith("spotify_") == true) mutable.update { it.copy(collection = emptyList(), collectionBusy = false, collectionHasMore = false) }
        viewModelScope.launch { try { spotify.disconnect() } catch (_: Exception) { /* Other providers remain available. */ } }
    }
    fun googleSelecting() { mutable.update { it.copy(googleBusy = true, googleError = null) } }
    fun googleSelected(profile: GoogleProfile): Boolean {
        val changed = profile.subject != mutable.value.googleProfile?.subject
        googleProfiles.save(profile)
        if (changed) disconnectAccount()
        mutable.update { it.copy(googleProfile = profile, googleBusy = false, googleError = null) }
        return changed
    }
    fun googleSelectionEnded(error: String? = null) { mutable.update { it.copy(googleBusy = false, googleError = error) } }
    fun forgetGoogleProfile() {
        googleProfiles.clear(); disconnectAccount()
        mutable.update { it.copy(googleProfile = null, googleBusy = false, googleError = null) }
    }
    fun connectAccount() { youtube.runtime.beginSignIn() }
    fun disconnectAccount() {
        // Choosing or forgetting a Google profile also signs YouTube out. Without a YouTube
        // account there is nothing to remove, so the current song and queue keep playing.
        if (!youtubeSignOutNeeded(mutable.value.account, youtube.runtime.hasSession())) return
        radio?.reset()
        controller?.stop(); controller?.clearMediaItems()
        mutablePosition.value = 0
        mutable.update { it.copy(current = null, queue = emptyList(), queueIndex = -1, positionMs = 0, durationMs = 0, error = null, errorDiagnostic = "") }
        viewModelScope.launch { youtube.runtime.disconnect() }
    }
    fun cancelAccountConnection() { youtube.runtime.cancelSignIn() }

    fun checkUpdates() {
        if (mutable.value.update.checking || mutable.value.update.downloading) return
        viewModelScope.launch {
            mutable.update { it.copy(update = it.update.copy(checking = true, message = "Checking GitHub releases…")) }
            try {
                val release = updater.check()
                mutable.update { it.copy(update = it.update.checked(release)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(update = it.update.copy(checking = false, message = updateFailureMessage(e, "Could not check for updates. Try again later."))) } }
        }
    }
    fun downloadUpdate() {
        val update = mutable.value.update
        val release = update.release ?: return
        if (update.downloading || update.checking) return
        viewModelScope.launch {
            mutable.update { it.copy(update = it.update.copy(downloading = true, progress = 0, message = "Downloading update…", apkPath = null)) }
            try {
                val file = updater.download(release) { progress -> mutable.update { it.copy(update = it.update.copy(progress = progress)) } }
                mutable.update { it.copy(update = it.update.copy(downloading = false, apkPath = file.path, message = "Verified update. Ready to install.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(update = it.update.copy(downloading = false, message = updateFailureMessage(e, "The update download failed. Retry the download."))) } }
        }
    }
    fun updateMessage(message: String) { mutable.update { it.copy(update = it.update.copy(message = message)) } }

    private fun syncPlayer(includeQueue: Boolean = false) {
        val c = controller ?: return
        trackListening(c)
        mutablePosition.value = c.currentPosition.coerceAtLeast(0)
        val restored = catalogueCache.read(c.currentMediaItem?.mediaMetadata?.extras?.getString("catalogue_track"))
        val queue = if (!includeQueue) mutable.value.queue else (0 until c.mediaItemCount).mapNotNull { index ->
            runCatching { c.getMediaItemAt(index).mediaMetadata.extras?.getString("catalogue_track")?.let { Track.fromJson(org.json.JSONObject(it)) } }.getOrNull()
        }
        mutable.update { it.copy(
            current = restored?.takeUnless(library::isRemoved)?.let(::enrich),
            queue = if (includeQueue) queue.map(::enrich) else queue,
            queueIndex = c.currentMediaItemIndex,
            routeLabel = currentRoute(it.audioProcessing),
            playing = c.isPlaying,
            // A paused song that is still loading is paused, not "Loading".
            buffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            positionMs = mutablePosition.value,
            durationMs = if (restored == null || library.isRemoved(restored)) 0 else c.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0,
        ) }
    }

    private fun currentRoute(audio: AudioProcessingState): String {
        val item = controller?.currentMediaItem ?: return ""
        val key = item.localConfiguration?.customCacheKey ?: item.mediaId
        val local = runCatching { item.mediaMetadata.extras?.getString("local_master")?.let { org.json.JSONObject(it) } }.getOrNull()
        if (!sources.isRemote(key) && local != null) {
            val report = audio.report ?: return "Local file · checking decoded channels"
            if (report.inputPositions.size <= 2) return "Local file · ${report.sampleRate / 1000f} kHz · stereo unchanged"
            return when (report.listeningMode) {
                ListeningMode.ORIGINAL -> "Local file · original front L/R"
                ListeningMode.PROCESSED -> "Local file · ${report.inputPositions.size} ch → DMR"
            }
        }
        return sources.label(key, audio.report) ?: item.mediaMetadata.extras?.getString("route").orEmpty()
    }

    fun connectSource(provider: MusicProvider, protocol: SourceProtocol, url: String, token: String) {
        if (mutable.value.sourceBusy) return
        viewModelScope.launch {
            mutable.update { it.copy(sourceBusy = true, sourceMessage = "Checking server…") }
            try {
                val connection = SourceConnection(java.util.UUID.randomUUID().toString(), provider.label, provider, protocol,
                    SourceConnection.validateUrl(url), token.trim())
                val name = withContext(Dispatchers.IO) { SourceServerClient(connection).probe() }.take(80)
                withContext(Dispatchers.IO) { sources.settings.save(connection.copy(name = name.ifBlank { provider.label })) }
                mutable.update { it.copy(sourceBusy = false, sourceMessage = "Server saved. Available recordings will be checked when you play a song.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(sourceBusy = false, sourceMessage = if (e is IllegalArgumentException) e.message else "Could not connect. Check the server address and access token.") } }
        }
    }

    fun beginTidalSignIn(): String? = runCatching { sources.tidal?.begin() }.getOrElse {
        mutable.update { it.copy(sourceMessage = "TIDAL sign-in could not start. Try again.") }; null
    }
    fun finishTidalSignIn(callback: String) { viewModelScope.launch { sources.tidal?.complete(callback) } }
    fun disconnectTidal() {
        // As for Apple Music: a song streaming from TIDAL must not keep using the revoked
        // account's stream; re-prepare it so it falls back to the next available source.
        val item = controller?.currentMediaItem
        val key = item?.localConfiguration?.customCacheKey ?: item?.mediaId
        val playingTidal = sources.state.value[key]?.stream?.provider == MusicProvider.TIDAL
        if (playingTidal) controller?.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sources.tidal?.disconnect() }
            if (playingTidal) controller?.prepare()
        }
    }
    fun cancelTidalSignIn() { viewModelScope.launch(Dispatchers.IO) { sources.tidal?.cancel() } }
    fun beginAppleSignIn(): Long = sources.apple?.beginWeb() ?: -1
    fun finishAppleSignIn(ticket: Long, value: String?) { if (value == null) sources.apple?.cancelWeb(ticket) else sources.apple?.completeWeb(ticket, value) }
    fun disconnectApple() {
        val item = controller?.currentMediaItem
        val key = item?.localConfiguration?.customCacheKey ?: item?.mediaId
        val playingApple = sources.state.value[key]?.stream?.provider == MusicProvider.APPLE_MUSIC
        if (playingApple) controller?.stop()
        sources.apple?.disconnect()
        if (playingApple) controller?.prepare()
    }
    fun cancelAppleSignIn() { sources.apple?.cancel() }

    fun removeSource(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sources.settings.remove(id) }
            mutable.update { it.copy(sourceMessage = null) }
        }
    }

    fun setQuery(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            // Clearing the search removes only a search failure; a playback or collection error and its Retry stay.
            mutable.update { it.clearedSearch() }
        } else mutable.update { it.copy(query = query.take(400)) }
    }

    fun search() {
        val query = mutable.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true, searched = true, error = null, errorDiagnostic = "", results = emptyList()) }
            try {
                val results = youtube.search(query)
                mutable.update { it.copy(results = results, searching = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(searching = false, error = catalogueErrorMessage(e, CatalogueMessages.SEARCH), errorDiagnostic = (e as? SourceException)?.diagnostic.orEmpty(), errorOrigin = ErrorOrigin.SEARCH) }
            }
        }
    }

    fun play(track: Track, queue: List<Track> = listOf(track)) = start(track, queue, 0)

    private fun start(track: Track, queue: List<Track>, startPositionMs: Long) {
        if (library.isRemoved(track)) return
        val c = controller ?: run {
            // Keep the request instead of dropping it; it plays once the player has connected.
            pendingPlay = track to queue
            mutable.update { it.copy(loadingTrack = track, error = null, errorDiagnostic = "") }
            if (controllerFuture == null) { reconnects = 0; connectController() }
            return
        }
        reconnects = 0
        val ordered = queue.filterNot(library::isRemoved).take(100).let { if (it.any { t -> t.libraryKey() == track.libraryKey() }) it else listOf(track) + it }
        suppressedHistoryKey = null
        val selectedIndex = ordered.indexOfFirst { it === track }.takeIf { it >= 0 }
            ?: ordered.indexOfFirst { it.libraryKey() == track.libraryKey() }.coerceAtLeast(0)
        val items = ordered.map(::mediaItem)
        mutablePosition.value = startPositionMs
        mutable.update { it.copy(current = track, loadingTrack = null, error = null, errorDiagnostic = "", queue = ordered, queueIndex = selectedIndex, positionMs = startPositionMs, durationMs = 0) }
        radio?.reset()
        c.setMediaItems(items, selectedIndex, startPositionMs)
        c.prepare()
        c.play()
        // A single song starts radio from the selected recommendation provider.
        if (ordered.size == 1) radio?.request(force = true)
    }

    private fun mediaItem(item: Track): MediaItem = labs.rtensor.music.playback.PlayableItems.build(item, route(item))

    fun retryRadio() { radio?.retry() }

    /** Measure real listening time so history and recommendations reflect what was heard, not just tapped. */
    private fun trackListening(c: MediaController) {
        val now = android.os.SystemClock.elapsedRealtime()
        val tick = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0, 2_000); lastTick = now
        val playing = catalogueCache.read(c.currentMediaItem?.mediaMetadata?.extras?.getString("catalogue_track"))
        val key = playing?.libraryKey()
        if (key != observedHistoryKey) { suppressedHistoryKey = null; observedHistoryKey = key }
        val open = listen
        if (open != null && open.first.libraryKey() != key) finishListen()
        if (playing == null || !mutable.value.trackHistory || library.isRemoved(playing) || suppressedHistoryKey == key) return
        val active = listen ?: Triple(playing, System.currentTimeMillis(), 0L).also { listen = it }
        if (c.isPlaying) listen = active.copy(third = active.third + tick)
    }

    private fun finishListen(final: Boolean = false) {
        val (track, startedAt, listened) = listen ?: return
        listen = null
        if (!mutable.value.trackHistory || library.isRemoved(track) || listened < 5_000) return
        val duration = track.durationMs.takeIf { it > 0 } ?: mutable.value.durationMs
        val event = PlayEvent(enrich(track), startedAt, listened, skipped = !ListeningHistory.counts(listened, duration))
        mutable.update { it.copy(playEvents = (listOf(event) + it.playEvents).take(ListeningHistory.MAX_EVENTS)) }
        persistHistory(final)
    }

    private fun persistHistory(final: Boolean = false) {
        val snapshot = mutable.value.playEvents.toList()
        val revision = ++historyRevision
        if (final) listening.replace(snapshot, revision)
        else viewModelScope.launch(Dispatchers.IO) { listening.replace(snapshot, revision) }
    }

    private fun suppressCurrentHistory() {
        suppressedHistoryKey = controller?.currentMediaItem?.mediaMetadata?.extras?.getString("catalogue_track")
            ?.let(catalogueCache::read)?.libraryKey() ?: mutable.value.current?.libraryKey()
        observedHistoryKey = suppressedHistoryKey
        listen = null
    }

    /** Build "Mixed for you": radio from your most-played songs, re-ranked on this device. */
    fun refreshForYou() {
        if (forYouJob?.isActive == true) return
        val seeds = Recommender.seeds(mutable.value.playEvents, mutable.value.saved, rotation = forYouRotation++)
        if (seeds.isEmpty()) return
        val revision = recommendationRevision
        val engine = mutable.value.recommendationEngine
        forYouJob = viewModelScope.launch {
            mutable.update { it.copy(forYouBusy = true) }
            try {
                val pools = seeds.map { seed -> async { runCatching { providerRadio(seed) }.getOrElse { e -> if (e is CancellationException) throw e; emptyList() } } }.awaitAll()
                val mix = if (engine == RecommendationEngine.SPOTIFY) pools.flatten().distinctBy(Track::libraryKey).take(25)
                    else withContext(Dispatchers.Default) { Recommender.mix(pools, mutable.value.playEvents) }
                if (revision == recommendationRevision) mutable.update { it.copy(forYou = mix, forYouBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == recommendationRevision) mutable.update { it.copy(forYouBusy = false) } }
        }
    }

    fun toggle() {
        // A song still waiting for the player shows Play; tapping it keeps (or renews) that one request.
        mutable.value.loadingTrack?.let { if (!mutable.value.resolving) play(it, pendingPlay?.second ?: mutable.value.queue); return }
        withController { c -> PlayerTransport.toggle(c, ::retry); syncPlayer() }
    }

    /** Rebuild the current song with a fresh audio link, from where it stopped. */
    fun retry() {
        val s = mutable.value
        val track = s.queue.getOrNull(s.queueIndex) ?: s.current ?: return
        val resumeAt = controller?.takeIf { it.playerError != null && it.currentMediaItemIndex == s.queueIndex }
            ?.currentPosition?.coerceAtLeast(0) ?: 0
        start(track, s.queue, resumeAt)
    }

    /** Jump to a song already in the queue without rebuilding it, so preloaded audio and radio stay. */
    fun skipTo(index: Int) = withController { PlayerTransport.skipTo(it, index); syncPlayer() }

    fun next() = withController { PlayerTransport.next(it); syncPlayer() }

    fun previous() = withController { PlayerTransport.previous(it, mutable.value.durationMs); syncPlayer() }

    fun seek(position: Long) = withController { PlayerTransport.seek(it, position, mutable.value.durationMs); syncPlayer() }

    fun save(track: Track) {
        val old = mutable.value.saved
        val wasSaved = old.any { it.libraryKey() == track.libraryKey() }
        if (wasSaved && track.source == Source.LOCAL) {
            library.removeLocal(track)
            mastersJob?.cancel(); masters.removeUri(track.url); routes.clear()
            forYouJob?.cancel()
            if (mutable.value.current?.libraryKey() == track.libraryKey()) suppressCurrentHistory()
            mutable.update { it.withoutLocalRecording(track).copy(mastersBusy = false, mastersSummary = summary(masters.entries, masters.folder != null)) }
            library.writeTracks("saved", mutable.value.saved)
            library.writeTracks("history", mutable.value.history)
            persistHistory()
            controller?.let { LibraryQueueEdits.reconcile(it, library::isRemoved, setOf(track.url), ::mediaItem) }
            syncPlayer(includeQueue = true)
            return
        }
        if (library.isRemoved(track)) return
        val next = if (wasSaved) old.filterNot { it.libraryKey() == track.libraryKey() } else listOf(track) + old
        library.writeTracks("saved", next)
        mutable.update { it.copy(saved = next) }
    }

    private fun recordHistory(track: Track) {
        if (!mutable.value.trackHistory || library.isRemoved(track) || suppressedHistoryKey == track.libraryKey()) return
        val history = (listOf(track) + mutable.value.history.filterNot { it.libraryKey() == track.libraryKey() }).take(40)
        if (history == mutable.value.history) return
        library.writeTracks("history", history)
        mutable.update { it.copy(history = history) }
    }

    fun importLocal(uri: Uri) {
        val importRevision = library.localRemovalRevision
        viewModelScope.launch {
            mutable.update { it.copy(importMessage = "Importing audio…") }
            try {
                val track = withContext(Dispatchers.IO) {
                    LocalAudioImporter.read(getApplication<Application>(), uri)
                }
                if (!library.restoreLocal(track, importRevision)) {
                    mutable.update { it.copy(importMessage = "Import cancelled: this recording was removed.") }
                    return@launch
                }
                val saved = listOf(track) + mutable.value.saved.filterNot { it.libraryKey() == track.libraryKey() }
                library.writeTracks("saved", saved)
                // Without a lasting grant the file only stays readable until Music restarts.
                val lasting = withContext(Dispatchers.IO) { LocalMasters.hasReadGrant(getApplication(), uri.toString()) }
                mutable.update { it.copy(saved = saved, importMessage = if (lasting) "Added ${track.title} to Saved."
                    else "Added ${track.title} to Saved for now. The app that holds this file did not allow lasting access, so import it again after Music restarts.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(importMessage = "Could not import this file. Choose a readable audio file.") } }
        }
    }

    /** Index a folder of the user's own lossless and Atmos files, so matching songs play from them. */
    fun indexMasters(tree: Uri) {
        mastersJob?.cancel()
        mastersJob = viewModelScope.launch {
            mutable.update { it.copy(mastersBusy = true, mastersSummary = "Reading the folder…") }
            try {
                val found = withContext(Dispatchers.IO) {
                    masters.index(tree) { count ->
                        mutable.update { if (isActive && it.mastersBusy) it.copy(mastersSummary = "Reading the folder… $count files") else it }
                    }
                }
                routes.clear()
                mutable.update { it.copy(mastersBusy = false, mastersSummary = summary(found, true)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // An unreadable choice must not keep a grant for a folder Music does not use.
                if (masters.folder != tree.toString()) masters.releaseGrant(tree.toString())
                mutable.update { it.copy(mastersBusy = false, mastersSummary = "Could not read this folder. Choose it again.") }
            }
        }
    }

    fun clearMasters() {
        mastersJob?.cancel()
        val oldUris = masters.entries.map { it.uri }.toSet()
        masters.folder?.let(masters::releaseGrant)
        masters.clear(); routes.clear()
        mutable.update { it.copy(mastersBusy = false, mastersSummary = summary(emptyList(), false)) }
        controller?.let { LibraryQueueEdits.reconcile(it, library::isRemoved, oldUris, ::mediaItem) }
        syncPlayer(includeQueue = true)
    }

    private companion object {
        const val MASTERS_ACCESS_LOST = "Music can no longer read the chosen folder, so its recordings are not used. Choose the folder again."
    }

    private fun summary(found: List<labs.rtensor.music.data.Master>, chosen: Boolean): String {
        if (!chosen) return ""
        val atmos = found.count { it.atmos }
        val multichannel = found.count { it.multichannel && !it.atmos }
        val hiRes = found.count { !it.multichannel && it.lossless && it.sampleRate > 48_000 }
        val lossless = found.count { !it.multichannel && it.lossless && it.sampleRate <= 48_000 }
        return listOfNotNull("${found.size} files", "$atmos Atmos".takeIf { atmos > 0 }, "$multichannel multichannel".takeIf { multichannel > 0 },
            "$hiRes hi-res".takeIf { hiRes > 0 }, "$lossless CD-quality".takeIf { lossless > 0 }).joinToString(" · ")
    }

    fun theme(value: String) {
        if (value !in setOf("light", "dark", "system")) return
        library.theme = value; mutable.update { it.copy(theme = value) }
    }
    fun accent(value: AccentChoice) { library.accent = value; mutable.update { it.copy(accent = value) } }
    fun reducedMotion(value: Boolean) { library.reducedMotion = value; mutable.update { it.copy(reducedMotion = value) } }
    fun clearError() { mutable.update { it.copy(error = null, errorDiagnostic = "") } }
    fun clearHistory() {
        suppressCurrentHistory(); forYouJob?.cancel()
        library.writeTracks("history", emptyList()); mutable.update { it.withoutHistory() }
        persistHistory()
    }
    fun removePlay(event: PlayEvent) {
        if (mutable.value.current?.libraryKey() == event.track.libraryKey()) suppressCurrentHistory()
        forYouJob?.cancel()
        mutable.update { it.withoutHistory(event.track) }
        library.writeTracks("history", mutable.value.history)
        persistHistory()
    }
    fun processing(processed: Boolean) {
        library.processedAudio = processed
        AudioPipeline.selectProcessing(processed)
    }
    fun autoplay(value: Boolean) {
        library.autoplay = value; mutable.update { it.copy(autoplay = value) }
        if (value) radio?.request() else radio?.reset()
    }
    fun trackHistory(value: Boolean) { library.trackHistory = value; if (!value) listen = null; mutable.update { it.copy(trackHistory = value) } }
    /** Queue a song to play after the current one. */
    fun playNext(track: Track) {
        if (library.isRemoved(track)) return
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(track); return }
        c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(track)); syncPlayer(includeQueue = true)
    }

    override fun onCleared() {
        radio?.reset()
        spotify.close()
        finishListen(final = true)
        // Also commit a pending clear/remove if there was no active listen.
        persistHistory(final = true)
        cleared = true; pendingPlay = null
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null; controllerFuture = null
        super.onCleared()
    }
}

/** Songs the phone recommends right now (Mixed for you, then Home's feed), as the car's Home tab lists them. */
internal fun carHomeTracks(state: MusicState): List<Track> {
    return (state.forYou + labs.rtensor.music.ui.feedSongs(state.feed) + state.feedMore).filter { it.source != Source.LOCAL }.distinctBy { it.libraryKey() }.take(100)
}

internal const val PLAYER_STARTING = "The audio player is still starting. Try again in a moment."

/** Only network and source failures are worth a connection hint; decoding and
 * output failures need the format, not a retry. */
internal fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode / 1000) {
    3 -> "This file could not be read. It may be damaged or in an unsupported container."
    4 -> "This audio format could not be decoded on this device."
    5 -> "The audio output rejected this format."
    6 -> "This audio is protected and cannot be played here."
    else -> "Playback stopped. Check your connection and tap Retry to refresh the audio link."
}
