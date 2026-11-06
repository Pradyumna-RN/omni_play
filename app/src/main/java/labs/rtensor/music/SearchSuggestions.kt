package labs.rtensor.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import labs.rtensor.music.data.SearchSuggestion

/** The query revision guards even a transport that finishes after cancellation. */
internal class SearchSuggestions(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> List<SearchSuggestion>,
    private val publish: (String, List<SearchSuggestion>, Boolean) -> Unit,
    private val debounceMs: Long = 250,
) {
    private var revision = 0L
    private var job: Job? = null
    fun cancel(query: String = "") { revision++; job?.cancel(); job = null; publish(query, emptyList(), false) }
    fun query(value: String) {
        cancel(value)
        val query = value.trim().take(400)
        if (query.isEmpty() || query.startsWith("http://", true) || query.startsWith("https://", true)) return
        val ticket = revision
        publish(value, emptyList(), true)
        job = scope.launch {
            delay(debounceMs)
            try {
                val result = fetch(query)
                if (revision == ticket) publish(value, result.distinctBy { it.track?.libraryKey() ?: it.text.lowercase() }.take(8), false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == ticket) publish(value, emptyList(), false) }
        }
    }
}
