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
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import labs.rtensor.dsp.ListeningMode
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
import labs.rtensor.music.updates.GitHubUpdater
import labs.rtensor.music.updates.UpdateState
import labs.rtensor.music.playback.MusicPlaybackService
import labs.rtensor.music.playback.AudioPipeline
import labs.rtensor.music.playback.AudioProcessingState
import labs.rtensor.music.playback.CatalogueTrackCache
import labs.rtensor.music.sources.*

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
    val reducedMotion: Boolean = false,
    val error: String? = null,
    val errorDiagnostic: String = "",
    val account: AccountState = AccountState(),
    val googleProfile: GoogleProfile? = null,
    val googleBusy: Boolean = false,
    val googleError: String? = null,
    val feed: List<FeedSection> = emptyList(),
    val feedBusy: Boolean = false,
    val feedHasMore: Boolean = false,
    val feedError: String? = null,
    /** False when an account is connected but YouTube answered the feed as signed out. */
    val feedPersonalized: Boolean = true,
    val playEvents: List<PlayEvent> = emptyList(),
    val forYou: List<Track> = emptyList(),
    val forYouBusy: Boolean = false,
    val autoplay: Boolean = true,
    val trackHistory: Boolean = true,
    val explore: List<FeedSection> = emptyList(),
    val exploreBusy: Boolean = false,
    val exploreError: String? = null,
    val collection: List<Track> = emptyList(),
    val collectionEntry: FeedEntry? = null,
    val collectionBusy: Boolean = false,
    val update: UpdateState = UpdateState(),
    val audioProcessing: AudioProcessingState = AudioProcessingState(),
    /** How the current song is played: the user's own Atmos or lossless file, or YouTube. */
    val routeLabel: String = "",
    val mastersSummary: String = "",
    val mastersBusy: Boolean = false,
    val playbackSources: List<SourceSummary> = emptyList(),
    val sourceBusy: Boolean = false,
    val sourceMessage: String? = null,
)

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    private val listening = ListeningHistory(application)
    private val googleProfiles = GoogleProfileStore(application)
    private val youtube = YouTubeSource(application)
    private val updater = GitHubUpdater(application)
    private val masters = LocalMasters(application)
    private val sources = PlaybackSources.get(application)
    private val routes = mutableMapOf<String, PlaybackRoute>()
    private fun route(track: Track) = routes.getOrPut(track.id) { PlaybackRoute.choose(track, masters.entries) }
    private val mutable = MutableStateFlow(MusicState(
        saved = library.tracks("saved"), history = library.tracks("history"), googleProfile = googleProfiles.load(),
        theme = library.theme, reducedMotion = library.reducedMotion, playEvents = listening.events,
        autoplay = library.autoplay, trackHistory = library.trackHistory,
        mastersSummary = summary(masters.entries, masters.folder != null),
        playbackSources = sources.settings.connections.value.map { it.summary() },
    ))
    val state = mutable.asStateFlow()
    private val mutablePosition = MutableStateFlow(0L)
    val playbackPosition = mutablePosition.asStateFlow()
    private val catalogueCache = CatalogueTrackCache()
    private val metadata = mutableMapOf<String, YouTubeMetadata>()
    private fun enrich(track: Track) = metadata[track.id]?.enrich(track) ?: track
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var feedJob: Job? = null
    private var collectionJob: Job? = null
    private var exploreJob: Job? = null
    private var radioJob: Job? = null
    private var forYouJob: Job? = null
    private var forYouRotation = 0
    private val radioSeeds = mutableSetOf<String>()
    /** The listen in progress: which song, when it started, and how long it has actually played. */
    private var listen: Triple<Track, Long, Long>? = null
    private var lastTick = 0L
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
            if (playbackState == Player.STATE_READY) mutable.update { it.copy(error = null, errorDiagnostic = "") }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayer(includeQueue = true)
            continueWithRadio()
            mutable.update { it.copy(error = null, errorDiagnostic = "") }
        }
        override fun onPlayerError(error: PlaybackException) {
            syncPlayer(includeQueue = true)
            val sourceError = generateSequence(error as Throwable) { it.cause }.filterIsInstance<SourceException>().firstOrNull()
            val provider = youtube.runtime.problem.value?.takeIf { it.videoId == mutable.value.current?.id?.removePrefix("yt:") }
            // The service classified the full cause chain before this callback arrived.
            val audio = AudioPipeline.state.value.failure
            mutable.update { it.copy(error = sourceError?.message ?: provider?.message ?: audio?.message ?: playbackErrorMessage(error),
                errorDiagnostic = sourceError?.diagnostic ?: provider?.let { p -> "${p.code}: ${p.diagnostic}" } ?: audio?.detail ?: error.errorCodeName,
                resolving = false, buffering = false) }
        }
    }

    init {
        AudioPipeline.selectProcessing(library.processedAudio)
        AudioPipeline.selectSpatial(library.spatialAudio)
        viewModelScope.launch {
            AudioPipeline.state.collect { audio -> mutable.update { it.copy(audioProcessing = audio, routeLabel = currentRoute(audio)) } }
        }
        viewModelScope.launch { sources.state.collect { mutable.update { it.copy(routeLabel = currentRoute(it.audioProcessing)) } } }
        viewModelScope.launch { sources.settings.connections.collect { profiles -> mutable.update { it.copy(playbackSources = profiles.map { p -> p.summary() }) } } }
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
                mutable.update { it.copy(account = account, feed = if (changed) emptyList() else it.feed, explore = if (changed) emptyList() else it.explore) }
                if (changed) { refreshFeed(); refreshExplore() }
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
        checkUpdates()
    }

    fun refreshFeed(more: Boolean = false) {
        if (more && (mutable.value.feedBusy || !mutable.value.feedHasMore)) return
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            mutable.update { it.copy(feedBusy = true, feedError = null) }
            try {
                val feed = youtube.home(more)
                mutable.update { it.copy(feedBusy = false, feed = if (more) it.feed + feed.sections else feed.sections, feedHasMore = feed.hasMore,
                    feedPersonalized = !it.account.connected || feed.personalized) }
                if (!more && mutable.value.forYou.isEmpty()) refreshForYou()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(feedBusy = false, feedError = sourceMessage(e)) } }
        }
    }

    fun refreshExplore() {
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreError = null) }
            try {
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
                mutable.update { it.copy(explore = sections, exploreBusy = false) }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(exploreBusy = false, exploreError = sourceMessage(e)) } }
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

    fun browse(entry: FeedEntry) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            mutable.update { it.copy(collectionEntry = entry, collection = emptyList(), collectionBusy = true, error = null) }
            try { val tracks = youtube.collection(entry); mutable.update { it.copy(collection = tracks, collectionBusy = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(collectionBusy = false, error = sourceMessage(e)) } }
        }
    }
    fun retryCollection() { mutable.value.collectionEntry?.let(::browse) }
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
                mutable.update { it.copy(update = UpdateState(release = release, message = if (release == null) "You’re up to date." else "Version ${release.version} is available.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(update = it.update.copy(checking = false, message = e.message ?: "Could not check for updates.")) } }
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
            catch (e: Exception) { mutable.update { it.copy(update = it.update.copy(downloading = false, message = e.message ?: "The update download failed.")) } }
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
            current = restored?.let(::enrich) ?: it.current,
            queue = if (includeQueue) queue.map(::enrich) else queue,
            queueIndex = c.currentMediaItemIndex,
            routeLabel = currentRoute(it.audioProcessing),
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = mutablePosition.value,
            durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0,
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
                ListeningMode.SPATIAL -> "Local file · ${report.inputPositions.size} ch → headphones"
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

    fun removeSource(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sources.settings.remove(id) }
            mutable.update { it.copy(sourceMessage = null) }
        }
    }

    fun setQuery(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            mutable.update { it.copy(query = "", searched = false, searching = false, results = emptyList(), error = null, errorDiagnostic = "") }
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
                mutable.update { it.copy(searching = false, error = sourceMessage(e), errorDiagnostic = (e as? SourceException)?.diagnostic.orEmpty()) }
            }
        }
    }

    fun play(track: Track, queue: List<Track> = listOf(track)) {
        val c = controller ?: run { mutable.update { it.copy(error = "The audio player is still starting. Try again in a moment.") }; return }
        val ordered = queue.take(100).let { if (it.any { t -> t.id == track.id }) it else listOf(track) + it }
        val selectedIndex = ordered.indexOfFirst { it === track }.takeIf { it >= 0 }
            ?: ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = ordered.map(::mediaItem)
        mutablePosition.value = 0
        mutable.update { it.copy(current = track, loadingTrack = null, error = null, errorDiagnostic = "", queue = ordered, queueIndex = selectedIndex, positionMs = 0, durationMs = 0) }
        radioJob?.cancel(); radioSeeds.clear()
        c.setMediaItems(items, selectedIndex, 0)
        c.prepare()
        c.play()
        // A single song starts its YouTube Music radio, like tapping a song in YouTube Music.
        if (ordered.size == 1) continueWithRadio(force = true)
    }

    private fun mediaItem(item: Track): MediaItem {
        val route = route(item)
        val extras = Bundle().apply {
            putString("catalogue_track", item.json().toString())
            putString("route", route.label)
            putString("fallback_route", route.label)
            route.master?.let { putString("local_master", it.json().toString()) }
        }
        val metadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist)
            .setArtworkUri(item.artwork.takeIf(String::isNotBlank)?.let(Uri::parse)).setExtras(extras).build()
        // The song keeps its YouTube identity (history, radio, recommendations); only the audio comes from the better file.
        return MediaItem.Builder().setMediaId(item.id).setUri(route.master?.uri ?: item.url)
            .setCustomCacheKey(java.util.UUID.randomUUID().toString())
            .setMediaMetadata(metadata).build()
    }

    /** Append radio for the playing song when the queue is about to run out. */
    private fun continueWithRadio(force: Boolean = false) {
        val c = controller ?: return
        if (!mutable.value.autoplay || radioJob?.isActive == true) return
        if (!force && c.currentMediaItemIndex < c.mediaItemCount - 2) return
        val seed = mutable.value.queue.getOrNull(c.currentMediaItemIndex) ?: mutable.value.current ?: return
        if (seed.source != Source.YOUTUBE || !radioSeeds.add(seed.id)) return
        radioJob = viewModelScope.launch {
            try {
                val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
                val recent = mutable.value.playEvents.take(30).map { it.track.id }.toSet()
                val radio = Recommender.filterRadio(youtube.radio(seed, have), mutable.value.playEvents)
                // Prefer songs not heard in the last few plays; fall back to the full radio if that empties it.
                val tracks = radio.filter { it.id !in recent }.ifEmpty { radio }.take((100 - c.mediaItemCount).coerceAtLeast(0))
                if (tracks.isNotEmpty() && c.mediaItemCount > 0) c.addMediaItems(tracks.map(::mediaItem))
                syncPlayer(includeQueue = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { radioSeeds.remove(seed.id) /* Radio is optional; the queue simply ends. */ }
        }
    }

    /** Measure real listening time so history and recommendations reflect what was heard, not just tapped. */
    private fun trackListening(c: MediaController) {
        val now = android.os.SystemClock.elapsedRealtime()
        val tick = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0, 2_000); lastTick = now
        val playing = catalogueCache.read(c.currentMediaItem?.mediaMetadata?.extras?.getString("catalogue_track"))
        val open = listen
        if (open != null && open.first.id != playing?.id) finishListen()
        if (playing == null) return
        val active = listen ?: Triple(playing, System.currentTimeMillis(), 0L).also { listen = it }
        if (c.isPlaying) listen = active.copy(third = active.third + tick)
    }

    private fun finishListen(final: Boolean = false) {
        val (track, startedAt, listened) = listen ?: return
        listen = null
        if (!mutable.value.trackHistory || listened < 5_000) return
        val duration = track.durationMs.takeIf { it > 0 } ?: mutable.value.durationMs
        val event = PlayEvent(enrich(track), startedAt, listened, skipped = !ListeningHistory.counts(listened, duration))
        mutable.update { it.copy(playEvents = (listOf(event) + it.playEvents).take(ListeningHistory.MAX_EVENTS)) }
        if (final) listening.add(event) else viewModelScope.launch(Dispatchers.IO) { listening.add(event) }
    }

    /** Build "Mixed for you": radio from your most-played songs, re-ranked on this device. */
    fun refreshForYou() {
        if (forYouJob?.isActive == true) return
        val seeds = Recommender.seeds(mutable.value.playEvents, mutable.value.saved, rotation = forYouRotation++)
        if (seeds.isEmpty()) return
        forYouJob = viewModelScope.launch {
            mutable.update { it.copy(forYouBusy = true) }
            try {
                val pools = seeds.map { seed -> async { runCatching { youtube.radio(seed) }.getOrElse { e -> if (e is CancellationException) throw e; emptyList() } } }.awaitAll()
                val mix = withContext(Dispatchers.Default) { Recommender.mix(pools, mutable.value.playEvents) }
                mutable.update { it.copy(forYou = mix, forYouBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(forYouBusy = false) } }
        }
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

    /** Jump to a song already in the queue without rebuilding it, so preloaded audio and radio stay. */
    fun skipTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        if ((controller?.currentPosition ?: mutablePosition.value) > 3000) { seek(0); return }
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem() ?: seek(0)
    }

    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, mutable.value.durationMs)); syncPlayer() }

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

    fun importLocal(uri: Uri) {
        viewModelScope.launch {
            mutable.update { it.copy(importMessage = "Importing audio…") }
            try {
                val track = withContext(Dispatchers.IO) {
                    LocalAudioImporter.read(getApplication<Application>(), uri)
                }
                val saved = listOf(track) + mutable.value.saved.filterNot { it.id == track.id }
                library.writeTracks("saved", saved)
                mutable.update { it.copy(saved = saved, importMessage = "Added ${track.title} to Saved.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(importMessage = "Could not import this file. Choose a readable audio file.") } }
        }
    }

    /** Index a folder of the user's own lossless and Atmos files, so matching songs play from them. */
    fun indexMasters(tree: Uri) {
        viewModelScope.launch {
            mutable.update { it.copy(mastersBusy = true, mastersSummary = "Reading the folder…") }
            try {
                val found = withContext(Dispatchers.IO) {
                    masters.index(tree) { count -> mutable.update { it.copy(mastersSummary = "Reading the folder… $count files") } }
                }
                routes.clear()
                mutable.update { it.copy(mastersBusy = false, mastersSummary = summary(found, true)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(mastersBusy = false, mastersSummary = "Could not read this folder. Choose it again.") } }
        }
    }

    fun clearMasters() {
        masters.folder?.let { folder -> runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(folder), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        masters.clear(); routes.clear()
        mutable.update { it.copy(mastersSummary = summary(emptyList(), false)) }
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
    fun reducedMotion(value: Boolean) { library.reducedMotion = value; mutable.update { it.copy(reducedMotion = value) } }
    fun clearError() { mutable.update { it.copy(error = null, errorDiagnostic = "") } }
    fun clearHistory() {
        listen = null
        library.writeTracks("history", emptyList()); mutable.update { it.copy(history = emptyList(), playEvents = emptyList(), forYou = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) { listening.clear() }
    }
    fun removePlay(event: PlayEvent) {
        val key = ListeningHistory.songKey(event.track.id)
        mutable.update { it.copy(playEvents = it.playEvents.filterNot { e -> ListeningHistory.songKey(e.track.id) == key }) }
        viewModelScope.launch(Dispatchers.IO) { listening.removeSong(event.track.id) }
    }
    fun processing(processed: Boolean) {
        library.processedAudio = processed
        AudioPipeline.selectProcessing(processed)
    }
    fun spatial(enabled: Boolean) {
        library.spatialAudio = enabled
        AudioPipeline.selectSpatial(enabled)
    }
    fun autoplay(value: Boolean) { library.autoplay = value; mutable.update { it.copy(autoplay = value) } }
    fun trackHistory(value: Boolean) { library.trackHistory = value; if (!value) listen = null; mutable.update { it.copy(trackHistory = value) } }
    /** Queue a song to play after the current one. */
    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(track); return }
        c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(track)); syncPlayer(includeQueue = true)
    }

    private fun sourceMessage(e: Exception): String =
        if (e is SourceException) e.message.orEmpty() else "Could not load this recording. Check your connection and retry."

    override fun onCleared() {
        finishListen(final = true)
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}

/** Only network and source failures are worth a connection hint; decoding and
 * output failures need the format, not a retry. */
internal fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode / 1000) {
    3 -> "This file could not be read. It may be damaged or in an unsupported container."
    4 -> "This audio format could not be decoded on this device."
    5 -> "The audio output rejected this format."
    6 -> "This audio is protected and cannot be played here."
    else -> "Playback stopped. Check your connection and tap Retry to refresh the audio link."
}
