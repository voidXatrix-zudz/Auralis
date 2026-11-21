package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the KuGou response handling: search parsing/ranking, the empty/malformed/
 * no-match cases, and the credit/instrumental cleanup of the downloaded LRC.
 */
class KuGouProviderTest {

    @Test
    fun `search keeps the matching song closest in duration`() {
        val body = """
            {"status":1,"data":{"info":[
              {"hash":"AAA","songname":"Shape of You","singername":"Ed Sheeran","duration":260},
              {"hash":"BBB","songname":"Another Song","singername":"Ed Sheeran","duration":233},
              {"hash":"CCC","songname":"Shape of You","singername":"Taylor Swift","duration":233},
              {"hash":"DDD","songname":"Shape of You","singername":"Ed Sheeran","duration":233}
            ]}}
        """.trimIndent()
        val songs = kugouCandidates(body, "Shape of You", "Ed Sheeran", 233_000)
        assertEquals(listOf("DDD"), songs.map { it.hash })
    }

    @Test
    fun `search sorts by duration distance`() {
        val body = """
            {"data":{"info":[
              {"hash":"FAR","songname":"Song","singername":"Artist","duration":236},
              {"hash":"NEAR","songname":"Song","singername":"Artist","duration":234}
            ]}}
        """.trimIndent()
        assertEquals(listOf("NEAR", "FAR"), kugouCandidates(body, "Song", "Artist", 233_000).map { it.hash })
    }

    @Test
    fun `search empty malformed or no-match yields no candidates`() {
        assertTrue(kugouCandidates(null, "Song", "Artist", 233_000).isEmpty())
        assertTrue(kugouCandidates("", "Song", "Artist", 233_000).isEmpty())
        assertTrue(kugouCandidates("not json", "Song", "Artist", 233_000).isEmpty())
        assertTrue(kugouCandidates("""{"data":{"info":[]}}""", "Song", "Artist", 233_000).isEmpty())
        assertTrue(
            kugouCandidates(
                """{"data":{"info":[{"hash":"X","songname":"Other","singername":"Nobody","duration":100}]}}""",
                "Song",
                "Artist",
                233_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `candidate and content parsing handle valid and missing payloads`() {
        assertEquals("1" to "KEY", parseKugouCandidate("""{"candidates":[{"id":"1","accesskey":"KEY"}]}"""))
        assertNull(parseKugouCandidate("""{"candidates":[]}"""))
        assertNull(parseKugouCandidate("garbage"))
        assertNull(parseKugouCandidate(null))

        assertEquals("QUJD", parseKugouContent("""{"content":"QUJD"}"""))
        assertNull(parseKugouContent("""{"status":1}"""))
        assertNull(parseKugouContent("garbage"))
    }

    @Test
    fun `credits are stripped but metadata and lyrics survive`() {
        val lrc = listOf(
            "?[id:\$00000000]",
            "[ar:Ed Sheeran]",
            "[ti:Shape of You]",
            "[al:Shape of You]",
            "[offset:0]",
            "[00:00.21]Ed Sheeran - Shape of You",
            "[00:03.22]Lyrics by:Ed Sheeran?Steve Mac",
            "[00:03.43]Composed by:Ed Sheeran?Steve Mac",
            "[00:09.55]The club isn't the best place to find a lover",
            "[00:11.99]So the bar is where I go",
        ).joinToString("\n")
        val cleaned = cleanKugouCredits(lrc)
        assertEquals(
            listOf(
                "[ar:Ed Sheeran]",
                "[ti:Shape of You]",
                "[al:Shape of You]",
                "[offset:0]",
                "[00:09.55]The club isn't the best place to find a lover",
                "[00:11.99]So the bar is where I go",
            ).joinToString("\n"),
            cleaned,
        )
    }

    @Test
    fun `a trailing credit line is stripped`() {
        val lyrics = (1..40).joinToString("\n") { "[00:%02d.00]Line %d".format(it % 60, it) }
        val cleaned = cleanKugouCredits("$lyrics\n[00:59.00]Producer:Someone")
        assertTrue(cleaned.endsWith("[00:40.00]Line 40"))
        assertFalse(cleaned.contains("Producer"))
    }

    @Test
    fun `credit-only content yields an empty string`() {
        val lrc = "[00:01.00]Lyrics by:A\n[00:02.00]Composed by:B"
        assertEquals("", cleanKugouCredits(lrc))
    }

    @Test
    fun `instrumental notice is detected`() {
        assertTrue(isKugouInstrumentalNotice("[00:00.00]纯音乐，请欣赏"))
        assertFalse(isKugouInstrumentalNotice("[00:00.00]Real lyrics here"))
        assertFalse(isKugouInstrumentalNotice(""))
        assertFalse(isKugouInstrumentalNotice("[00:00.00]"))
    }

    @Test
    fun `a full downloaded file is recognised as synced despite its metadata header`() {
        val file = listOf(
            "?[id:\$00000000]",
            "[ar:Ed Sheeran]",
            "[ti:Shape of You]",
            "[al:Shape of You]",
            "[offset:0]",
            "[00:03.22]Lyrics by:Ed Sheeran?Steve Mac",
            "[00:09.55]The club isn't the best place to find a lover",
            "[00:11.99]So the bar is where I go",
        ).joinToString("\n")
        val result = kugouResult("KuGou", file)
        val synced = result?.synced
        assertTrue(synced != null)
        assertFalse(synced!!.contains("Lyrics by"))
        assertTrue(synced.contains("[00:09.55]The club isn't the best place to find a lover"))
        assertNull(result.plain)
    }

    @Test
    fun `untimed content stays plain and notices and blanks yield null`() {
        val plain = kugouResult("KuGou", "Just some untimed words")
        assertNull(plain?.synced)
        assertEquals("Just some untimed words", plain?.plain)
        assertNull(kugouResult("KuGou", "[00:00.00]纯音乐,请欣赏"))
        assertNull(kugouResult("KuGou", "   "))
        assertNull(kugouResult("KuGou", ""))
    }
}
