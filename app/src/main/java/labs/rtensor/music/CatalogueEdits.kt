package labs.rtensor.music

import labs.rtensor.music.data.AccountState
import labs.rtensor.music.data.SourceException
import labs.rtensor.music.data.Track
import labs.rtensor.music.spotify.SpotifyCatalogueException

/** A collection page arrived: the first page replaces the list, later pages append without duplicates. */
internal fun MusicState.collectionPageLoaded(offset: Int, tracks: List<Track>, hasMore: Boolean): MusicState =
    copy(collection = (if (offset == 0) tracks else collection + tracks).distinctBy(Track::libraryKey),
        collectionBusy = false, collectionHasMore = hasMore)

/** A collection page failed. Songs already shown stay, and a failed "Load more" can be tapped again. */
internal fun MusicState.collectionPageFailed(offset: Int, message: String): MusicState =
    copy(collectionBusy = false, collectionHasMore = offset > 0, error = message, errorOrigin = ErrorOrigin.COLLECTION)

/** Provider errors already explain themselves; anything else gets a message for the screen that failed. */
internal fun catalogueErrorMessage(e: Exception, fallback: String): String =
    if (e is SourceException || e is SpotifyCatalogueException) e.message?.takeIf { it.isNotBlank() } ?: fallback else fallback

internal object CatalogueMessages {
    const val SEARCH = "Search could not finish. Check your connection and retry."
    const val FEED = "Recommendations could not load. Check your connection and retry."
    const val COLLECTION = "This collection could not load. Check your connection and retry."
}

/**
 * Signing out of YouTube resets its engine and stops playback that may be using the
 * account. When no YouTube account is connected, connecting or stored, there is nothing
 * to sign out of, so choosing or forgetting a Google profile must leave playback alone.
 */
internal fun youtubeSignOutNeeded(account: AccountState, storedSession: Boolean): Boolean =
    account.connected || account.connecting || storedSession

/** The search box was cleared. Only an error the search produced goes with it. */
internal fun MusicState.clearedSearch(): MusicState {
    val searchError = errorOrigin == ErrorOrigin.SEARCH
    return copy(query = "", searched = false, searching = false, results = emptyList(),
        error = if (searchError) null else error, errorDiagnostic = if (searchError) "" else errorDiagnostic)
}

/** Preserve genuine related rows or the last good page when an Explore response is empty. */
internal fun MusicState.exploreLoaded(sections: List<labs.rtensor.music.data.FeedSection>, problem: String? = null): MusicState =
    copy(explore = sections.ifEmpty { explore }, exploreBusy = false,
        exploreError = problem ?: if (sections.isEmpty()) "YouTube Music returned no Explore items. Pull down to retry." else null)

/** Category navigation carries provider parameters; only new items append on continuation. */
internal fun MusicState.browsePageLoaded(page: labs.rtensor.music.data.HomeFeed, more: Boolean): MusicState {
    val previous = if (more) collectionSections else emptyList()
    val seen = previous.flatMap { it.items }.mapTo(HashSet()) { it.id }
    val added = page.sections.map { section -> section.copy(items = section.items.filter { seen.add(it.id) }) }.filter { it.items.isNotEmpty() }
    return copy(collectionSections = previous + added, collectionBusy = false, collectionHasMore = page.hasMore, collectionBrowseError = null)
}
