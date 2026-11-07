package com.auralis.app

import com.auralis.app.core.formatDuration
import com.auralis.app.core.formatFileSize
import com.auralis.app.core.formatSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun durationUnderAnHour() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("3:05", formatDuration(185_000))
    }

    @Test fun durationOverAnHour() {
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test fun negativeDurationIsZero() {
        assertEquals("0:00", formatDuration(-5))
    }

    @Test fun fileSizes() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024))
        assertEquals("1.50 GB", formatFileSize(3L * 512 * 1024 * 1024))
    }

    @Test fun summaryUsesOnlyAvailableProperties() {
        val hiRes = fakeTrack(bitDepth = 24, sampleRateHz = 96_000, bitrateBps = 2_304_000)
        assertEquals("FLAC · 24-bit · 96 kHz · 2304 kbps", hiRes.formatSummary())
        val mp3 = fakeTrack(ext = "mp3", codec = "MPEG 1 Layer 3", lossless = false, bitDepth = null, sampleRateHz = 44_100, bitrateBps = 320_000)
        assertEquals("MP3 · 44.1 kHz · 320 kbps", mp3.formatSummary())
        assertEquals("FLAC", fakeTrack(bitDepth = null, sampleRateHz = null).formatSummary())
    }
}
