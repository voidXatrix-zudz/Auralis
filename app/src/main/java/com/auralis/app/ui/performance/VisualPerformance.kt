package com.auralis.app.ui.performance

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

enum class VisualPerformanceTier { HIGH, MEDIUM, LOW }

/** Kept for compatibility with the theme entry points; new code reads [LocalPerformanceProfile]. */
val LocalVisualPerformance = staticCompositionLocalOf { VisualPerformanceTier.MEDIUM }

/** Thin hardware detection used by tests and by the monitor. */
object VisualPerformance {
    fun detect(context: Context): VisualPerformanceTier =
        AdaptiveTierResolver.baseTier(DevicePerformanceMonitor(context).conditions.value)
}
