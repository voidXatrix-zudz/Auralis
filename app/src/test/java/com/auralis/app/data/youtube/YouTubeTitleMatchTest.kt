package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Artist/title heuristics for the InnerTube `player` response (see [artistAndTitle]). */
class YouTubeTitleMatchTest {

    @Test
    fun `a Topic channel supplies the real artist`() {
        assertEquals(
            "Arijit Singh" to "Kesariya",
            artistAndTitle("Kesariya", "Arijit Singh - Topic"),
        )
    }

    @Test
    fun `a VEVO channel supplies the real artist`() {
        assertEquals("TaylorSwift" to "Anti-Hero", artistAndTitle("Anti-Hero", "TaylorSwiftVEVO"))
    }

    @Test
    fun `a label channel lets an Artist - Song title be split`() {
        assertEquals(
            "Pritam" to "Kesariya",
            artistAndTitle("Pritam - Kesariya", "Sony Music India"),
        )
    }

    @Test
    fun `an artist's own channel is trusted over a title split`() {
        assertEquals(
            "Some Person" to "Artist - Song",
            artistAndTitle("Artist - Song", "Some Person"),
        )
    }

    @Test
    fun `a remaster marker is not mistaken for an artist split`() {
        assertEquals("" to "Let It Be - Remastered 2009", artistAndTitle("Let It Be - Remastered 2009", ""))
    }

    @Test
    fun `a plain title keeps its channel as the artist`() {
        assertEquals("Some Channel" to "Just A Song", artistAndTitle("Just A Song", "Some Channel"))
        assertEquals("" to "Just A Song", artistAndTitle("Just A Song", ""))
    }

    @Test
    fun `the split rejects edit-noise right halves and long left halves`() {
        assertNull(splitArtistTitle("Just A Song"))
        assertNull(splitArtistTitle("Song - Live"))
        assertNull(splitArtistTitle("Song - Official Video"))
        assertNull(splitArtistTitle("Song - Remastered 2011"))
        assertNull(splitArtistTitle(" - Song"))
        assertNull(splitArtistTitle("A B C D E F - Song"))
        assertEquals("Arijit Singh" to "Kesariya", splitArtistTitle("Arijit Singh - Kesariya"))
    }

    @Test
    fun `label detection recognises aggregators`() {
        assertTrue(looksLikeLabel("Sony Music India"))
        assertTrue(looksLikeLabel("T-Series Official"))
        assertFalse(looksLikeLabel("Arijit Singh"))
    }

    @Test
    fun `usable metadata needs a real title`() {
        val song = YtSong("id", "Song", "Artist", "", 0L, null)
        assertTrue(isUsableMetadata(song, "id"))
        assertFalse(isUsableMetadata(song.copy(title = ""), "id"))
        assertFalse(isUsableMetadata(song.copy(title = "id"), "id"))
        assertFalse(isUsableMetadata(null, "id"))
    }
}