package com.auralis.app

import com.auralis.app.domain.audio.isHiRes
import com.auralis.app.domain.audio.isLossless
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityTest {
    @Test fun cdQualityFlacIsLosslessButNotHiRes() {
        val t = fakeTrack(bitDepth = 16, sampleRateHz = 44_100)
        assertTrue(t.isLossless)
        assertFalse(t.isHiRes)
    }

    @Test fun flacAbove16BitIsHiRes() {
        assertTrue(fakeTrack(bitDepth = 24, sampleRateHz = 44_100).isHiRes)
    }

    @Test fun flacAbove44kHzIsHiRes() {
        assertTrue(fakeTrack(bitDepth = 16, sampleRateHz = 96_000).isHiRes)
    }

    @Test fun mp3IsNeverBadged() {
        val t = fakeTrack(ext = "mp3", codec = "MPEG 1 Layer 3", lossless = false, bitDepth = null, sampleRateHz = 48_000)
        assertFalse(t.isLossless)
        assertFalse(t.isHiRes)
    }

    @Test fun losslessIsInferredFromCodecWhenFlagMissing() {
        assertTrue(fakeTrack(codec = "ALAC", lossless = false).isLossless)
        assertTrue(fakeTrack(codec = "PCM_S16LE", lossless = false).isLossless)
    }

    @Test fun missingPropertiesDoNotJustifyHiRes() {
        assertFalse(fakeTrack(bitDepth = null, sampleRateHz = null).isHiRes)
    }
}
