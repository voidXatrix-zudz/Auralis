package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixture tests for the LRCLIB response parsing: the empty/malformed/no-match cases that must
 * degrade to "no lyrics" instead of throwing, plus the search ranking.
 */
class LrclibParserTest {

    // --- /api/get -----------------------------------------------------------------------------

    @Test
    fun `get with synced and plain lyrics is parsed and trimmed`() {
        val body = """
            {"id":1250,"trackName":"Shape of You","artistName":"Ed Sheeran","duration":234.0,
             "syncedLyrics":"  [00:00.00]The club isn't the best place  ","plainLyrics":"The club isn't the best place"}
        """.trimIndent()
        val found = LrclibParser.parseGetBody(body)
        assertEquals("[00:00.00]The club isn't the best place", found?.synced)
        assertEquals("The club isn't the best place", found?.plain)
    }

    @Test
    fun `get with only plain lyrics keeps synced null`() {
        val found = LrclibParser.parseGetBody("""{"plainLyrics":"Just words here"}""")
        assertNull(found?.synced)
        assertEquals("Just words here", found?.plain)
    }

    @Test
    fun `empty body yields null`() {
        assertNull(LrclibParser.parseGetBody(null))
        assertNull(LrclibParser.parseGetBody(""))
        assertNull(LrclibParser.parseGetBody("   "))
    }

    @Test
    fun `malformed body yields null instead of throwing`() {
        assertNull(LrclibParser.parseGetBody("not json at all"))
        assertNull(LrclibParser.parseGetBody("{ truncated"))
        assertNull(LrclibParser.parseGetBody("[1,2,3]"))
    }

    @Test
    fun `get with neither lyric field yields null`() {
        assertNull(LrclibParser.parseGetBody("""{"id":1,"trackName":"X"}"""))
        assertNull(LrclibParser.parseGetBody("""{"syncedLyrics":"","plainLyrics":""}"""))
    }

    // --- /api/search ---------------------------------------------------------------------------

    @Test
    fun `search picks the closest duration and prefers a synced hit`() {
        val body = """
            [
              {"trackName":"Song","duration":300,"plainLyrics":"plain far"},
              {"trackName":"Song","duration":234,"plainLyrics":"plain close"},
              {"trackName":"Song","duration":235,"syncedLyrics":"[00:00.00]synced close"}
            ]
        """.trimIndent()
        val result = LrclibParser.parseSearchBody(body, "Song", 234)
        val found = result as LrclibResult.Found
        // The 235 s hit carries timestamps and is closest, so it wins over the 234 s plain one.
        assertEquals("[00:00.00]synced close", found.synced)
    }

    @Test
    fun `search prefers the closer duration when both are synced`() {
        val body = """
            [
              {"trackName":"Song","duration":300,"syncedLyrics":"far"},
              {"trackName":"Song","duration":236,"syncedLyrics":"near"}
            ]
        """.trimIndent()
        val found = LrclibParser.parseSearchBody(body, "Song", 234) as LrclibResult.Found
        assertEquals("near", found.synced)
    }

    @Test
    fun `search empty malformed or all-empty yields NotFound`() {
        assertEquals(LrclibResult.NotFound, LrclibParser.parseSearchBody(null, "Song", 234))
        assertEquals(LrclibResult.NotFound, LrclibParser.parseSearchBody("", "Song", 234))
        assertEquals(LrclibResult.NotFound, LrclibParser.parseSearchBody("{ not an array", "Song", 234))
        assertEquals(LrclibResult.NotFound, LrclibParser.parseSearchBody("[]", "Song", 234))
        assertEquals(
            LrclibResult.NotFound,
            LrclibParser.parseSearchBody("""[{"trackName":"Song","duration":234}]""", "Song", 234),
        )
    }
}
