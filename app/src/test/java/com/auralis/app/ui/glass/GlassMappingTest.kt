package com.auralis.app.ui.glass

import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.PerformanceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock material contract: everything the Live blur + Dock transparency sliders drive must be
 * large, monotonic and tier-independent (the tier only changes render cost).
 */
class GlassMappingTest {

    // --- Live blur: 0% -> 0dp, 100% -> 80dp, 80 * s^1.15, rounded to 0.5 dp ---------------

    @Test
    fun `blur radius hits the five percent targets`() {
        assertEquals(0f, GlassMapping.blurDp(0f), 0.001f)
        assertEquals(16f, GlassMapping.blurDp(0.25f), 2f)
        assertEquals(36f, GlassMapping.blurDp(0.50f), 2f)
        assertEquals(57f, GlassMapping.blurDp(0.75f), 2f)
        assertEquals(80f, GlassMapping.blurDp(1f), 0.001f)
    }

    @Test
    fun `blur radius is monotonic and always snapped to half a dp`() {
        var previous = -1f
        var s = 0f
        while (s <= 1.0001f) {
            val r = GlassMapping.blurDp(s.coerceAtMost(1f))
            assertTrue("radius must not decrease", r >= previous)
            assertEquals("must be a 0.5dp multiple", 0f, (r * 2f) - (r * 2f).toInt().toFloat(), 0.001f)
            previous = r
            s += 0.01f
        }
    }

    @Test
    fun `blur radius is never scaled by the performance tier`() {
        // Same slider value -> same radius regardless of profile.
        assertEquals(GlassMapping.blurDp(1f), GlassMapping.blurDp(1f))
        assertEquals(80f, GlassMapping.blurDp(1f), 0.001f)
    }

    // --- Dock transparency: 0.90 / 0.66 / 0.42 / 0.20 / 0.05 ------------------------------

    @Test
    fun `dock tint alpha hits the five percent targets`() {
        assertEquals(0.90f, GlassMapping.dockTintAlpha(0f), 0.03f)
        assertEquals(0.66f, GlassMapping.dockTintAlpha(0.25f), 0.03f)
        assertEquals(0.42f, GlassMapping.dockTintAlpha(0.50f), 0.03f)
        assertEquals(0.20f, GlassMapping.dockTintAlpha(0.75f), 0.03f)
        assertEquals(0.05f, GlassMapping.dockTintAlpha(1f), 0.03f)
    }

    @Test
    fun `dock tint alpha is monotonic and clamped`() {
        var previous = 2f
        var i = 0
        while (i <= 100) {
            val a = GlassMapping.dockTintAlpha(i / 100f)
            assertTrue(a <= previous + 0.0001f)
            assertTrue(a in 0f..1f)
            previous = a
            i++
        }
    }

    @Test
    fun `dock frost and scrim stay within their caps`() {
        var i = 0
        while (i <= 100) {
            val s = i / 100f
            assertTrue(GlassMapping.dockFrostAlpha(s) <= 0.08f + 0.0001f)
            assertTrue(GlassMapping.dockFrostAlpha(s) >= 0.02f - 0.0001f)
            val scrim = GlassMapping.dockTextScrimAlpha(s)
            assertTrue("scrim never exceeds 0.16", scrim <= 0.16f + 0.0001f)
            assertTrue(GlassMapping.dockSaturation(s) in 1.0f..1.30f + 0.0001f)
            i++
        }
    }

    @Test
    fun `haze input scale reduces cost with radius and clamps to a quarter`() {
        assertEquals(1f, GlassMapping.hazeInputScale(0f), 0.001f)
        assertTrue(GlassMapping.hazeInputScale(40f) < GlassMapping.hazeInputScale(20f))
        assertTrue(GlassMapping.hazeInputScale(1000f) >= 0.25f)
        assertEquals(1f / (1f + 80f / 28f), GlassMapping.hazeInputScale(80f), 0.001f)
    }

    // --- resolveDockGlass honesty ---------------------------------------------------------

    private fun settings() = AppSettings()

    @Test
    fun `no reduced-by-performance-mode message is ever produced`() {
        for (profile in listOf(PerformanceProfiles.High, PerformanceProfiles.Medium, PerformanceProfiles.Low)) {
            val style = resolveDockGlass(
                settings = settings(),
                glass = GlassSettings(dockTransparency = 0.5f, blurStrength = 1f),
                profile = profile,
            )
            assertTrue(
                "reason must not mention performance mode: ${style.limitedReason}",
                style.limitedReason?.contains("Performance Mode") != true,
            )
        }
    }

    @Test
    fun `old and low android keeps the android 12 message`() {
        val style = resolveDockGlass(
            settings = settings(),
            glass = GlassSettings(blurStrength = 1f),
            profile = PerformanceProfiles.High,
            sdkInt = 30,
        )
        assertTrue(style.limitedReason?.contains("Android 12") == true)
    }

    @Test
    fun `blur strength zero reports no limitation`() {
        val style = resolveDockGlass(
            settings = settings(),
            glass = GlassSettings(blurStrength = 0f),
            profile = PerformanceProfiles.High,
            sdkInt = 33,
        )
        assertNull(style.limitedReason)
    }

    @Test
    fun `low tier still reports the low performance message`() {
        val style = resolveDockGlass(
            settings = settings(),
            glass = GlassSettings(blurStrength = 1f),
            profile = PerformanceProfiles.Low,
            sdkInt = 33,
        )
        assertTrue(style.limitedReason?.contains("Low performance") == true)
    }
}
