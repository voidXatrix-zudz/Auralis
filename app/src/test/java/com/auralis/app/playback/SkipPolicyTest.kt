package com.auralis.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipPolicyTest {
    @Test fun `previous from inside the autoplay tail steps back one song`() {
        // Queue: [Bloodline (context), B, Sao Paulo]. On Sao Paulo (2), previous is B (1), not Bloodline.
        assertEquals(1, previousSkipTarget(previous = 1, current = 2, lastContext = 0))
    }

    @Test fun `previous from the first autoplay song returns to the last context song`() {
        assertEquals(0, previousSkipTarget(previous = 0, current = 1, lastContext = 0))
    }

    @Test fun `repeat all wrap from the first context song is clamped to the last context song`() {
        // On index 0 of [c0, c1, c2, a3, a4], Media3 wraps previous to 4 (the tail).
        assertEquals(2, previousSkipTarget(previous = 4, current = 0, lastContext = 2))
    }

    @Test fun `plain list steps back one`() {
        assertEquals(3, previousSkipTarget(previous = 3, current = 4, lastContext = 9))
    }

    @Test fun `no context block uses the previous index as is`() {
        assertEquals(1, previousSkipTarget(previous = 1, current = 2, lastContext = -1))
    }

    @Test fun `nothing before the first song returns null`() {
        assertNull(previousSkipTarget(previous = -1, current = 0, lastContext = 0))
    }
}
