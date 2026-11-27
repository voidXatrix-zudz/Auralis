package com.auralis.app.ui.glass

import com.auralis.app.data.settings.dockTransparencyToOpacity
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.PerformanceProfiles
import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glass mapping contract (): the ONE place a transparency/clarity value becomes an
 * alpha, a blur radius or an effect scale. The dock and the panels move in opposite directions on
 * purpose, both never add a black/grey wash, and blur is fixed per tier (never slider-driven).
 */
class GlassMappingTest {

    // --- dockTintAlpha: 0 -> 0.02, 100 -> 1.00, monotonic, never black ---------------------

    @Test
    fun `dock tint hits its endpoints`() {
        assertEquals(0.02f, GlassMappings.dockTintAlpha(0), 0.0001f)
        assertEquals(1.00f, GlassMappings.dockTintAlpha(100), 0.0001f)
    }

    @Test
    fun `dock tint is monotonic and clamped across the slider`() {
        var previous = -1f
        var i = 0
        while (i <= 100) {
            val a = GlassMappings.dockTintAlpha(i)
            assertTrue("alpha must not decrease at $i%", a >= previous)
            assertTrue(a in 0.02f..1.0f)
            previous = a
            i++
        }
        assertEquals(GlassMappings.dockTintAlpha(-10), GlassMappings.dockTintAlpha(0), 0f)
        assertEquals(GlassMappings.dockTintAlpha(140), GlassMappings.dockTintAlpha(100), 0f)
    }

    // --- panelTintAlpha: clarity 100 -> most see-through (0.12), 0 -> most solid (0.92) ------

    @Test
    fun `panel tint hits its endpoints`() {
        assertEquals(0.92f, GlassMappings.panelTintAlpha(0), 0.0001f)
        assertEquals(0.12f, GlassMappings.panelTintAlpha(100), 0.0001f)
    }

    @Test
    fun `panel tint is monotonic decreasing and clamped`() {
        var previous = 2f
        var i = 0
        while (i <= 100) {
            val a = GlassMappings.panelTintAlpha(i)
            assertTrue("alpha must not increase at $i%", a <= previous)
            assertTrue(a in 0.12f..0.92f)
            previous = a
            i++
        }
        assertEquals(GlassMappings.panelTintAlpha(-5), GlassMappings.panelTintAlpha(0), 0f)
        assertEquals(GlassMappings.panelTintAlpha(200), GlassMappings.panelTintAlpha(100), 0f)
    }

    // --- outline gate: OFF removes the rim on EVERY surface --------------------------------

    @Test
    fun `outlines off means no rim alpha`() {
        assertEquals(0f, GlassMappings.outlineAlpha(false), 0f)
        assertEquals(1f, GlassMappings.outlineAlpha(true), 0f)
    }

    // --- fixed per-tier constants ----------------------------------------------------------

    @Test
    fun `blur radius is fixed per tier and never slider driven`() {
        assertEquals(10f, GlassMappings.blurDp(VisualPerformanceTier.HIGH), 0f)
        assertEquals(10f, GlassMappings.blurDp(VisualPerformanceTier.MEDIUM), 0f)
        assertEquals(16f, GlassMappings.blurDp(VisualPerformanceTier.LOW), 0f)
    }

    @Test
    fun `effect scale shrinks with the tier`() {
        assertEquals(GlassTokens.EFFECT_RESOLUTION_HIGH, GlassMappings.effectScale(VisualPerformanceTier.HIGH), 0f)
        assertEquals(GlassTokens.EFFECT_RESOLUTION_MEDIUM, GlassMappings.effectScale(VisualPerformanceTier.MEDIUM), 0f)
        assertEquals(GlassTokens.EFFECT_RESOLUTION_LOW, GlassMappings.effectScale(VisualPerformanceTier.LOW), 0f)
    }

    // --- GlassConfig capability gates ------------------------------------------------------

    @Test
    fun `glass needs api 31 and the lens needs api 33 plus a non-low tier`() {
        assertFalse(GlassConfig(sdkInt = 30).isGlassCapable)
        assertTrue(GlassConfig(sdkInt = 31).isGlassCapable)
        assertFalse(GlassConfig(sdkInt = 32, tier = VisualPerformanceTier.HIGH).hasLens)
        assertTrue(GlassConfig(sdkInt = 33, tier = VisualPerformanceTier.HIGH).hasLens)
        assertTrue(GlassConfig(sdkInt = 33, tier = VisualPerformanceTier.MEDIUM).hasLens)
        assertFalse(GlassConfig(sdkInt = 33, tier = VisualPerformanceTier.LOW).hasLens)
    }

    // --- Persisted migration: transparency % -> opacity % ----------------------------------

    @Test
    fun `old transparency migrates to the inverted opacity`() {
        assertEquals(70, dockTransparencyToOpacity(30))
        assertEquals(0, dockTransparencyToOpacity(100))
        assertEquals(100, dockTransparencyToOpacity(0))
        // Out-of-range values are clamped, never persisted raw.
        assertEquals(100, dockTransparencyToOpacity(-20))
        assertEquals(0, dockTransparencyToOpacity(140))
    }

    // --- resolveDockGlass honesty ---------------------------------------------------------

    private fun settings() = AppSettings()

    @Test
    fun `no reduced-by-performance-mode message is ever produced`() {
        for (profile in listOf(PerformanceProfiles.High, PerformanceProfiles.Medium, PerformanceProfiles.Low)) {
            val style = resolveDockGlass(
                settings = settings(),
                glass = GlassSettings(dockOpacity = 0.5f),
                profile = profile,
            )
            assertTrue(
                "reason must not mention performance mode: ${style.limitedReason}",
                style.limitedReason?.contains("Performance") != true,
            )
        }
    }

    @Test
    fun `android 11 and older keeps the android 12 message`() {
        val style = resolveDockGlass(
            settings = settings(),
            glass = GlassSettings(),
            profile = PerformanceProfiles.High,
            sdkInt = 30,
        )
        assertTrue(style.limitedReason?.contains("Android 12") == true)
        assertFalse(style.blurEnabled)
    }

    @Test
    fun `a supported device reports no limitation and keeps blur even on the low profile`() {
        val style = resolveDockGlass(
            settings = settings(),
            glass = GlassSettings(),
            profile = PerformanceProfiles.Low,
            sdkInt = 33,
        )
        assertNull(style.limitedReason)
        assertTrue(style.blurEnabled)
    }

    @Test
    fun `reduce blur and effects reports the true reason`() {
        val style = resolveDockGlass(
            settings = AppSettings(reduceBlurAndEffects = true),
            glass = GlassSettings(),
            profile = PerformanceProfiles.Low,
            sdkInt = 33,
        )
        assertTrue(style.limitedReason?.contains("Reduce blur and effects") == true)
        assertFalse(style.blurEnabled)
    }
}
