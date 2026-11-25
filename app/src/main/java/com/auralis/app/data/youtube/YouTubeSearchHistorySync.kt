package com.auralis.app.data.youtube

import com.auralis.app.data.db.SearchHistoryDao
import com.auralis.app.data.db.SearchHistoryEntity
import com.auralis.app.data.db.SearchHistoryRules

/**
 * Merges the account's recent YouTube searches into the local Recent-searches list, the search-box
 * twin of [YouTubeHistorySync].
 *
 * The list is a union, not a wipe-and-replace: a term typed in AURALIS is pushed to YouTube by one
 * signed search per committed term (`YouTubeRepository.recordSearch`), and a term searched on another
 * device is pulled in here. A term the
 * local list already knows keeps its own timestamp (a refresh never re-stamps an old search as "just
 * now"), and a local-only term the account has not returned is left untouched. Terms deleted in the
 * app are passed in as `blocked` so a pull racing the delete cannot bring them straight back.
 *
 * It never throws: a failed fetch is reported as [SearchHistorySyncResult.failed] and is otherwise a
 * no-op for that pass.
 */
class YouTubeSearchHistorySync(
    private val dao: SearchHistoryDao,
    private val fetch: suspend () -> YouTubeLoadResult<List<YtSearchSuggestion>>,
) {
    /**
     * One pass. Returns how many rows actually changed and whether the pull failed, so the caller can
     * log one honest line. A pass that changes nothing writes nothing: Room would otherwise emit the
     * same list every five minutes and recompose the Search screen for no reason.
     */
    suspend fun sync(
        nowMs: Long = System.currentTimeMillis(),
        /** Terms just deleted in the app (lowercased), suppressed from this pass. */
        blocked: Set<String> = emptySet(),
    ): SearchHistorySyncResult {
        val fetched = when (val result = runCatching { fetch() }.getOrElse {
            return SearchHistorySyncResult(merged = 0, failed = true)
        }) {
            is YouTubeLoadResult.Failure -> return SearchHistorySyncResult(merged = 0, failed = true)
            is YouTubeLoadResult.Success -> result.value
        }
        val existing = runCatching { dao.getAll() }.getOrDefault(emptyList())
        val merged = mergeSearchHistory(fetched, existing, nowMs, blocked)
        // Keep only rows that are new or whose position moved; an unchanged term is already stored.
        val byLower = existing.associateBy { it.query.lowercase() }
        val changed = merged.filter { row ->
            val previous = byLower[row.query.lowercase()]
            previous == null || previous.searchedAt != row.searchedAt || previous.query != row.query
        }
        if (changed.isNotEmpty()) runCatching { dao.upsertAll(changed) }
        return SearchHistorySyncResult(merged = changed.size, failed = false)
    }
}

/** Outcome of one [YouTubeSearchHistorySync.sync] pass. */
data class SearchHistorySyncResult(val merged: Int, val failed: Boolean)

/**
 * Pure merge step, kept out of Room so it can be unit-tested.
 *
 * [existing] is the local list, [nowMs] anchors a newly imported term (the page is newest first, so
 * imports keep that order) and [blocked] holds terms just deleted in the app. Only the rows to
 * upsert are returned; a local term the page did not return is simply absent, so the upsert leaves
 * it in place.
 */
fun mergeSearchHistory(
    fetched: List<YtSearchSuggestion>,
    existing: List<SearchHistoryEntity>,
    nowMs: Long,
    blocked: Set<String> = emptySet(),
): List<SearchHistoryEntity> {
    val byLower = existing.associateBy { it.query.lowercase() }
    val blockedLower = blocked.mapTo(HashSet()) { it.lowercase() }
    val seen = HashSet<String>()
    val rows = ArrayList<SearchHistoryEntity>(fetched.size)
    // Spacing for a freshly imported term; only imported rows advance it, so a known row's own
    // timestamp is never re-stamped.
    var rank = 0
    for (suggestion in fetched) {
        // A completion is not history; only a real recent search belongs in the list.
        if (!suggestion.fromHistory) continue
        // Same rule the Search box applies (trim, at least two characters).
        val text = SearchHistoryRules.normalize(suggestion.text) ?: continue
        val key = text.lowercase()
        if (!seen.add(key)) continue
        if (key in blockedLower) continue
        val previous = byLower[key]
        if (previous != null) {
            // Keep the local spelling and timestamp: the term is already known.
            rows += SearchHistoryEntity(query = previous.query, searchedAt = previous.searchedAt)
        } else {
            rows += SearchHistoryEntity(query = text, searchedAt = nowMs - rank)
            rank++
        }
    }
    // The page is newest-first, so a row must never be older than the row above it. When it is, the
    // account is saying the lower term was searched more recently than our own record (it was
    // searched elsewhere), so lift it forward. Never move a timestamp backwards.
    for (index in rows.size - 2 downTo 0) {
        if (rows[index].searchedAt <= rows[index + 1].searchedAt) {
            rows[index] = rows[index].copy(searchedAt = rows[index + 1].searchedAt + 1)
        }
    }
    return rows
}
