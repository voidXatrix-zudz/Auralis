package com.auralis.app.data.lyrics

import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.LyricsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the Apple TTML -> enhanced-LRC conversion, including the empty/malformed cases
 * and the round trip through [LrcParser] that proves the screens get ordinary word-synced lines.
 */
class TtmlParserTest {

    @Test
    fun `word-timed TTML becomes enhanced LRC and parses into words`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word"><body><div>
            <p begin="9.731" end="12.105"><span begin="9.731" end="9.927">The</span> <span begin="9.927" end="10.284">club</span> <span begin="10.284" end="12.105">rocks &amp; rolls</span></p>
            </div></body></tt>
        """.trimIndent()
        assertEquals(
            "[00:09.731]<00:09.731>The <00:09.927>club <00:10.284>rocks & rolls",
            TtmlParser.parse(ttml),
        )

        val lyrics = LrcParser.parse(TtmlParser.parse(ttml)!!)
        val line = lyrics.synced!!.single()
        assertEquals(9_731L, line.timeMs)
        assertEquals("The club rocks & rolls", line.text)
        assertEquals(listOf("The", "club", "rocks & rolls"), line.words.map { it.text })
        assertEquals(listOf(9_731L, 9_927L, 10_284L), line.words.map { it.timeMs })
        assertEquals(LyricsSync.WORD, lyrics.sync)
    }

    @Test
    fun `line-level and mm-ss timings are supported`() {
        val ttml = """<tt><body><div><p begin="12.000">Just a line</p><p begin="1:02.500">Later line</p></div></body></tt>"""
        assertEquals(
            "[00:12.000]Just a line\n[01:02.500]Later line",
            TtmlParser.parse(ttml),
        )
    }

    @Test
    fun `entity references are decoded`() {
        val ttml = """<tt><body><p begin="1.5">Don&#39;t &lt;stop&gt; &quot;now&quot;</p></body></tt>"""
        assertEquals("[00:01.500]Don't <stop> \"now\"", TtmlParser.parse(ttml))
    }

    @Test
    fun `empty paragraphs keep their timestamp as instrumental gaps`() {
        val ttml = """<tt><body><p begin="5.0"></p><p begin="6.0">Real</p></body></tt>"""
        assertEquals("[00:05.000]\n[00:06.000]Real", TtmlParser.parse(ttml))
    }

    @Test
    fun `blank malformed or untimed input yields null`() {
        assertNull(TtmlParser.parse(null))
        assertNull(TtmlParser.parse(""))
        assertNull(TtmlParser.parse("   "))
        assertNull(TtmlParser.parse("not xml at all"))
        assertNull(TtmlParser.parse("<tt><body><div>no paragraphs</div></body></tt>"))
        assertNull(TtmlParser.parse("<tt><body><p>no begin attribute</p></body></tt>"))
    }

    @Test
    fun `parse never throws on truncated markup`() {
        assertTrue(TtmlParser.parse("<tt><body><p begin=\"1.0\">unclosed") == null)
    }
}
