package com.auralis.app.domain.stats

import com.auralis.app.domain.model.Track
import java.util.Calendar
import java.util.TimeZone

data class PlayEvent(val trackId: String, val playedAt: Long, val durationMs: Long)

data class TopTrack(val track: Track, val plays: Int, val listenedMs: Long)
data class TopArtist(val name: String, val plays: Int, val listenedMs: Long)

data class WrappedStats(
    val year: Int,
    val month: Int?,
    val totalPlays: Int,
    val listenedMs: Long,
    val uniqueTracks: Int,
    val uniqueArtists: Int,
    val daysActive: Int,
    val topTracks: List<TopTrack>,
    val topArtists: List<TopArtist>,
    val monthlyPlays: List<Int>,
    val availableYears: List<Int>,
    val availableMonths: List<Int>,
)

/** Pure aggregation of real play events for the monthly and yearly Wrapped recap. */
object ListeningStats {
    fun compute(
        tracks: List<Track>,
        events: List<PlayEvent>,
        year: Int,
        month: Int?,
        timeZone: TimeZone = TimeZone.getDefault(),
        limit: Int = 10,
    ): WrappedStats {
        val byId = tracks.associateBy { it.id }
        val calendar = Calendar.getInstance(timeZone)
        val filtered = events.filter { event ->
            calendar.timeInMillis = event.playedAt
            val eventYear = calendar.get(Calendar.YEAR)
            val eventMonth = calendar.get(Calendar.MONTH) + 1
            eventYear == year && (month == null || eventMonth == month)
        }
        val known = filtered.mapNotNull { event -> byId[event.trackId]?.let { it to event } }
        val monthCounts = MutableList(12) { 0 }
        val days = HashSet<Int>()
        for (event in filtered) {
            calendar.timeInMillis = event.playedAt
            if (month == null) monthCounts[calendar.get(Calendar.MONTH)] += 1
            days += calendar.get(Calendar.DAY_OF_YEAR)
        }
        val trackAgg = known.groupBy { it.first.id }.map { (_, list) ->
            TopTrack(
                track = list.first().first,
                plays = list.size,
                listenedMs = list.sumOf { it.second.durationMs.coerceAtLeast(0L) },
            )
        }.sortedWith(compareByDescending<TopTrack> { it.plays }.thenByDescending { it.listenedMs }.thenBy { it.track.title })
        val artistAgg = known.groupBy { it.first.artist.ifBlank { "Unknown artist" } }.map { (name, list) ->
            TopArtist(
                name = name,
                plays = list.size,
                listenedMs = list.sumOf { it.second.durationMs.coerceAtLeast(0L) },
            )
        }.sortedWith(compareByDescending<TopArtist> { it.plays }.thenByDescending { it.listenedMs }.thenBy { it.name })

        val availableYears = events.map { event ->
            calendar.timeInMillis = event.playedAt
            calendar.get(Calendar.YEAR)
        }.distinct().sortedDescending()
        val availableMonths = filtered.map { event ->
            calendar.timeInMillis = event.playedAt
            calendar.get(Calendar.MONTH) + 1
        }.distinct().sorted()

        return WrappedStats(
            year = year,
            month = month,
            totalPlays = filtered.size,
            listenedMs = filtered.sumOf { it.durationMs.coerceAtLeast(0L) },
            uniqueTracks = known.map { it.first.id }.distinct().size,
            uniqueArtists = known.map { it.first.artist.ifBlank { "Unknown artist" } }.distinct().size,
            daysActive = days.size,
            topTracks = trackAgg.take(limit),
            topArtists = artistAgg.take(limit),
            monthlyPlays = monthCounts,
            availableYears = availableYears,
            availableMonths = availableMonths,
        )
    }
}
