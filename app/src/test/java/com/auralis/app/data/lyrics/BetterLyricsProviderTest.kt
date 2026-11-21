package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture tests for the BetterLyrics (`lyrics-api.boidu.dev`) response handling. */
class BetterLyricsProviderTest {

    @Test
    fun `ttml field is extracted and converted to LRC`() {
        val body = """{"ttml":"<tt><body><div><p begin=\"9.0\">Hi there</p></div></body></tt>"}"""
        val ttml = betterLyricsTtml(body)
        assertEquals("<tt><body><div><p begin=\"9.0\">Hi there</p></div></body></tt>", ttml)
        assertEquals("[00:09.000]Hi there", TtmlParser.parse(ttml!!))
    }

    @Test
    fun `word-synced ttml keeps its word timings`() {
        val body = """{"ttml":"<tt><body><p begin=\"1.0\"><span begin=\"1.0\">Hello</span> <span begin=\"1.5\">world</span></p></body></tt>"}"""
        assertEquals("[00:01.000]<00:01.000>Hello <00:01.500>world", TtmlParser.parse(betterLyricsTtml(body)!!))
    }

    @Test
    fun `empty missing or malformed bodies yield null`() {
        assertNull(betterLyricsTtml(null))
        assertNull(betterLyricsTtml(""))
        assertNull(betterLyricsTtml("not json"))
        assertNull(betterLyricsTtml("""{"ttml":""}"""))
        assertNull(betterLyricsTtml("""{"message":"Lyrics not found"}"""))
        assertNull(betterLyricsTtml("""{"ttml":null}"""))
    }
}
