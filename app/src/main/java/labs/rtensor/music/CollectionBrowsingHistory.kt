package labs.rtensor.music

import labs.rtensor.music.data.FeedEntry
import labs.rtensor.music.data.FeedSection
import labs.rtensor.music.data.Track

/** Only catalogue pages are restored; playback and provider account state remain current. */
internal class CollectionBrowsingHistory {
    data class Page(val entry: FeedEntry, val sections: List<FeedSection>, val tracks: List<Track>,
        val more: Boolean, val offset: Int?, val requestOffset: Int, val problem: String?) {
        fun restore(state: MusicState) = state.copy(collectionEntry = entry, collectionSections = sections,
            collection = tracks, collectionHasMore = more || offset != null, collectionBusy = false,
            collectionBrowseError = problem,
            error = if (state.errorOrigin == ErrorOrigin.COLLECTION) null else state.error,
            errorOrigin = if (state.errorOrigin == ErrorOrigin.COLLECTION) "" else state.errorOrigin)
    }
    private val pages = ArrayDeque<Page>()
    fun entering(state: MusicState, nested: Boolean, offset: Int?, requestOffset: Int) {
        if (!nested) { pages.clear(); return }
        val entry = state.collectionEntry ?: return
        pages.addLast(Page(entry, state.collectionSections, state.collection, state.collectionHasMore, offset, requestOffset, state.collectionBrowseError))
        if (pages.size > 12) pages.removeFirst()
    }
    fun clear() { pages.clear() }
    fun pop(): Page? = if (pages.isEmpty()) null else pages.removeLast()
}
