package com.auralis.app.ui.lyrics

import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.LrcWord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure sync helpers: mapping word timings onto the display text (spaced LRC, no-space
 * KRC/CJK, trailing spaces, blank words) and the playback-speed estimate used by the smooth clock.
 */
class KaraokeLyricsTest {

    private fun line(text: String, vararg words: String): LrcLine = LrcLine(
        timeMs = 0L,
        text = text,
        words = words.mapIndexed { index, word -> LrcWord(index * 500L, word) },
    )

    @Test
    fun `spaced words map to their character ranges`() {
        val ranges = wordRanges(line("Hello big world", "Hello", "big", "world"))!!
        assertArrayEquals(intArrayOf(0, 6, 10), ranges.starts)
        assertArrayEquals(intArrayOf(5, 9, 15), ranges.ends)
    }

    @Test
    fun `no-space words map one character at a time`() {
        val ranges = wordRanges(line("你好世界", "你", "好", "世", "界"))!!
        assertArrayEquals(intArrayOf(0, 1, 2, 3), ranges.starts)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), ranges.ends)
    }

    @Test
    fun `trailing spaces after the last word are allowed`() {
        val ranges = wordRanges(line("Hello world  ", "Hello", "world"))!!
        assertArrayEquals(intArrayOf(0, 6), ranges.starts)
        assertArrayEquals(intArrayOf(5, 11), ranges.ends)
    }

    @Test
    fun `blank words keep a zero-length range`() {
        val ranges = wordRanges(line("Hello world", "Hello", "", "world"))!!
        assertArrayEquals(intArrayOf(0, 6, 6), ranges.starts)
        assertArrayEquals(intArrayOf(5, 6, 11), ranges.ends)
    }

    @Test
    fun `words that do not reconstruct the text fall back`() {
        assertNull(wordRanges(line("Hello world", "Hello", "there")))
    }

    @Test
    fun `empty words or blank text cannot map`() {
        assertNull(wordRanges(LrcLine(0L, "Hello", emptyList())))
        assertNull(wordRanges(LrcLine(0L, "   ", listOf(LrcWord(0L, "x")))))
    }

    @Test
    fun `speed estimate follows normal playback rates`() {
        assertEquals(1f, estimatePlaybackSpeed(1_000, 1_000, 1_500, 1_500, true, true), 0.001f)
        assertEquals(2f, estimatePlaybackSpeed(1_000, 1_000, 2_000, 1_500, true, true), 0.001f)
        assertEquals(0.5f, estimatePlaybackSpeed(1_000, 1_000, 1_250, 1_500, true, true), 0.001f)
    }

    @Test
    fun `speed estimate rejects pause, seek and implausible intervals`() {
        // Resuming from pause.
        assertEquals(1f, estimatePlaybackSpeed(1_000, 1_000, 1_500, 1_500, false, true), 0.001f)
        // Seeking backwards.
        assertEquals(1f, estimatePlaybackSpeed(5_000, 1_000, 1_000, 1_500, true, true), 0.001f)
        // Implausibly fast (8x).
        assertEquals(1f, estimatePlaybackSpeed(1_000, 1_000, 5_000, 1_500, true, true), 0.001f)
        // First sample.
        assertEquals(1f, estimatePlaybackSpeed(-1, 0, 1_500, 1_500, true, true), 0.001f)
    }
}
