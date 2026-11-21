package com.auralis.app.data.lyrics

import com.auralis.app.domain.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture tests for the LyricsPlus body -> enhanced-LRC conversion. */
class LyricsPlusProviderTest {

    private val body = """
        {"type":"Word","metadata":{"source":"Apple"},"lyrics":[
          {"time":5623,"duration":2673,"text":"Something's got a hold",
           "syllabus":[
             {"time":5623,"duration":751,"text":"Something's "},
             {"time":6374,"duration":203,"text":"got "},
             {"time":6577,"duration":154,"text":"a "},
             {"time":6731,"duration":203,"text":"hold "}
           ]},
          {"time":8485,"duration":3525,"text":"No, I don't know"}
        ]}
    """.trimIndent()

    @Test
    fun `syllabus becomes inline word tags and syllabUS-less lines stay line-level`() {
        assertEquals(
            "[00:05.623]<00:05.623>Something's <00:06.374>got <00:06.577>a <00:06.731>hold\n" +
                "[00:08.485]No, I don't know",
            lyricsPlusFromBody(body),
        )
    }

    @Test
    fun `converted text parses into word-synced lines`() {
        val lyrics = LrcParser.parse(lyricsPlusFromBody(body)!!)
        val lines = lyrics.synced!!
        assertEquals(2, lines.size)
        assertEquals("Something's got a hold", lines[0].text)
        assertEquals(listOf("Something's", "got", "a", "hold"), lines[0].words.map { it.text })
        assertEquals(listOf(5_623L, 6_374L, 6_577L, 6_731L), lines[0].words.map { it.timeMs })
        assertEquals("No, I don't know", lines[1].text)
        assertEquals(emptyList<String>(), lines[1].words.map { it.text })
    }

    @Test
    fun `empty malformed and lyric-less bodies yield null`() {
        assertNull(lyricsPlusFromBody(null))
        assertNull(lyricsPlusFromBody(""))
        assertNull(lyricsPlusFromBody("not json"))
        assertNull(lyricsPlusFromBody("""{"error":{"status":404}}"""))
        assertNull(lyricsPlusFromBody("""{"lyrics":[]}"""))
    }

    @Test
    fun `rows without a time are dropped`() {
        val json = """{"lyrics":[{"text":"no time"},{"time":1000,"text":"kept"}]}"""
        assertEquals("[00:01.000]kept", lyricsPlusFromBody(json))
    }
}
