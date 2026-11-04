package labs.rtensor.music

import labs.rtensor.music.data.Source
import labs.rtensor.music.data.Track

/** History deletion also removes Home's recent shortcut, but leaves Saved alone. */
internal fun MusicState.withoutHistory(track: Track? = null): MusicState {
    val key = track?.libraryKey()
    return copy(
        history = if (key == null) emptyList() else history.filterNot { it.libraryKey() == key },
        playEvents = if (key == null) emptyList() else playEvents.filterNot { it.track.libraryKey() == key },
        forYou = emptyList(), forYouBusy = false,
    )
}

/** Removing an imported file is stronger than unbookmarking an online song. */
internal fun MusicState.withoutLocalRecording(track: Track): MusicState {
    require(track.source == Source.LOCAL)
    fun Track.matches() = source == Source.LOCAL && url == track.url
    val remainingQueue = queue.filterNot { it.matches() }
    val removedCurrent = current?.matches() == true
    return withoutHistory(track).copy(
        saved = saved.filterNot { it.matches() },
        queue = remainingQueue,
        queueIndex = if (removedCurrent || remainingQueue.isEmpty() || queueIndex < 0) -1 else queue.take(queueIndex).count { !it.matches() },
        current = current?.takeUnless { it.matches() },
        loadingTrack = loadingTrack?.takeUnless { it.matches() },
        results = results.filterNot { it.matches() },
        collection = collection.filterNot { it.matches() },
        playing = if (removedCurrent) false else playing,
        buffering = if (removedCurrent) false else buffering,
        resolving = if (removedCurrent) false else resolving,
        positionMs = if (removedCurrent) 0 else positionMs,
        durationMs = if (removedCurrent) 0 else durationMs,
        error = if (removedCurrent) null else error,
        errorDiagnostic = if (removedCurrent) "" else errorDiagnostic,
    )
}
