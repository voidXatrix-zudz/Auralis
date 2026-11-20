package com.auralis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1: successful per-song thumbnail lines are gone — one summary line per request, and a line only
 * when a thumbnail actually failed.
 */
class RequestSummaryTest {
    @Test
    fun `valid thumbnails produce no per-song lines and one search summary`() {
        val tally = ThumbnailTally()
        repeat(20) { index ->
            assertNull(tally.add("Search", "video$index", "https://i.ytimg.com/vi/$index/hq.jpg"))
        }

        assertEquals("Search: 20 songs, thumbnails OK 20/20", requestSummary("Search", tally))
    }

    @Test
    fun `only failed thumbnails get a line and the summary counts them`() {
        val tally = ThumbnailTally()
        val failures = buildList {
            repeat(50) { index ->
                // Two of fifty have no usable artwork.
                val url = if (index == 3 || index == 17) null else "https://i.ytimg.com/vi/$index/hq.jpg"
                tally.add("Radio", "track$index", url)?.let(::add)
            }
        }

        assertEquals(2, failures.size)
        assertEquals(48, tally.ok)
        assertEquals(2, tally.failed)
        assertEquals(
            "Radio: 50 parsed, 2 hidden, thumbnails OK 48/50, 2 failed",
            requestSummary("Radio", tally, noun = "parsed", hidden = 2),
        )
    }

    @Test
    fun `a blank URL is a failure with its own reason`() {
        val tally = ThumbnailTally()

        val line = tally.add("Search", "video", " ")

        assertEquals("Search: thumbnail missing for [video] (empty)", line)
        assertEquals(1, tally.failed)
    }
}