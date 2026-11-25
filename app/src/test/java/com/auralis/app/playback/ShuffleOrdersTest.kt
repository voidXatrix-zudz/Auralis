package com.auralis.app.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleOrdersTest {

    @Test
    fun emptyQueueGivesAnEmptyOrder() {
        assertEquals(0, anchoredShuffle(0, 0, 1L).size)
    }

    @Test
    fun singleSongStaysFirst() {
        assertArrayEquals(intArrayOf(0), anchoredShuffle(1, 0, 42L))
    }

    @Test
    fun twoSongsPutTheCurrentFirst() {
        assertArrayEquals(intArrayOf(1, 0), anchoredShuffle(2, 1, 7L))
        assertArrayEquals(intArrayOf(0, 1), anchoredShuffle(2, 0, 7L))
    }

    @Test
    fun everyIndexAppearsExactlyOnce() {
        val count = 60
        for (current in listOf(0, 1, count / 2, count - 1)) {
            val order = anchoredShuffle(count, current, 987654321L)
            assertEquals(count, order.size)
            assertEquals("current song must be first", current, order[0])
            assertEquals("no duplicate or missing song", (0 until count).toSet(), order.toSet())
        }
    }

    @Test
    fun currentIndexOutsideTheQueueFallsBackToTheFirstSong() {
        assertArrayEquals(anchoredShuffle(3, 0, 5L), anchoredShuffle(3, -3, 5L))
        assertArrayEquals(anchoredShuffle(3, 2, 5L), anchoredShuffle(3, 99, 5L))
    }

    @Test
    fun differentSeedsGiveDifferentOrders() {
        val first = anchoredShuffle(12, 4, 1L)
        val second = anchoredShuffle(12, 4, 2L)
        assertTrue("a new shuffle must not replay the previous order", !first.contentEquals(second))
    }

    @Test
    fun sameSeedGivesTheSameOrder() {
        assertArrayEquals(anchoredShuffle(9, 3, 123456789L), anchoredShuffle(9, 3, 123456789L))
    }
}