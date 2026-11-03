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
    /** Net skips outweigh listens by this much (recent ones counting most). */
    private const val DISLIKED_ARTIST = -1.5
    private const val NEW_ARTIST_BONUS = 0.15

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

    /**
     * Up to [count] songs to seed radio from, each by a different main artist: your two
     * favourites, the last song you played through, then a rotating pick from the rest of
     * your top songs, so each refresh explores a different corner of your taste.
     */
    fun seeds(events: List<PlayEvent>, saved: List<Track> = emptyList(), count: Int = 4, now: Long = System.currentTimeMillis(), rotation: Int = 0): List<Track> {
        val scores = trackScores(events, now)
        val savedIds = saved.mapTo(HashSet()) { it.id }
        val tracks = (events.map { it.track } + saved).filter { it.source == Source.YOUTUBE }.associateBy { it.id }
        val ranked = tracks.values.filter { (scores[it.id] ?: 0.0) > 0 || it.id in savedIds }
            .sortedByDescending { (scores[it.id] ?: 0.0) + if (it.id in savedIds) 0.5 else 0.0 }
            .distinctBy(::mainArtist)
        if (ranked.size <= count) return ranked
        val picked = LinkedHashMap<String, Track>()
        ranked.take(2).forEach { picked[it.id] = it }
        val usedArtists = picked.values.mapTo(HashSet(), ::mainArtist)
        events.firstOrNull { !it.skipped && it.track.source == Source.YOUTUBE && mainArtist(it.track) !in usedArtists && (scores[it.track.id] ?: 0.0) > 0 }
            ?.let { picked[it.track.id] = it.track; usedArtists += mainArtist(it.track) }
        val rest = ranked.drop(2).take(12).filter { mainArtist(it) !in usedArtists }
        if (rest.isNotEmpty()) for (i in rest.indices) {
            if (picked.size >= count) break
            val track = rest[(i + rotation).mod(rest.size)]
            if (usedArtists.add(mainArtist(track))) picked[track.id] = track
        }
        return picked.values.take(count)
    }

    /**
     * Keep YouTube's radio order, minus what this device knows you do not want: songs you
     * skipped, and artists you skip far more than you finish.
     */
    fun filterRadio(tracks: List<Track>, events: List<PlayEvent>, now: Long = System.currentTimeMillis()): List<Track> {
        if (events.isEmpty()) return tracks
        val trackScore = trackScores(events, now)
        val affinity = artistAffinity(events, now)
        val kept = tracks.filter { track ->
            (trackScore[track.id] ?: 0.0) >= 0 &&
                ListeningHistory.artists(track.artist).none { (affinity[it.lowercase()] ?: 0.0) < DISLIKED_ARTIST }
        }
        return kept.ifEmpty { tracks }
    }

    /** Songs in YouTube's suggestions that you skipped or heard in the last few days are left out. */
    fun freshSongs(entries: List<FeedEntry>, events: List<PlayEvent>, now: Long = System.currentTimeMillis()): List<FeedEntry> {
        val bare = { id: String -> id.removePrefix("yt:") }
        val skipped = trackScores(events, now).filterValues { it < 0 }.keys.mapTo(HashSet(), bare)
        val recent = events.filter { now - it.startedAt < 3 * DAY }.mapTo(HashSet()) { bare(it.track.id) }
        return entries.filter { it.videoId.isBlank() || (it.videoId !in recent && it.videoId !in skipped) }
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
        val disliked = affinity.filterValues { it < DISLIKED_ARTIST }.keys
        val candidates = LinkedHashMap<String, Pair<Track, Double>>()
        for (pool in pools) pool.forEachIndexed { index, track ->
            if (track.id in recent || track.id in skipped) return@forEachIndexed
            if (ListeningHistory.artists(track.artist).any { it.lowercase() in disliked }) return@forEachIndexed
            val position = 1.0 / (1 + index / 5.0)
            val previous = candidates[track.id]
            candidates[track.id] = track to ((previous?.second ?: 0.0) + position)
        }
        // Known artists get a modest lift and new artists a small one, so the mix is not
        // only artists you already play: discovery is the point of a mix.
        val ranked = candidates.values.map { (track, base) ->
            val names = ListeningHistory.artists(track.artist).map { it.lowercase() }
            val known = names.mapNotNull { affinity[it] }
            val artist = if (known.isEmpty()) NEW_ARTIST_BONUS else known.max().coerceIn(-2.0, 2.0) * 0.3
            track to base + artist
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
