package com.auralis.app.data.lyrics

import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.LyricsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ QRC is what the Better Lyrics "Portato" provider returns. These fixtures are trimmed from a
 * real `/qq/getLyrics` response, so they guard the exact word/space layout the renderer depends on.
 */
class QrcParserTest {

    private val fixture = """
        <?xml version="1.0" encoding="utf-8"?>
        <QrcInfos>
        <QrcHeadInfo SaveTime="231" Version="100"/>
        <LyricInfo LyricCount="1">
        <Lyric_1 LyricType="1" LyricContent="[ti:Shape of You]
        [ar:Ed Sheeran]
        [offset:0]
        [0,2380]Shape(0,476) (476,95)of(571,190) (761,95)You(856,286)
        " />
        </LyricInfo>
        </QrcInfos>
    """.trimIndent()

    @Test
    fun `qrc word tokens convert to enhanced lrc and keep the spaces`() {
        val lrc = QrcParser.parse(fixture)
        assertEquals(
            "[00:00.000]<00:00.000>Shape<00:00.476> <00:00.571>of<00:00.761> <00:00.856>You",
            lrc,
        )
    }

    @Test
    fun `converted qrc parses as word-synced lyrics with readable text`() {
        val lyrics = LrcParser.parse(QrcParser.parse(fixture)!!)
        assertEquals(LyricsSync.WORD, lyrics.sync)
        val line = lyrics.synced?.single()
        assertEquals("Shape of You", line?.text)
        assertTrue(line?.words?.isNotEmpty() == true)
    }

    @Test
    fun `blank and non-qrc bodies yield null`() {
        assertNull(QrcParser.parse(null))
        assertNull(QrcParser.parse(""))
        assertNull(QrcParser.parse("not qrc at all"))
    }
}