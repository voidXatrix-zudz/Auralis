package com.auralis.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recent searches: trim, minimum length, case-insensitive dedupe, newest first, max 20, delete. */
class SearchHistoryRulesTest {
    @Test
    fun `short or blank queries are not remembered`() {
        assertNull(SearchHistoryRules.normalize("  a  "))
        assertNull(SearchHistoryRules.normalize("   "))
        assertEquals("ab", SearchHistoryRules.normalize("  ab  "))
    }

    @Test
    fun `a repeat with different casing moves to the top without duplicating`() {
        val existing = listOf("Weeknd", "Radiohead", "Beyoncé")
        val merged = SearchHistoryRules.merge(existing, "weeknd")
        assertEquals(listOf("weeknd", "Radiohead", "Beyoncé"), merged)
        assertEquals(3, merged.size)
    }

    @Test
    fun `the newest twenty survive`() {
        val existing = (1..25).map { "query $it" }
        val merged = SearchHistoryRules.merge(existing, "newest")
        assertEquals(20, merged.size)
        assertEquals("newest", merged.first())
        assertEquals("query 19", merged.last())
    }

    @Test
    fun `delete removes every casing of one query`() {
        val existing = listOf("Weeknd", "Radiohead", "weeknd live")
        assertEquals(listOf("Radiohead", "weeknd live"), SearchHistoryRules.delete(existing, "WEEKND"))
    }

    @Test
    fun `clear is an empty list and a new search starts from it`() {
        val cleared = emptyList<String>()
        assertEquals(listOf("abbey road"), SearchHistoryRules.merge(cleared, "abbey road"))
    }
}