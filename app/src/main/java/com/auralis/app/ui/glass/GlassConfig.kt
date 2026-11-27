package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.ui.performance.VisualPerformanceTier

/**
 * the SINGLE source of truth for every glass surface.
 *
 * Nothing below the app root may read DataStore, a preference int, or a hardcoded blur/alpha/outline
 * value: every glass surface reads this one immutable object through [LocalGlassConfig]. Blur and the
 * lens are fixed constants (see `GlassTokens`); the only user-controlled dimensions are
 * [dockTransparency], [glassClarity] and [outlinesEnabled].
 *
 * @property dockTransparency 0..100, user setting. 0 = fully see-through (blur/lens/rim still
 *   visible), 100 = opaque. Never a black or grey wash. Drives the dock family only.
 * @property glassClarity 0..100, user setting. Higher = more see-through. Drives Panel surfaces.
 * @property outlinesEnabled user setting: the rim + hairline on every glass surface.
 * @property performance the persisted [PerformanceMode] (Low / Medium / High / Automatic).
 * @property tier the resolved [VisualPerformanceTier] (Automatic is resolved by the performance
 *   controller against live device conditions). The render decisions use the RESOLVED tier, so
 *   "Automatic" behaves exactly like the tier it picked.
 * @property sdkInt the runtime API level; the capability gates below are derived from it.
 */
@Immutable
data class GlassConfig(
    val dockTransparency: Int = DEFAULT_DOCK_TRANSPARENCY,
    val glassClarity: Int = DEFAULT_GLASS_CLARITY,
    val outlinesEnabled: Boolean = DEFAULT_OUTLINES_ENABLED,
    val performance: PerformanceMode = PerformanceMode.AUTOMATIC,
    val tier: VisualPerformanceTier = VisualPerformanceTier.MEDIUM,
    val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** RenderEffect blur/colour controls need Android 12 (API 31). */
    val isGlassCapable: Boolean get() = sdkInt >= Build.VERSION_CODES.S

    /** The AGSL lens needs Android 13 (API 33) and any tier above Low. */
    val hasLens: Boolean
        get() = sdkInt >= Build.VERSION_CODES.TIRAMISU && tier != VisualPerformanceTier.LOW

    companion object {
        /** Defaults: dock transparency 10 (~6% tint), clarity 60, outlines on. */
        const val DEFAULT_DOCK_TRANSPARENCY = 10
        const val DEFAULT_GLASS_CLARITY = 60
        const val DEFAULT_OUTLINES_ENABLED = true
    }
}

/**
 * Provided ONCE at the app root with [compositionLocalOf] (not `staticCompositionLocalOf`), so a
 * configuration change recomposes only the composables that actually read it.
 */
val LocalGlassConfig = compositionLocalOf { GlassConfig() }

/** Scroll-morph multiplier for dock tint: 0 is fully expanded, 1 is collapsed. */
val LocalDockTransparencyScale = compositionLocalOf { 1f }
