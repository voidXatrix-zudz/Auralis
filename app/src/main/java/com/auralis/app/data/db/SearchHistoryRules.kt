package com.auralis.app.data.db

/**
 * Pure rules for the Recent-searches list, shared by the ViewModel and its unit tests.
 *
 * The Room table stores the literal query as its primary key, so the case-insensitive part of the
 * rule (trim, de-duplicate, move the newest casing to the top, cap at 20) lives here instead of in
 * SQL where it cannot be tested without a device.
 */
object SearchHistoryRules {
    /** A query worth remembering: trimmed and at least two characters, otherwise null. */
    fun normalize(query: String): String? = query.trim().takeIf { it.length >= 2 }

    /** New list with [query] first, any case-variant removed, capped at [limit]. */
    fun merge(existing: List<String>, query: String, limit: Int = 20): List<String> {
        val normalized = normalize(query) ?: return existing
        return (listOf(normalized) + existing.filterNot { it.equals(normalized, ignoreCase = true) })
            .take(limit)
    }

    /** New list without any casing of [query]. */
    fun delete(existing: List<String>, query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return existing
        return existing.filterNot { it.equals(normalized, ignoreCase = true) }
    }
}