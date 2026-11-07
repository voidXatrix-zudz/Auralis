package com.auralis.app

import com.auralis.app.data.SourceIds
import com.auralis.app.domain.audio.EqGainsCodec
import com.auralis.app.domain.audio.EqPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    private val flat = EqPresets.gains("Flat")

    @Test fun gainsRoundTrip() {
        val rock = EqPresets.gains("Rock")
        assertEquals(rock, EqGainsCodec.decode(EqGainsCodec.encode(rock), flat))
    }

    @Test fun malformedGainsFallBack() {
        assertEquals(flat, EqGainsCodec.decode(null, flat))
        assertEquals(flat, EqGainsCodec.decode("1,2,3", flat))
        assertEquals(flat, EqGainsCodec.decode("a,b,c,d,e,f,g,h,i,j", flat))
    }

    @Test fun gainsAreClampedToTwelveDb() {
        val decoded = EqGainsCodec.decode("30,-30,0,0,0,0,0,0,0,0", flat)
        assertEquals(12f, decoded[0])
        assertEquals(-12f, decoded[1])
    }

    @Test fun treeSourceIdIsStableAndUnique() {
        val a = SourceIds.forTree("content://tree/a")
        assertEquals(a, SourceIds.forTree("content://tree/a"))
        assertTrue(a != SourceIds.forTree("content://tree/b"))
    }
}
