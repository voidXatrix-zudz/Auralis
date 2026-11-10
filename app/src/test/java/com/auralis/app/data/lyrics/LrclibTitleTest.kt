package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/** Metadata/filename cleanup rules used for LRCLIB lookups. */
class LrclibTitleTest {
    @Test
    fun stripsExtensionUnderscoresAndTrackNumber() {
        assertEquals("Some Song name", cleanFileName("01 - Some_Song_name.mp3"))
        assertEquals("Some Song name", cleanFileName("01_-_Some_Song_name.flac"))
    }

    @Test
    fun keepsPlainNames() {
        assertEquals("QA Tone 60Hz", cleanFileName("QA_Tone_60Hz.wav"))
    }

    @Test
    fun keepsNumericTitles() {
        assertEquals("1979 Song", cleanFileName("1979 Song.flac"))
        assertEquals("Mr. Brightside", cleanFileName("Mr. Brightside"))
        assertEquals("1999", cleanFileName("1999"))
    }
}
