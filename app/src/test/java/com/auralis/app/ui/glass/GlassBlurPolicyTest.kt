package com.auralis.app.ui.glass

import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PerformanceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blur contract: backdrop blur is a property of the Glassmorphism look, so it may only be
 * refused by the platform or by an explicit user choice. Performance heuristics (RAM, cores, heap
 * class, Battery Saver, thermal) are not inputs at all, which is what makes "Automatic never
 * disables blur" true by construction. STEP 5: the Low tier still blurs (a larger, cheaper radius);
 * it only drops the lens.
 */
class GlassBlurPolicyTest {

    @Test
    fun `blur is allowed by default on a supported platform`() {
        assertTrue(GlassBlurPolicy.allowed(AppSettings(), sdkInt = 33))
    }

    @Test
    fun `every performance mode keeps blur`() {
        for (mode in PerformanceMode.entries) {
            assertTrue(
                "blur must stay on for $mode",
                GlassBlurPolicy.allowed(AppSettings(performanceProfile = mode), 33),
            )
        }
    }

    @Test
    fun `reduce blur and effects turns blur off`() {
        assertFalse(GlassBlurPolicy.allowed(AppSettings(reduceBlurAndEffects = true), 33))
    }

    @Test
    fun `android 11 and older cannot blur`() {
        assertFalse(GlassBlurPolicy.allowed(AppSettings(), sdkInt = 30))
    }

    @Test
    fun `source registration matches the blur decision`() {
        assertTrue(GlassBlurPolicy.sourceAllowed(AppSettings(), sdkInt = 33))
        assertTrue(GlassBlurPolicy.sourceAllowed(AppSettings(performanceProfile = PerformanceMode.LOW), sdkInt = 33))
        assertFalse(GlassBlurPolicy.sourceAllowed(AppSettings(reduceBlurAndEffects = true), sdkInt = 33))
        assertFalse(GlassBlurPolicy.sourceAllowed(AppSettings(), sdkInt = 30))
    }
}
