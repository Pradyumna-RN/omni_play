package labs.rtensor.music.data

import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device ranking for "Mixed for you". YouTube Music supplies candidates (radio for the
 * songs you play most); this re-ranks them with what only this device knows: how recently
 * you listened, which artists you keep finishing, and which songs you skip.
 * Pure functions, so it is cheap to test and nothing leaves the phone.
 */
object Recommender {
    private const val HALF_LIFE_DAYS = 14.0
    private const val DAY = ListeningHistory.DAY

    private fun decay(age: Long) = exp(-ln(2.0) * age.coerceAtLeast(0) / (HALF_LIFE_DAYS * DAY))

    /** Positive for artists you finish, negative for artists you skip; recent listens weigh most. */
    fun artistAffinity(events: List<PlayEvent>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val scores = HashMap<String, Double>()
        for (event in events) {
            val weight = decay(now - event.startedAt) * if (event.skipped) -0.6 else 1.0
            ListeningHistory.artists(event.track.artist).forEach { scores[it.lowercase()] = (scores[it.lowercase()] ?: 0.0) + weight }
        }
        return scores
    }

    fun trackScores(events: List<PlayEvent>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val scores = HashMap<String, Double>()
        for (event in events) {
            val weight = decay(now - event.startedAt) * if (event.skipped) -0.8 else 1.0
            scores[event.track.id] = (scores[event.track.id] ?: 0.0) + weight
        }
        return scores
    }

    /** Up to [count] favourite songs to seed radio from, each by a different main artist. */
    fun seeds(events: List<PlayEvent>, saved: List<Track> = emptyList(), count: Int = 3, now: Long = System.currentTimeMillis()): List<Track> {
        val scores = trackScores(events, now)
        val tracks = (events.map { it.track } + saved).filter { it.source == Source.YOUTUBE }.associateBy { it.id }
        val ranked = tracks.values.sortedByDescending { (scores[it.id] ?: 0.0) + if (saved.any { s -> s.id == it.id }) 0.5 else 0.0 }
            .filter { (scores[it.id] ?: 0.0) > 0 || saved.any { s -> s.id == it.id } }
        val usedArtists = HashSet<String>()
        return ranked.filter { usedArtists.add(mainArtist(it)) }.take(count)
    }

    /**
     * Merge radio pools into one mix. A song that several of your favourites lead to, early in
     * their radio, by an artist you like, ranks highest. Songs played in the last few days and
     * songs you skipped are left out, and no artist gets more than two slots.
     */
    fun mix(pools: List<List<Track>>, events: List<PlayEvent>, size: Int = 25, now: Long = System.currentTimeMillis()): List<Track> {
        val affinity = artistAffinity(events, now)
        val trackScore = trackScores(events, now)
        val recent = events.filter { now - it.startedAt < 3 * DAY }.map { it.track.id }.toSet()
        val skipped = trackScore.filterValues { it < 0 }.keys
        val candidates = LinkedHashMap<String, Pair<Track, Double>>()
        for (pool in pools) pool.forEachIndexed { index, track ->
            if (track.id in recent || track.id in skipped) return@forEachIndexed
            val position = 1.0 / (1 + index / 5.0)
            val previous = candidates[track.id]
            candidates[track.id] = track to ((previous?.second ?: 0.0) + position)
        }
        val ranked = candidates.values.map { (track, base) ->
            val artist = ListeningHistory.artists(track.artist).maxOfOrNull { affinity[it.lowercase()] ?: 0.0 } ?: 0.0
            track to base + artist.coerceIn(-2.0, 3.0) * 0.4
        }.sortedByDescending { it.second }.map { it.first }
        val perArtist = HashMap<String, Int>()
        val picked = ranked.filter { val a = mainArtist(it); val n = perArtist[a] ?: 0; if (n < 2) { perArtist[a] = n + 1; true } else false }
        return spread(picked.take(size))
    }

    /** Reorder so the same artist does not play twice in a row where that can be avoided. */
    fun spread(tracks: List<Track>): List<Track> {
        val rest = tracks.toMutableList(); val out = ArrayList<Track>(tracks.size)
        while (rest.isNotEmpty()) {
            val last = out.lastOrNull()?.let(::mainArtist)
            val index = rest.indexOfFirst { mainArtist(it) != last }.takeIf { it >= 0 } ?: 0
            out += rest.removeAt(index)
        }
        return out
    }

    private fun mainArtist(track: Track) = ListeningHistory.artists(track.artist).firstOrNull()?.lowercase() ?: track.id
}
