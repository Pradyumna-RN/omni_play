package labs.rtensor.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import labs.rtensor.music.data.PlaybackRoute
import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track
import labs.rtensor.music.data.YouTubeLink

/** The one way a [Track] becomes a playable session item, shared by the phone UI and the car. */
internal object PlayableItems {
    fun build(track: Track, route: PlaybackRoute): MediaItem {
        val extras = Bundle().apply {
            putString("catalogue_track", track.json().toString())
            putString("route", route.label)
            putString("fallback_route", route.label)
            route.master?.let { putString("local_master", it.json().toString()) }
        }
        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setArtworkUri(track.artwork.takeIf(String::isNotBlank)?.let(Uri::parse)).setExtras(extras).build()
        // Keep its discovery identity (YouTube or Spotify) when another source supplies audio.
        return MediaItem.Builder().setMediaId(track.id).setUri(route.master?.uri ?: track.url)
            .setCustomCacheKey(java.util.UUID.randomUUID().toString())
            .setMediaMetadata(metadata).build()
    }

    /** Only a local document, a YouTube recording or a pending Spotify catalogue entry may enter the player. */
    fun permitted(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri
        return uri?.scheme == "content" || (uri?.scheme == "https" && YouTubeLink.normalize(uri.toString())?.contains("/watch?v=") == true) ||
            spotifyCatalogueItem(item)
    }

    fun spotifyCatalogueItem(item: MediaItem): Boolean = runCatching {
        val track = Track.fromJson(org.json.JSONObject(item.mediaMetadata.extras?.getString("catalogue_track") ?: return@runCatching false))
        labs.rtensor.music.spotify.SpotifyTrackMatcher.pending(track) &&
            track.source == Source.YOUTUBE &&
            item.localConfiguration?.uri.toString() == "https://open.spotify.com/track/${track.spotifyId}"
    }.getOrDefault(false)
}
