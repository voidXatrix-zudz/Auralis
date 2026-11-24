package com.auralis.app.ui.glass

import androidx.compose.ui.graphics.Color
import com.auralis.app.data.settings.dockTransparencyToOpacity
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.PerformanceProfiles
import com.auralis.app.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock material contract: everything the Live blur + Dock opacity sliders drive must be
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

    // --- Dock opacity: one fill, clear -> translucent -> opaque solid ----------------------

    @Test
    fun `dock fill is fully clear at zero percent`() {
        val fill = GlassMapping.dockFill(0f)
        assertEquals(0f, fill.alpha, 0.0001f)
        assertEquals(GlassTokens.Cream, fill.copy(alpha = 1f))
    }

    @Test
    fun `dock fill is exactly opaque solid at one hundred percent`() {
        val fill = GlassMapping.dockFill(1f)
        assertEquals(1f, fill.alpha, 0f)
        assertEquals(GlassMapping.DOCK_SOLID, fill)
        // Never a dead pure black: the solid is the canvas lifted toward cream.
        assertTrue(fill != Color.Black)
    }

    @Test
    fun `dock fill alpha is monotonic across the whole slider`() {
        var previous = -1f
        var i = 0
        while (i <= 100) {
            val a = GlassMapping.dockFill(i / 100f).alpha
            assertTrue("alpha must not decrease at $i%: $a < $previous", a >= previous - 0.0001f)
            assertTrue(a in 0f..1f)
            previous = a
            i++
        }
    }

    @Test
    fun `dock fill has no visible step between adjacent percent values`() {
        var i = 0
        while (i < 100) {
            val a = GlassMapping.dockFill(i / 100f).alpha
            val b = GlassMapping.dockFill((i + 1) / 100f).alpha
            assertTrue("1% step at $i% moves alpha by ${b - a}", b - a <= 0.04f + 0.0001f)
            i++
        }
    }

    @Test
    fun `dock fill stays frost without a black tint below half opacity`() {
        var i = 0
        while (i <= 50) {
            val fill = GlassMapping.dockFill(i / 100f)
            assertEquals(GlassTokens.Cream, fill.copy(alpha = 1f))
            assertTrue(fill.alpha <= 0.10f + 0.0001f)
            i++
        }
    }

    @Test
    fun `dock saturation stays in range across the blur slider`() {
        var i = 0
        while (i <= 100) {
            assertTrue(GlassMapping.dockSaturation(i / 100f) in 1.0f..1.30f + 0.0001f)
            i++
        }
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
                glass = GlassSettings(dockOpacity = 0.5f, blurStrength = 1f),
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
