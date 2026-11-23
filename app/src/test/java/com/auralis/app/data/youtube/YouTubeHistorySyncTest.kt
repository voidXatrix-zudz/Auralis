package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeRecentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetched `FEmusic_history` page is merged into the local mirror without ever losing a local
 * play count, reordering a play that just happened in the app, or leaving a placeholder row without
 * metadata.
 */
class YouTubeHistorySyncTest {

    private fun song(id: String) = YtSong(
        videoId = id,
        title = "Song $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 180_000L,
        thumbnailUrl = "https://art/$id.jpg",
    )

    private fun row(id: String, playedAt: Long, playCount: Int) = YouTubeRecentEntity(
        videoId = id,
        title = "Old $id",
        artist = "Old artist",
        album = "",
        durationMs = 0L,
        thumbnailUrl = null,
        playedAt = playedAt,
        playCount = playCount,
    )

    @Test
    fun `fetched order becomes playedAt order, newest first`() {
        val now = 1_000_000L
        val merged = mergeHistory(listOf(song("a"), song("b"), song("c")), emptyMap(), now)
        assertEquals(listOf("a", "b", "c"), merged.map { it.videoId })
        assertTrue(merged[0].playedAt > merged[1].playedAt)
        assertTrue(merged[1].playedAt > merged[2].playedAt)
        assertTrue(merged.all { it.playCount == 1 })
    }

    @Test
    fun `an imported song keeps the local play count and metadata`() {
        val existing = mapOf("a" to row("a", playedAt = 10L, playCount = 7))
        val merged = mergeHistory(listOf(song("a")), existing, 5_000L)
        assertEquals(7, merged.single().playCount)
        assertEquals("Song a", merged.single().title)
    }

    @Test
    fun `an existing row keeps its timestamp across repeated syncs`() {
        val first = mergeHistory(listOf(song("a"), song("b")), emptyMap(), 1_000L)
        val existing = first.associateBy { it.videoId }
        val second = mergeHistory(listOf(song("a"), song("b")), existing, 5_000L)
        assertEquals(first.map { it.playedAt }, second.map { it.playedAt })
    }

    @Test
    fun `a local play that just happened is never moved backwards`() {
        val existing = mapOf("a" to row("a", playedAt = 9_999_999L, playCount = 2))
        val merged = mergeHistory(listOf(song("a")), existing, 1_000L)
        assertEquals(9_999_999L, merged.single().playedAt)
    }

    @Test
    fun `duplicate video ids in one page collapse to the first`() {
        val merged = mergeHistory(listOf(song("a"), song("a"), song("b")), emptyMap(), 100L)
        assertEquals(listOf("a", "b"), merged.map { it.videoId })
    }

    @Test
    fun `blank video ids are skipped`() {
        val merged = mergeHistory(
            listOf(song(""), song("  "), song("real")),
            emptyMap(),
            100L,
        )
        assertEquals(listOf("real"), merged.map { it.videoId })
    }

    @Test
    fun `local-only rows are not returned, so the upsert leaves them untouched`() {
        val existing = mapOf("local-only" to row("local-only", 50L, 3))
        val merged = mergeHistory(listOf(song("remote")), existing, 100L)
        assertEquals(listOf("remote"), merged.map { it.videoId })
    }

    @Test
    fun `missing fetched fields fall back to the existing row`() {
        val existing = mapOf(
            "a" to YouTubeRecentEntity(
                videoId = "a",
                title = "Known title",
                artist = "Known artist",
                album = "Known album",
                durationMs = 200_000L,
                thumbnailUrl = "https://known.jpg",
                playedAt = 1L,
                playCount = 4,
            ),
        )
        val merged = mergeHistory(
            listOf(YtSong("a", "", "", "", 0L, null)),
            existing,
            100L,
        ).single()
        assertEquals("Known title", merged.title)
        assertEquals("Known artist", merged.artist)
        assertEquals("Known album", merged.album)
        assertEquals(200_000L, merged.durationMs)
        assertEquals("https://known.jpg", merged.thumbnailUrl)
    }

    @Test
    fun `a song played elsewhere is surfaced above the locally newest row`() {
        // Locally Y was played most recently; the page now ranks X (played on another device) above it.
        val existing = mapOf(
            "y" to row("y", playedAt = 2_000L, playCount = 4),
            "x" to row("x", playedAt = 1_000L, playCount = 2),
        )
        val merged = mergeHistory(listOf(song("x"), song("y")), existing, nowMs = 9_000L)
        assertEquals(listOf("x", "y"), merged.map { it.videoId })
        assertTrue(merged[0].playedAt > merged[1].playedAt)
        assertEquals(2_000L, merged[1].playedAt)
    }

    @Test
    fun `surfacing a song elsewhere keeps every play count`() {
        val existing = mapOf(
            "y" to row("y", playedAt = 2_000L, playCount = 4),
            "x" to row("x", playedAt = 1_000L, playCount = 2),
        )
        val merged = mergeHistory(listOf(song("x"), song("y")), existing, nowMs = 9_000L)
        assertEquals(listOf(2, 4), merged.map { it.playCount })
    }

    @Test
    fun `a surfaced row does not drift on the next sync`() {
        val existing = mapOf(
            "x" to row("x", playedAt = 1_000L, playCount = 2),
            "y" to row("y", playedAt = 2_000L, playCount = 4),
        )
        val first = mergeHistory(listOf(song("x"), song("y")), existing, nowMs = 9_000L)
        val second = mergeHistory(listOf(song("x"), song("y")), first.associateBy { it.videoId }, 20_000L)
        assertEquals(first.map { it.playedAt }, second.map { it.playedAt })
    }

    @Test
    fun `metadata targets are the rows still titled with their video id, capped`() {
        val existing = listOf(
            row("a", 1L, 1).copy(title = "a"),
            row("b", 2L, 1).copy(title = "Real b"),
            row("c", 3L, 1).copy(title = ""),
        )
        assertEquals(listOf("a"), metadataTargets(existing, emptyList(), limit = 1).map { it.videoId })
    }

    @Test
    fun `a row the merge just named is not a metadata target`() {
        val existing = listOf(row("a", 1L, 1).copy(title = "a"))
        val merged = listOf(row("a", 1L, 1).copy(title = "Real a"))
        assertTrue(metadataTargets(existing, merged, limit = 10).isEmpty())
    }

    @Test
    fun `withMetadata fills a placeholder without lowering known values`() {
        val placeholder = row("a", 1L, 1).copy(title = "a", artist = "known artist")
        val filled = placeholder.withMetadata(
            YtSong("a", "Real title", "", "", 200_000L, "https://art/a.jpg"),
        )
        assertEquals("Real title", filled.title)
        assertEquals("known artist", filled.artist)
        assertEquals(200_000L, filled.durationMs)
        assertTrue(filled.thumbnailUrl!!.startsWith("https://"))
    }
}