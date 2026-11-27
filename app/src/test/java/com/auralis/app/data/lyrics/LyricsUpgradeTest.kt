package com.auralis.app.data.lyrics

import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.LyricsSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staged lyrics lookup only ever upgrades what is on screen: PLAIN -> LINE -> WORD. */
class LyricsUpgradeTest {

    private val text = "first line of the song\nsecond line of the song\nthird line of the song\nfourth line of the song"

    private fun lyrics(sync: LyricsSync, score: Int, raw: String = text) =
        Lyrics(LyricsSource.LRCLIB, raw, null, null, provider = "Test", sync = sync, matchScore = score)

    @Test
    fun `nothing shown yet accepts anything`() {
        assertTrue(shouldUpgradeLyrics(null, lyrics(LyricsSync.PLAIN, 60)))
    }

    @Test
    fun `line to word is an upgrade`() {
        assertTrue(shouldUpgradeLyrics(lyrics(LyricsSync.LINE, 76), lyrics(LyricsSync.WORD, 76)))
    }

    @Test
    fun `plain to line and plain to word are upgrades`() {
        assertTrue(shouldUpgradeLyrics(lyrics(LyricsSync.PLAIN, 76), lyrics(LyricsSync.LINE, 76)))
        assertTrue(shouldUpgradeLyrics(lyrics(LyricsSync.PLAIN, 76), lyrics(LyricsSync.WORD, 76)))
    }

    @Test
    fun `word to line and equal tiers never swap`() {
        assertFalse(shouldUpgradeLyrics(lyrics(LyricsSync.WORD, 76), lyrics(LyricsSync.LINE, 98)))
        assertFalse(shouldUpgradeLyrics(lyrics(LyricsSync.WORD, 76), lyrics(LyricsSync.WORD, 98)))
        assertFalse(shouldUpgradeLyrics(lyrics(LyricsSync.LINE, 76), lyrics(LyricsSync.LINE, 98)))
    }

    @Test
    fun `a lower scored upgrade must match the text already shown`() {
        val shown = lyrics(LyricsSync.LINE, 90)
        val sameSong = lyrics(LyricsSync.WORD, 76)
        val otherSong = lyrics(
            LyricsSync.WORD, 76,
            raw = "completely different words here\nnothing in common at all\nanother unrelated verse\nyet one more strange line",
        )
        assertTrue(shouldUpgradeLyrics(shown, sameSong))
        assertFalse(shouldUpgradeLyrics(shown, otherSong))
    }
}
