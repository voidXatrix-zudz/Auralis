package com.auralis.app.ui.performance

import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.app.domain.model.PerformanceMode

/**
 * The resolved profile every screen reads. Provided once at the root, updated by
 * [PerformanceController] the moment the mode, Battery Saver or thermal state changes.
 */
val LocalPerformanceProfile = staticCompositionLocalOf { PerformanceProfile.Default }

/** The user's raw choice, for Settings labels only. */
val LocalPerformanceMode = staticCompositionLocalOf { PerformanceMode.AUTOMATIC }

/**
 * Why Automatic stepped down (e.g. "Battery Saver", "Device temperature"), or null.
 * Used by the dock blur hint so the user understands a limited control.
 */
val LocalPerformanceReason = staticCompositionLocalOf<String?> { null }
