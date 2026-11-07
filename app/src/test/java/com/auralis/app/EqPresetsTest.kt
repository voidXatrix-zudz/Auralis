package com.auralis.app

import com.auralis.app.domain.audio.EqPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {
    @Test fun everyPresetHasOneGainPerBand() {
        EqPresets.names.forEach { assertEquals(it, EqPresets.FREQS_HZ.size, EqPresets.gains(it).size) }
    }

    @Test fun unknownPresetFallsBackToFlat() {
        assertTrue(EqPresets.gains(EqPresets.CUSTOM).all { it == 0f })
    }

    @Test fun presetsMatchTheWebReference() {
        assertEquals(listOf("Flat", "Bass", "Treble", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Electronic", "Loudness"), EqPresets.names)
    }
}
