package com.auralis.app.data.lyrics

import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.LrcWord
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.LyricsSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ranking tests for choosing between providers that answer for the same song. */
class LyricsQualityTest {

    private fun lyrics(sync: LyricsSync, provider: String, confidence: Double): Lyrics = Lyrics(
        source = LyricsSource.LRCLIB,
        raw = "raw",
        synced = when (sync) {
            LyricsSync.PLAIN -> null
            LyricsSync.LINE -> listOf(LrcLine(0L, "a line"))
            LyricsSync.WORD -> listOf(LrcLine(0L, "a line", words = listOf(LrcWord(0L, "a", 100L), LrcWord(100L, "line", 400L))))
        },
        plain = if (sync == LyricsSync.PLAIN) "a line" else null,
        provider = provider,
        confidence = confidence,
        sync = sync,
    )

    @Test
    fun `word-synced beats line-synced even when the line result is more confident`() {
        val word = lyrics(LyricsSync.WORD, "BiniLyrics", 0.70)
        val line = lyrics(LyricsSync.LINE, "LRCLIB", 0.99)
        assertTrue(isBetterLyrics(word, line))
        assertFalse(isBetterLyrics(line, word))
    }

    @Test
    fun `line-synced beats plain text`() {
        val line = lyrics(LyricsSync.LINE, "LRCLIB", 0.60)
        val plain = lyrics(LyricsSync.PLAIN, "LyricsOvh", 0.95)
        assertTrue(isBetterLyrics(line, plain))
        assertFalse(isBetterLyrics(plain, line))
    }

    @Test
    fun `confidence breaks ties within the same sync level`() {
        val strong = lyrics(LyricsSync.WORD, "BiniLyrics", 0.9)
        val weak = lyrics(LyricsSync.WORD, "LyricsPlus", 0.5)
        assertTrue(isBetterLyrics(strong, weak))
        assertFalse(isBetterLyrics(weak, strong))
    }

    @Test
    fun `an out-of-sync result loses the tie`() {
        val clean = lyrics(LyricsSync.LINE, "LRCLIB", 0.8)
        val slowed = clean.copy(outOfSync = true)
        assertTrue(isBetterLyrics(clean, slowed))
    }

    @Test
    fun `when prefer-synced is off, confidence alone decides`() {
        val word = lyrics(LyricsSync.WORD, "BiniLyrics", 0.50)
        val line = lyrics(LyricsSync.LINE, "LRCLIB", 0.80)
        assertTrue(isBetterLyrics(line, word, preferSynced = false))
        assertFalse(isBetterLyrics(word, line, preferSynced = false))
    }
}
