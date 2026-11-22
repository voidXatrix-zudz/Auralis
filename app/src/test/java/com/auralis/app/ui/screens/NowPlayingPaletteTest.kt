package com.auralis.app.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blend background leans on a handful of colour maths helpers. They are the only part of the
 * Now Playing restyle that is pure logic, so they are the part we can pin down in a test: the
 * contrast ceiling must always hold, whatever the artwork throws at it.
 */
class NowPlayingPaletteTest {

    @Test
    fun lumaOfPureColours() {
        assertEquals(0f, Color.Black.luma(), 0.001f)
        assertEquals(1f, Color.White.luma(), 0.001f)
        assertEquals(0.7152f, Color(0f, 1f, 0f).luma(), 0.001f)
    }

    @Test
    fun capLumaLeavesDarkColoursAlone() {
        val dark = Color(0xFF101828)
        assertEquals(dark, dark.capLuma(0.42f))
    }

    @Test
    fun capLumaPullsBrightColoursDownToTheCeiling() {
        val bright = Color(0xFFFFF3A0)
        val capped = bright.capLuma(0.30f)
        assertTrue("luma was ${capped.luma()}", capped.luma() <= 0.301f)
        assertTrue(capped.luma() > 0.29f)
        // Scaling preserves the channel order, so the tint does not flip.
        assertTrue(capped.red >= capped.green && capped.green >= capped.blue)
    }

    @Test
    fun noDominantFallsBackToTheAppPalette() {
        assertEquals(ArtPalette.Fallback, NowPlayingPaletteEngine.fromDominant(null))
    }

    @Test
    fun everySlotStaysUnderItsContrastCeiling() {
        // A saturated yellow is the worst case: extremely bright, so every slot has to be clamped.
        val palette = NowPlayingPaletteEngine.fromDominant(0xFFFFD700.toInt())
        assertTrue(palette.dominant.luma() <= 0.461f)
        assertTrue(palette.vibrant.luma() <= 0.561f)
        assertTrue(palette.muted.luma() <= 0.421f)
        assertTrue(palette.darkMuted.luma() <= 0.181f)
        assertTrue(palette.lightVibrant.luma() <= 0.621f)
    }

    @Test
    fun darkMutedIsAlwaysTheDarkestSlot() {
        val palette = NowPlayingPaletteEngine.fromDominant(0xFF3B5BDB.toInt())
        assertTrue(palette.darkMuted.luma() < palette.dominant.luma())
        assertTrue(palette.lightVibrant.luma() > palette.darkMuted.luma())
    }
}
