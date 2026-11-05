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
    copy(collectionBusy = false, collectionHasMore = offset > 0, error = message)

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
