package com.auralis.app.ui.glass

import android.os.Build
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.performance.VisualPerformanceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val _preview = MutableStateFlow<GlassConfigPreview?>(null)

    /** Values being previewed by the Appearance sliders; never written to DataStore. */
    val preview: StateFlow<GlassConfigPreview?> = _preview.asStateFlow()

    /** The single config every glass surface reads (through `LocalGlassConfig`). */
    val config: StateFlow<GlassConfig> =
        combine(settings, tier) { stored, resolvedTier -> stored.toGlassConfig(resolvedTier) }
            .distinctUntilChanged()
            // STEP 10.1: ONE Logs line per GlassConfig change, so the in-app Logs screen shows
            // exactly what the renderer was told without an attached debugger.
            .onEach { config ->
                AppLog.i(
                    "Glass",
                    "GlassConfig dock=${config.dockTransparency} clarity=${config.glassClarity} " +
                        "outlines=${if (config.outlinesEnabled) "on" else "off"} " +
                        "perf=${config.performance.name} lens=${config.hasLens} " +
                        "blurDp=${GlassMappings.blurDp(config.tier).toInt()}",
                )
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = AppSettings().toGlassConfig(VisualPerformanceTier.MEDIUM),
            )

    fun setDockTransparency(value: Int) {
        val clamped = value.coerceIn(0, 100)
        scope.launch { settingsRepository.update { it.copy(dockGlassOpacity = clamped) } }
    }

    fun previewDockTransparency(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _preview.update { (it ?: GlassConfigPreview()).copy(dockTransparency = clamped) }
    }

    fun setGlassClarity(value: Int) {
        val clamped = value.coerceIn(0, 100)
        scope.launch { settingsRepository.update { it.copy(glassClarity = clamped) } }
    }

    fun previewGlassClarity(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _preview.update { (it ?: GlassConfigPreview()).copy(glassClarity = clamped) }
    }

    /** Clears an unfinished slider gesture. A completed gesture remains until DataStore catches up. */
    fun clearPreview() {
        _preview.value = null
    }

    /** Drop the transient copy only after the committed setting is visible in the saved config. */
    fun clearPreviewIfPersisted(config: GlassConfig) {
        val current = _preview.value ?: return
        if (current.matches(config)) _preview.compareAndSet(current, null)
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

data class GlassConfigPreview(
    val dockTransparency: Int? = null,
    val glassClarity: Int? = null,
) {
    fun applyTo(config: GlassConfig): GlassConfig = config.copy(
        dockTransparency = dockTransparency ?: config.dockTransparency,
        glassClarity = glassClarity ?: config.glassClarity,
    )

    fun matches(config: GlassConfig): Boolean =
        (dockTransparency == null || dockTransparency == config.dockTransparency) &&
            (glassClarity == null || glassClarity == config.glassClarity)
}
