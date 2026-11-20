package com.auralis.app

import com.auralis.app.ui.performance.RefreshRateTargeting
import com.auralis.app.ui.performance.VisualPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateTargetingTest {

    // (modeId, refreshRateHz) — ids deliberately do not equal the rate, to prove pick picks the id.
    private val modes = listOf(1 to 60f, 2 to 90f, 3 to 120f)

    @Test fun tierMapsToTargetRate() {
        assertEquals(120, RefreshRateTargeting.targetHz(VisualPerformanceTier.HIGH))
        assertEquals(90, RefreshRateTargeting.targetHz(VisualPerformanceTier.MEDIUM))
        assertEquals(60, RefreshRateTargeting.targetHz(VisualPerformanceTier.LOW))
    }

    @Test fun picksExactlyWhenPanelSupportsTheTarget() {
        assertEquals(3, RefreshRateTargeting.pickModeId(modes, 120))
        assertEquals(2, RefreshRateTargeting.pickModeId(modes, 90))
        assertEquals(1, RefreshRateTargeting.pickModeId(modes, 60))
    }

    @Test fun neverAsksAboveTheTarget() {
        // A 60/90 panel asked for 120 must fall back to its fastest mode, 90.
        val panel = listOf(1 to 60f, 2 to 90f)
        assertEquals(2, RefreshRateTargeting.pickModeId(panel, 120))
    }

    @Test fun sixtyHertzPanelRunsEveryTierAtSixty() {
        val panel = listOf(1 to 60f)
        assertEquals(1, RefreshRateTargeting.pickModeId(panel, 120))
        assertEquals(1, RefreshRateTargeting.pickModeId(panel, 90))
        assertEquals(1, RefreshRateTargeting.pickModeId(panel, 60))
    }

    @Test fun usesSlowestModeWhenTargetIsBelowEveryMode() {
        // 90/120-only panel asked for 60: we cannot go below the panel, so pick the slowest.
        val panel = listOf(2 to 90f, 3 to 120f)
        assertEquals(2, RefreshRateTargeting.pickModeId(panel, 60))
    }

    @Test fun toleratesFloatNoise() {
        // A 59.99 Hz report still counts as 60 for a 60 Hz target.
        val panel = listOf(1 to 59.99f, 3 to 120f)
        assertEquals(1, RefreshRateTargeting.pickModeId(panel, 60))
    }

    @Test fun noModesReturnsNull() {
        assertNull(RefreshRateTargeting.pickModeId(emptyList(), 120))
        assertEquals(0f, RefreshRateTargeting.pickRefreshRate(emptyList(), 120), 0.001f)
    }

    @Test fun refreshRateMatchesChosenModeId() {
        assertEquals(120f, RefreshRateTargeting.pickRefreshRate(modes, 120), 0.001f)
        assertEquals(90f, RefreshRateTargeting.pickRefreshRate(modes, 100), 0.001f)
    }
}
