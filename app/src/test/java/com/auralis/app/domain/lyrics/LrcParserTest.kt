package com.auralis.app.domain.lyrics

import com.auralis.app.domain.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `multiple timestamps on one line are expanded and sorted`() {
        val parsed = LrcParser.parse(
            """
            [00:20.10][00:12.40]Repeated line
            [00:08.00]First
            """.trimIndent(),
        )
        val lines = parsed.synced!!
        assertEquals(3, lines.size)
        assertEquals(listOf(8_000L, 12_400L, 20_100L), lines.map { it.timeMs })
        assertEquals("Repeated line", lines[1].text)
        assertEquals(LyricsSource.USER, parsed.source)
    }

    @Test
    fun `offset tag shifts every timestamp`() {
        val parsed = LrcParser.parse("[offset:+500]\n[00:10.00]Shifted\n[00:11.50]Later")
        assertEquals(10_500L, parsed.synced!![0].timeMs)
        assertEquals(12_000L, parsed.synced!![1].timeMs)
    }

    @Test
    fun `active line moves with playback position`() {
        val lines = LrcParser.parse("[00:10.00]One\n[00:20.00]Two\n[00:30.00]Three").synced!!
        assertEquals(-1, LrcParser.activeLine(lines, 9_999))
        assertEquals(0, LrcParser.activeLine(lines, 10_000))
        assertEquals(1, LrcParser.activeLine(lines, 25_000))
        assertEquals(2, LrcParser.activeLine(lines, 90_000))
    }

    @Test
    fun `validate counts timed lines and flags plain text`() {
        val timed = LrcParser.validate("[00:01.00]A\n[00:02.50]B\n[00:02.50]C")
        assertEquals(3, timed.timedLines)
        assertTrue(timed.hasTimestamps)
        assertTrue(timed.canSave)
        assertTrue(timed.warnings.any { it.contains("duplicated", ignoreCase = true) })

        val plain = LrcParser.validate("Just a plain line\nAnother line")
        assertEquals(0, plain.timedLines)
        assertEquals(2, plain.plainLines)
        assertFalse(plain.hasTimestamps)
        assertTrue(plain.canSave)

        val empty = LrcParser.validate("   \n\n")
        assertFalse(empty.canSave)
    }

    @Test
    fun `timestamps and metadata tags do not leak into plain preview`() {
        val parsed = LrcParser.parse("[ti:Title]\n[ar:Artist]\n[00:05.00]Hello\n[00:10.00]World")
        assertNotNull(parsed.synced)
        assertEquals("Hello\nWorld", parsed.plain)
    }

    @Test
    fun `enhanced word tags are extracted and stripped from display text`() {
        val parsed = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.60>big <00:11.20>world")
        val line = parsed.synced!!.single()
        assertEquals("Hello big world", line.text)
        assertEquals(listOf(10_000L, 10_600L, 11_200L), line.words.map { it.timeMs })
        assertEquals(listOf("Hello", "big", "world"), line.words.map { it.text })
        assertEquals("Hello big world", parsed.plain)
    }

    @Test
    fun `word tags receive the offset tag`() {
        val parsed = LrcParser.parse("[offset:-200]\n[00:10.00]<00:10.00>Hi <00:10.50>there")
        val line = parsed.synced!!.single()
        assertEquals(9_800L, line.timeMs)
        assertEquals(listOf(9_800L, 10_300L), line.words.map { it.timeMs })
    }

    @Test
    fun `line without a leading tag is timed by its first word tag`() {
        val parsed = LrcParser.parse("<00:07.50>Word timed line")
        val line = parsed.synced!!.single()
        assertEquals(7_500L, line.timeMs)
        assertEquals("Word timed line", line.text)
        assertEquals(1, line.words.size)
    }

    @Test
    fun `multiple timestamps share the same word timings`() {
        val parsed = LrcParser.parse("[00:20.00][00:10.00]<00:10.00>Re <00:10.40>peat")
        assertEquals(2, parsed.synced!!.size)
        assertEquals(listOf(10_000L, 10_400L), parsed.synced!![0].words.map { it.timeMs })
        // The repeat is shifted by the same delta as its line timestamp.
        assertEquals(listOf(20_000L, 20_400L), parsed.synced!![1].words.map { it.timeMs })
    }

    @Test
    fun `malformed lines are kept as plain text and do not break parsing`() {
        val parsed = LrcParser.parse(
            """
            [00:bad]Broken tag
            [00:01.00]Good line
            [00:02.xx]Bad fraction
            [00:03.00]
            """.trimIndent(),
        )
        val lines = parsed.synced!!
        assertEquals(1, lines.size)
        assertEquals("Good line", lines[0].text)
        assertEquals(1_000L, lines[0].timeMs)
        val plain = parsed.plain.orEmpty()
        assertTrue(plain.contains("Broken tag"))
        assertTrue(plain.contains("Bad fraction"))
    }

    @Test
    fun `speaker tags are stripped on every path`() {
        // "v1:" right after the line timestamp, "V2:" right after a word tag.
        val parsed = LrcParser.parse(
            """
            [00:12.00]v1:Don't cry
            [00:20.00]<00:20.00>V2:Don't <00:20.60>speak
            [v1: hidden bracket line]
            [00:30.00]Plain line
            """.trimIndent(),
        )
        val lines = parsed.synced!!
        assertEquals("Don't cry", lines[0].text)
        assertEquals("Don't speak", lines[1].text)
        assertEquals(listOf("Don't", "speak"), lines[1].words.map { it.text })
        // A real lyric that starts with a bracketed speaker label is never swallowed as metadata.
        assertEquals("Plain line", lines[2].text)
        assertTrue(parsed.plain.orEmpty().contains("hidden bracket line"))
    }

    @Test
    fun `offset tag with surrounding spaces still applies`() {
        val parsed = LrcParser.parse("[offset: +300 ]\n[00:10.00]Shifted")
        assertEquals(10_300L, parsed.synced!!.single().timeMs)
    }

    @Test
    fun `line that empties after speaker tag strip keeps its timestamp`() {
        val parsed = LrcParser.parse("[00:40.00]v1:")
        val line = parsed.synced!!.single()
        assertEquals(40_000L, line.timeMs)
        assertEquals("", line.text)
    }
}
