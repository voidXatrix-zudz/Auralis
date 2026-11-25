package com.auralis.app.ui.glass

import android.os.Build
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.VisualPerformanceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * STEP 2.2 - the one writer/reader of the glass preference values.
 *
 * It reuses the existing DataStore-backed [SettingsRepository] (no SharedPreferences, no second
 * copy) and combines the three stored glass values with the RESOLVED performance tier into ONE
 * [GlassConfig] StateFlow. Every value is clamped on read (here) and on write (the setters), so an
 * out-of-range or corrupt stored int can never reach the renderer.
 *
 * Setters are the only way the Appearance UI may change glass: no screen keeps its own copy.
 */
class GlassConfigRepository(
    private val settingsRepository: SettingsRepository,
    private val settings: StateFlow<AppSettings>,
    private val tier: StateFlow<VisualPerformanceTier>,
    private val scope: CoroutineScope,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** The single config every glass surface reads (through `LocalGlassConfig`). */
    val config: StateFlow<GlassConfig> =
        combine(settings, tier) { stored, resolvedTier -> stored.toGlassConfig(resolvedTier) }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = AppSettings().toGlassConfig(VisualPerformanceTier.MEDIUM),
            )

    fun setDockTransparency(value: Int) {
        val clamped = value.coerceIn(0, 100)
        scope.launch { settingsRepository.update { it.copy(dockGlassOpacity = clamped) } }
    }

    fun setGlassClarity(value: Int) {
        val clamped = value.coerceIn(0, 100)
        scope.launch { settingsRepository.update { it.copy(glassClarity = clamped) } }
    }

    fun setOutlines(enabled: Boolean) {
        scope.launch { settingsRepository.update { it.copy(glassOutlines = enabled) } }
    }

    private fun AppSettings.toGlassConfig(resolvedTier: VisualPerformanceTier): GlassConfig =
        GlassConfig(
            dockTransparency = dockGlassOpacity.coerceIn(0, 100),
            glassClarity = glassClarity.coerceIn(0, 100),
            outlinesEnabled = glassOutlines,
            performance = performanceProfile,
            tier = resolvedTier,
            sdkInt = sdkInt,
        )
}
