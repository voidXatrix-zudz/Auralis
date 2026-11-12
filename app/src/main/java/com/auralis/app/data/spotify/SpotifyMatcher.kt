package com.auralis.app.data.spotify

import com.auralis.app.data.youtube.YtSong
import kotlin.math.max

/**
 * Scores YouTube Music search results against a Spotify row. The goal is to prefer the studio
 * recording: title/artist similarity dominates, an exact-ish duration breaks ties, and live/remix/
 * lyric/slowed variants are penalized unless the Spotify title also asks for one.
 */
object SpotifyMatcher {

    data class Candidate(val song: YtSong, val score: Double, val confident: Boolean)

    private val VARIANT_WORDS = listOf(
        "live", "remix", "cover", "karaoke", "instrumental", "slowed", "sped up", "nightcore",
        "mashup", "acoustic", "demo", "reverb", "lyric", "lyrics", "8d", "edit",
    )

    /** "title artist" query for the InnerTube songs filter. */
    fun query(row: SpotifyRow): String {
        val artist = row.artist.substringBefore(',').trim()
        return if (artist.isBlank()) row.title.trim() else "${row.title.trim()} $artist"
    }

    /** Best candidate for [row] across [results], or null when nothing scores above the floor. */
    fun best(row: SpotifyRow, results: List<YtSong>): Candidate? =
        candidates(row, results).firstOrNull()

    /** Candidates sorted best-first, capped at [limit], for the review screen. */
    fun candidates(row: SpotifyRow, results: List<YtSong>, limit: Int = 6): List<Candidate> =
        results.map { score(row, it) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit)

    fun score(row: SpotifyRow, song: YtSong): Candidate {
        val titleSim = similarity(row.title, song.title)
        val artistSim = artistSimilarity(row.artist, song.artist)
        val durationOk = row.durationMs <= 0L ||
            (song.durationMs > 0L && kotlin.math.abs(song.durationMs - row.durationMs) <= 3_000L)
        var value = 0.65 * titleSim + 0.35 * artistSim
        if (!durationOk) value -= 0.35
        value -= versionPenalty(row.title, song.title)
        val confident = value >= 0.62 && durationOk && artistSim >= 0.34 && titleSim >= 0.5
        return Candidate(song, value.coerceIn(0.0, 1.0), confident)
    }

    /** Penalizes live/remix/… variants that the Spotify title did not ask for. */
    private fun versionPenalty(queryTitle: String, songTitle: String): Double {
        val query = queryTitle.lowercase()
        val song = songTitle.lowercase()
        var penalty = 0.0
        for (word in VARIANT_WORDS) {
            if (song.contains(word) && !query.contains(word)) penalty += 0.2
        }
        return penalty.coerceAtMost(0.6)
    }

    private fun artistSimilarity(queryArtist: String, songArtist: String): Double {
        val a = tokens(queryArtist)
        val b = tokens(songArtist)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val overlap = a.intersect(b).size.toDouble() / minOf(a.size, b.size).coerceAtLeast(1)
        return if (overlap > 0.0) overlap else similarity(queryArtist, songArtist)
    }

    private fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val distance = levenshtein(na, nb)
        return (1.0 - distance.toDouble() / max(na.length, nb.length)).coerceIn(0.0, 1.0)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\b(feat|ft|featuring|with)\\b.*$"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokens(value: String): Set<String> =
        normalize(value).split(' ').filter { it.isNotBlank() }.toSet()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
