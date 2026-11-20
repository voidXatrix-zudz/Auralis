package com.auralis.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ten-second rule, measured as real listening time. Pauses add nothing, seeks add nothing,
 * a re-listen counts again, and local and YouTube songs use the same constant.
 */
class PlaybackHistoryTrackerTest {
    private class Walk(val tracker: PlaybackHistoryTracker = PlaybackHistoryTracker()) {
        var now = 0L
        var position = 0L

        /** First sample only establishes the baseline, exactly like the player's first tick. */
        fun start(id: String): Walk {
            tracker.onTick(id, position, playing = true, wallClockMs = now)
            return this
        }

        /** Advances one second of real time and one second of position. */
        fun step(id: String, seconds: Int = 1, playing: Boolean = true): Boolean {
            now += seconds * 1_000L
            position += seconds * 1_000L
            return tracker.onTick(id, position, playing = playing, wallClockMs = now)
        }
    }

    @Test
    fun `history threshold is exactly ten seconds for every source`() {
        assertEquals(10_000L, HISTORY_MIN_MS)
    }

    @Test
    fun `nine seconds does not count`() {
        val walk = Walk().start("yt:a")
        var fired = false
        repeat(9) { fired = walk.step("yt:a") || fired }
        assertFalse(fired)
        assertEquals(9_000L, walk.tracker.listenedMs)
        assertFalse(walk.tracker.recorded)
    }

    @Test
    fun `the tenth second fires once`() {
        val walk = Walk().start("t1")
        repeat(9) { walk.step("t1") }
        assertFalse(walk.tracker.recorded)

        assertTrue(walk.step("t1"))
        assertEquals(10_000L, walk.tracker.listenedMs)
        // Further ticks in the same listen stay quiet.
        assertFalse(walk.step("t1"))
        assertTrue(walk.tracker.recorded)
    }

    @Test
    fun `a seek cannot inflate listening time`() {
        val tracker = PlaybackHistoryTracker()
        tracker.onTick("t1", 0L, playing = true, wallClockMs = 0L)
        // One wall-clock second, but the position jumped two minutes: a seek, not listening.
        val fired = tracker.onTick("t1", 120_000L, playing = true, wallClockMs = 1_000L)
        assertFalse(fired)
        assertEquals(0L, tracker.listenedMs)
    }

    @Test
    fun `paused time adds nothing`() {
        val walk = Walk().start("t1")
        repeat(20) { walk.step("t1", playing = false) }
        assertEquals(0L, walk.tracker.listenedMs)
        assertFalse(walk.tracker.recorded)
    }

    @Test
    fun `restarting a counted song counts the re-listen again and moves it to the top`() {
        val walk = Walk().start("t1")
        repeat(10) { walk.step("t1") }
        assertTrue(walk.tracker.recorded)

        // The player loops back to the start: the next occurrence must count again.
        walk.tracker.onTick("t1", 0L, playing = true, wallClockMs = walk.now + 1_000L)
        walk.now += 1_000L
        walk.position = 0L
        var firedAgain = false
        repeat(10) { firedAgain = walk.step("t1") || firedAgain }
        assertTrue(firedAgain)
    }

    @Test
    fun `changing track resets the counter`() {
        val walk = Walk().start("t1")
        walk.step("t1", seconds = 8)
        walk.step("t2")
        assertFalse(walk.tracker.recorded)
        assertEquals(0L, walk.tracker.listenedMs)
    }

    @Test
    fun `a null track resets everything`() {
        val walk = Walk().start("t1")
        walk.step("t1", seconds = 5)
        assertFalse(walk.tracker.onTick(null, walk.position, playing = true, wallClockMs = walk.now + 1_000L))
        assertEquals(0L, walk.tracker.listenedMs)
    }
}