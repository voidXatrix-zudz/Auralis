package com.auralis.app.data.youtube

import com.auralis.app.data.db.SearchHistoryDao
import com.auralis.app.data.db.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account's recent searches are merged into the local Recent list without importing completions,
 * re-stamping a term that is already known, dropping local-only terms, or resurrecting a term the
 * user just deleted.
 */
class YouTubeSearchHistorySyncTest {

    private fun history(text: String, token: String? = "tok-$text") =
        YtSearchSuggestion(text = text, fromHistory = true, feedbackToken = token)

    private fun completion(text: String) = YtSearchSuggestion(text = text, fromHistory = false)

    private fun row(query: String, searchedAt: Long) = SearchHistoryEntity(query, searchedAt)

    @Test
    fun `fetched history becomes newest first`() {
        val merged = mergeSearchHistory(
            listOf(history("adele"), history("weeknd"), history("daft punk")),
            emptyList(),
            1_000_000L,
        )
        assertEquals(listOf("adele", "weeknd", "daft punk"), merged.map { it.query })
        assertTrue(merged[0].searchedAt > merged[1].searchedAt)
        assertTrue(merged[1].searchedAt > merged[2].searchedAt)
    }

    @Test
    fun `completions are never imported`() {
        val merged = mergeSearchHistory(
            listOf(completion("adele live"), history("adele")),
            emptyList(),
            10L,
        )
        assertEquals(listOf("adele"), merged.map { it.query })
    }

    @Test
    fun `an existing term keeps its timestamp and casing`() {
        val existing = listOf(row("Weeknd", 5L))
        val merged = mergeSearchHistory(listOf(history("weeknd")), existing, 9_000L)
        assertEquals(listOf("Weeknd"), merged.map { it.query })
        assertEquals(5L, merged.single().searchedAt)
    }

    @Test
    fun `a local-only term is not returned, so the upsert leaves it alone`() {
        val existing = listOf(row("local only", 50L))
        val merged = mergeSearchHistory(listOf(history("remote")), existing, 100L)
        assertEquals(listOf("remote"), merged.map { it.query })
    }

    @Test
    fun `duplicate terms collapse case-insensitively`() {
        val merged = mergeSearchHistory(
            listOf(history("Adele"), history("adele"), history("Weeknd")),
            emptyList(),
            100L,
        )
        assertEquals(listOf("Adele", "Weeknd"), merged.map { it.query })
    }

    @Test
    fun `blank and single-character terms are skipped`() {
        val merged = mergeSearchHistory(
            listOf(history(""), history("   "), history("a"), history("ab")),
            emptyList(),
            100L,
        )
        assertEquals(listOf("ab"), merged.map { it.query })
    }

    @Test
    fun `blocked terms are not imported while a delete settles`() {
        val merged = mergeSearchHistory(
            listOf(history("adele"), history("weeknd")),
            emptyList(),
            100L,
            blocked = setOf("ADELE"),
        )
        assertEquals(listOf("weeknd"), merged.map { it.query })
    }

    @Test
    fun `a term searched elsewhere is surfaced above the locally newest one`() {
        val existing = listOf(row("yy", 2_000L), row("xx", 1_000L))
        val merged = mergeSearchHistory(listOf(history("xx"), history("yy")), existing, nowMs = 9_000L)
        assertEquals(listOf("xx", "yy"), merged.map { it.query })
        assertTrue(merged[0].searchedAt > merged[1].searchedAt)
        assertEquals(2_000L, merged[1].searchedAt)
    }

    @Test
    fun `a repeat sync does not drift timestamps`() {
        val first = mergeSearchHistory(
            listOf(history("adele"), history("weeknd")),
            emptyList(),
            1_000L,
        )
        val second = mergeSearchHistory(
            listOf(history("adele"), history("weeknd")),
            first,
            5_000L,
        )
        assertEquals(first.map { it.searchedAt }, second.map { it.searchedAt })
    }

    @Test
    fun `an unchanged page writes nothing`() = runBlocking {
        val existing = listOf(row("adele", 1_000L), row("weeknd", 999L))
        val dao = RecordingDao(existing)
        val sync = YouTubeSearchHistorySync(dao) {
            YouTubeLoadResult.Success(listOf(history("adele"), history("weeknd")))
        }
        val result = sync.sync(nowMs = 9_000L)
        assertEquals(0, result.merged)
        assertTrue(dao.written.isEmpty())
    }

    @Test
    fun `a new term is written once and reported`() = runBlocking {
        val dao = RecordingDao(listOf(row("adele", 1_000L)))
        val sync = YouTubeSearchHistorySync(dao) {
            YouTubeLoadResult.Success(listOf(history("weeknd"), history("adele")))
        }
        val result = sync.sync(nowMs = 9_000L)
        assertEquals(listOf("weeknd"), dao.written.map { it.query })
        assertEquals(1, result.merged)
    }

    /** Minimal in-memory DAO: only the read/write the sync uses is implemented. */
    private class RecordingDao(private val rows: List<SearchHistoryEntity>) : SearchHistoryDao {
        val written = mutableListOf<SearchHistoryEntity>()
        override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>> = emptyFlow()
        override suspend fun getAll(): List<SearchHistoryEntity> = rows
        override suspend fun upsert(entry: SearchHistoryEntity) { written += entry }
        override suspend fun upsertAll(entries: List<SearchHistoryEntity>) { written += entries }
        override suspend fun delete(query: String) = Unit
        override suspend fun trim(limit: Int) = Unit
        override suspend fun clear() = Unit
    }
}
