package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeDao
import com.auralis.app.data.db.YouTubeRecentEntity

/**
 * Merges a fetched `FEmusic_history` page into the local YouTube-history mirror.
 *
 * Until now the mirror (`youtube_recents`) was written only by in-app playback, so the History
 * screen and the "Your most played" row could not see a song the user played on another device —
 * and after a reinstall the account's real history never came back. [sync] fills that gap without
 * ever destroying local data:
 *
 *  - the fetched order (newest first) decides `playedAt`, so the list matches YouTube's own order;
 *  - a row the app already knows keeps its local `playCount` (an import never inflates the count);
 *  - a song the fetched page ranks above a locally newer one was played elsewhere, so it is lifted
 *    forward instead of being left where it was;
 *  - a row seen for the first time is imported with a count of 1, because it did play somewhere;
 *  - a song the app knows but YouTube did not return is left untouched;
 *  - a row still titled with its video id gains real metadata, a few at a time.
 *
 * It never throws: a failed fetch is reported as [HistorySyncResult.failed]; everything else is a
 * no-op for that pass.
 */
class YouTubeHistorySync(
    private val dao: YouTubeDao,
    private val fetch: suspend () -> YouTubeLoadResult<List<YtSong>>,
    /** Best-effort metadata for one video id, used to fill placeholder rows left by in-app plays. */
    private val fetchMetadata: suspend (String) -> YtSong? = { null },
) {
    /**
     * One pass. Returns how many rows were merged, whether the fetch itself failed and how many
     * placeholder rows gained real metadata, so the caller can log one honest line.
     */
    suspend fun sync(nowMs: Long = System.currentTimeMillis()): HistorySyncResult {
        val fetched = when (val result = runCatching { fetch() }.getOrElse {
            return HistorySyncResult(merged = 0, failed = true, backfilled = 0)
        }) {
            is YouTubeLoadResult.Failure -> return HistorySyncResult(merged = 0, failed = true, backfilled = 0)
            is YouTubeLoadResult.Success -> result.value
        }
        val existing = runCatching { dao.allRecents() }.getOrDefault(emptyList())
        val merged = mergeHistory(fetched, existing.associateBy { it.videoId }, nowMs)
        if (merged.isNotEmpty()) runCatching { dao.upsertRecents(merged) }
        return HistorySyncResult(
            merged = merged.size,
            failed = false,
            backfilled = backfillMetadata(existing, merged),
        )
    }

    /** Fills up to [METADATA_LIMIT] placeholder rows; never overwrites a real title. */
    private suspend fun backfillMetadata(
        existing: List<YouTubeRecentEntity>,
        merged: List<YouTubeRecentEntity>,
    ): Int {
        var patched = 0
        for (row in metadataTargets(existing, merged, METADATA_LIMIT)) {
            val song = runCatching { fetchMetadata(row.videoId) }.getOrNull() ?: continue
            runCatching { dao.upsertRecent(row.withMetadata(song)) }.onSuccess { patched++ }
        }
        return patched
    }

    companion object {
        /** At most this many metadata lookups per pass, so a long backfill cannot flood the network. */
        const val METADATA_LIMIT = 10
    }
}

/**
 * Outcome of one [YouTubeHistorySync.sync] pass. [failed] separates a broken pull from a page that
 * simply had nothing new; [backfilled] counts placeholder rows that gained real metadata.
 */
data class HistorySyncResult(val merged: Int, val failed: Boolean, val backfilled: Int)

/**
 * Pure merge step, kept separate from Room so it can be unit-tested: [existing] is keyed by
 * `videoId`, [nowMs] anchors the newest fetched song.
 */
fun mergeHistory(
    fetched: List<YtSong>,
    existing: Map<String, YouTubeRecentEntity>,
    nowMs: Long,
): List<YouTubeRecentEntity> {
    val seen = HashSet<String>()
    val rows = ArrayList<YouTubeRecentEntity>(fetched.size)
    var rank = 0
    for (song in fetched) {
        val videoId = song.videoId.trim()
        if (videoId.isEmpty()) continue
        if (!seen.add(videoId)) continue
        val previous = existing[videoId]
        // A row already in the mirror keeps its real timestamp: re-stamping it with "now" on every
        // five-minute refresh would make old YouTube history look like it was just played. Only a
        // newly imported row needs a timestamp, and the page's own order spaces those out.
        val fetchedAt = nowMs - rank
        rank++
        rows += YouTubeRecentEntity(
            videoId = videoId,
            title = song.title.ifBlank { previous?.title.orEmpty() },
            artist = song.artist.ifBlank { previous?.artist.orEmpty() },
            album = song.album.ifBlank { previous?.album.orEmpty() },
            durationMs = if (song.durationMs > 0L) song.durationMs else previous?.durationMs ?: 0L,
            thumbnailUrl = song.thumbnailUrl?.let(::normalizeArtworkUrl) ?: previous?.thumbnailUrl,
            playedAt = previous?.playedAt ?: fetchedAt,
            playCount = previous?.playCount?.coerceAtLeast(1) ?: 1,
        )
    }
    // The page is newest-first, so a row must never be older than the row above it. When it is, the
    // page is saying the lower row was played more recently than our own record (it was played on
    // another device), so lift it forward. Never move a timestamp backwards and never touch counting.
    for (index in rows.size - 2 downTo 0) {
        if (rows[index].playedAt <= rows[index + 1].playedAt) {
            rows[index] = rows[index].copy(playedAt = rows[index + 1].playedAt + 1)
        }
    }
    return rows
}

/**
 * Rows to ask metadata for: their stored title is still the video id an in-app play writes before
 * metadata exists (or blank). A row the merge just gave a real title is never a target. Capped so a
 * single pass cannot fan out into a burst of requests.
 */
fun metadataTargets(
    existing: List<YouTubeRecentEntity>,
    merged: List<YouTubeRecentEntity>,
    limit: Int,
): List<YouTubeRecentEntity> {
    if (limit <= 0) return emptyList()
    val current = (existing + merged).associateBy { it.videoId }
    return current.values
        .filter { it.title.isBlank() || it.title == it.videoId }
        .take(limit)
}

/** The same row with fetched metadata filled in; never lowers a value it already has. */
fun YouTubeRecentEntity.withMetadata(song: YtSong): YouTubeRecentEntity = copy(
    title = song.title.ifBlank { title },
    artist = song.artist.ifBlank { artist },
    album = song.album.ifBlank { album },
    durationMs = if (song.durationMs > 0L) song.durationMs else durationMs,
    thumbnailUrl = song.thumbnailUrl?.let(::normalizeArtworkUrl) ?: thumbnailUrl,
)
