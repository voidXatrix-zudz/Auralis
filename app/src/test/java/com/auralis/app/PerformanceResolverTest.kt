package com.auralis.app

import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.ui.performance.AdaptiveTierResolver
import com.auralis.app.ui.performance.DeviceConditions
import com.auralis.app.ui.performance.PerformanceProfiles
import com.auralis.app.ui.performance.VisualPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceResolverTest {

    private val strong = DeviceConditions(
        isLowRamDevice = false,
        totalMemBytes = 8L * 1024 * 1024 * 1024,
        availMemBytes = 3L * 1024 * 1024 * 1024,
        memoryClassMb = 512,
        cores = 8,
        sdkInt = 34,
    )

    private val weak = DeviceConditions(
        isLowRamDevice = true,
        totalMemBytes = 2L * 1024 * 1024 * 1024,
        availMemBytes = 512L * 1024 * 1024,
        memoryClassMb = 128,
        cores = 4,
        sdkInt = 31,
    )

    @Test fun lowRamDeviceResolvesToLowBaseTier() {
        assertEquals(VisualPerformanceTier.LOW, AdaptiveTierResolver.baseTier(weak))
    }

    @Test fun strongDeviceResolvesToHighBaseTier() {
        assertEquals(VisualPerformanceTier.HIGH, AdaptiveTierResolver.baseTier(strong))
    }

    @Test fun manualModesAreHonouredExactly() {
        val resolver = AdaptiveTierResolver()
        assertEquals(VisualPerformanceTier.HIGH, resolver.resolve(PerformanceMode.HIGH, weak, 0L))
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.MEDIUM, strong, 0L))
        assertEquals(VisualPerformanceTier.LOW, resolver.resolve(PerformanceMode.LOW, strong, 0L))
    }

    @Test fun batterySaverStepsAutomaticDownOneLevel() {
        val resolver = AdaptiveTierResolver()
        val saver = strong.copy(batterySaver = true)
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.AUTOMATIC, saver, 0L))
    }

    @Test fun thermalModerateStepsAutomaticDownOneLevel() {
        val resolver = AdaptiveTierResolver()
        val hot = strong.copy(thermalStatus = AdaptiveTierResolver.THERMAL_MODERATE)
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.AUTOMATIC, hot, 0L))
    }

    @Test fun holdPreventsFlappingAfterConstraintClears() {
        val resolver = AdaptiveTierResolver(restoreDelayMs = 30_000L)
        val saver = strong.copy(batterySaver = true)

        // Constrained: step down and start the hold.
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.AUTOMATIC, saver, 0L))
        assertTrue(resolver.holdRemainingMs(0L) > 0L)

        // Cleared almost immediately: still stepped down.
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.AUTOMATIC, strong, 1_000L))

        // Still inside the hold window.
        assertEquals(VisualPerformanceTier.MEDIUM, resolver.resolve(PerformanceMode.AUTOMATIC, strong, 29_000L))

        // Hold expired: step back up.
        assertEquals(VisualPerformanceTier.HIGH, resolver.resolve(PerformanceMode.AUTOMATIC, strong, 31_000L))
        assertEquals(0L, resolver.holdRemainingMs(31_000L))
    }

    @Test fun lowBaseTierStaysLowUnderConstraint() {
        val resolver = AdaptiveTierResolver()
        val constrainedWeak = weak.copy(batterySaver = true)
        assertEquals(VisualPerformanceTier.LOW, resolver.resolve(PerformanceMode.AUTOMATIC, constrainedWeak, 0L))
    }

    @Test fun constraintDetectionCoversSaverAndThermal() {
        assertTrue(AdaptiveTierResolver.isConstrained(strong.copy(batterySaver = true)))
        assertTrue(AdaptiveTierResolver.isConstrained(strong.copy(thermalStatus = 3)))
        assertFalse(AdaptiveTierResolver.isConstrained(strong))
    }

    @Test fun presetsReduceCostFromHighToLow() {
        val high = PerformanceProfiles.High
        val medium = PerformanceProfiles.Medium
        val low = PerformanceProfiles.Low

        assertTrue(high.blurEnabled)
        assertEquals(0.6f, medium.blurScale, 0.001f)
        assertTrue(medium.blurEnabled)
        assertFalse(low.blurEnabled)

        assertTrue(high.refractionEnabled)
        assertFalse(medium.refractionEnabled)

        assertTrue(medium.blurDownsample < 1f)
        assertTrue(low.shadowScale < medium.shadowScale)
        assertTrue(low.artworkMaxPx < medium.artworkMaxPx)
        assertTrue(medium.artworkMaxPx < high.artworkMaxPx)
    }

    // --- Glass-shaping rules: LOW is reserved for genuinely weak devices -----------------------

    @Test fun lowCoresOrSmallHeapOnlyCapAtMediumNeverForceLow() {
        val fewCores = strong.copy(cores = 2)
        val smallHeap = strong.copy(memoryClassMb = 128)
        assertEquals(VisualPerformanceTier.MEDIUM, AdaptiveTierResolver.baseTier(fewCores))
        assertEquals(VisualPerformanceTier.MEDIUM, AdaptiveTierResolver.baseTier(smallHeap))
    }

    @Test fun lowAvailableRamNoLongerForcesLow() {
        // A phone that is momentarily tight on RAM must not drop the whole app to LOW.
        val brieflyTight = strong.copy(availMemBytes = 128L * 1024 * 1024)
        assertEquals(VisualPerformanceTier.HIGH, AdaptiveTierResolver.baseTier(brieflyTight))
    }

    @Test fun onlyLowRamOrUnder3GbForcesLow() {
        val smallTotalRam = strong.copy(totalMemBytes = (2.5 * 1024 * 1024 * 1024).toLong())
        assertEquals(VisualPerformanceTier.LOW, AdaptiveTierResolver.baseTier(smallTotalRam))
        assertEquals(
            VisualPerformanceTier.LOW,
            AdaptiveTierResolver.baseTier(strong.copy(isLowRamDevice = true)),
        )
    }

    @Test fun automaticStepDownNeverReachesLowFromMedium() {
        assertEquals(VisualPerformanceTier.MEDIUM, AdaptiveTierResolver.stepDown(VisualPerformanceTier.HIGH))
        assertEquals(VisualPerformanceTier.MEDIUM, AdaptiveTierResolver.stepDown(VisualPerformanceTier.MEDIUM))
        assertEquals(VisualPerformanceTier.LOW, AdaptiveTierResolver.stepDown(VisualPerformanceTier.LOW))
    }

    @Test fun mediumBaseTierStaysMediumUnderBatterySaver() {
        val resolver = AdaptiveTierResolver()
        val mediumDevice = strong.copy(cores = 4)
        assertEquals(
            VisualPerformanceTier.MEDIUM,
            resolver.resolve(PerformanceMode.AUTOMATIC, mediumDevice.copy(batterySaver = true), 0L),
        )
    }
}
