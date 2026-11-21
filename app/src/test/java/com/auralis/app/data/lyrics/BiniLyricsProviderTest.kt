package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture tests for BiniLyrics: result selection, the SSRF host gate and TTML conversion. */
class BiniLyricsProviderTest {

    @Test
    fun `only the matching recording is picked`() {
        val body = """
            {"results":[
              {"track_name":"Shape of You","artist_name":"Someone Else","duration":234,"lyricsUrl":"https://lrc.red/s/WRONG.ttml"},
              {"track_name":"Shape of You","artist_name":"Ed Sheeran","duration":234,"lyricsUrl":"https://lrc.red/s/GBAHS1600463.ttml"}
            ]}
        """.trimIndent()
        assertEquals(
            "https://lrc.red/s/GBAHS1600463.ttml",
            pickBiniLyricsUrl(body, "Shape of You", "Ed Sheeran", 233_000),
        )
    }

    @Test
    fun `closest duration wins`() {
        val body = """
            {"results":[
              {"track_name":"Song","artist_name":"Artist","duration":235,"lyricsUrl":"https://lrc.red/s/A.ttml"},
              {"track_name":"Song","artist_name":"Artist","duration":233,"lyricsUrl":"https://lrc.red/s/B.ttml"}
            ]}
        """.trimIndent()
        assertEquals("https://lrc.red/s/B.ttml", pickBiniLyricsUrl(body, "Song", "Artist", 233_000))
    }

    @Test
    fun `untrusted or insecure lyrics urls are rejected`() {
        val body = """
            {"results":[
              {"track_name":"Song","artist_name":"Artist","duration":233,"lyricsUrl":"https://evil.example/s/A.ttml"},
              {"track_name":"Song","artist_name":"Artist","duration":233,"lyricsUrl":"http://lrc.red/s/A.ttml"}
            ]}
        """.trimIndent()
        assertNull(pickBiniLyricsUrl(body, "Song", "Artist", 233_000))
    }

    @Test
    fun `empty malformed and no-match bodies yield null`() {
        assertNull(pickBiniLyricsUrl(null, "Song", "Artist", 233_000))
        assertNull(pickBiniLyricsUrl("", "Song", "Artist", 233_000))
        assertNull(pickBiniLyricsUrl("not json", "Song", "Artist", 233_000))
        assertNull(pickBiniLyricsUrl("""{"results":[]}""", "Song", "Artist", 233_000))
        assertNull(
            pickBiniLyricsUrl(
                """{"results":[{"track_name":"Other","artist_name":"Nobody","duration":100,"lyricsUrl":"https://lrc.red/s/X.ttml"}]}""",
                "Song",
                "Artist",
                233_000,
            ),
        )
    }

    @Test
    fun `host gate allows only the two lyrics hosts over https`() {
        assertTrue(biniLyricsUrlAllowed("https://lrc.red/s/GBAHS1600463.ttml"))
        assertTrue(biniLyricsUrlAllowed("https://lyrics-api.binimum.org/?track=x"))
        assertFalse(biniLyricsUrlAllowed("http://lrc.red/s/X.ttml"))
        assertFalse(biniLyricsUrlAllowed("https://evil.example/s/X.ttml"))
        assertFalse(biniLyricsUrlAllowed("not a url"))
        assertFalse(biniLyricsUrlAllowed(null))
        assertFalse(biniLyricsUrlAllowed(""))
    }

    @Test
    fun `the fetched document converts to word-synced lrc`() {
        val ttml = """<tt><body><p begin="9.731"><span begin="9.731">The</span> <span begin="9.927">club</span></p></body></tt>"""
        assertEquals("[00:09.731]<00:09.731>The <00:09.927>club", TtmlParser.parse(ttml))
    }
}
