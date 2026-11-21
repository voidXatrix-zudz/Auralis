package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.PerformanceProfile

/**
 * The performance/optics flags every glass surface renders with, plus the dock's resolved fill and
 * blur for the Appearance preview.
 *
 * The three user controls live in [GlassSettings]; this object only translates them for the
 * performance profile (blur allowed?, downsample, refraction, outlines). It deliberately contains
 * NO surface fill value: surfaces read [GlassMapping.fillAlpha] with their own [GlassKind], so the
 * dock and the cards can never leak into each other.
 */
@Immutable
data class DockGlassStyle(
    /** Dock-only fill alpha derived from "Dock transparency". Never used by other surfaces. */
    val tintAlpha: Float = GlassMapping.dockFillAlpha(GlassMapping.DEFAULT_DOCK_TRANSPARENCY),
    /** Effective backdrop blur radius in dp after the performance cap (all surfaces). */
    val blurDp: Float = GlassMapping.blurDp(GlassMapping.DEFAULT_BLUR_STRENGTH, 1f),
    /** True when live backdrop blur can run (allowed by the profile and the radius is non-zero). */
    val blurEnabled: Boolean = true,
    /** AGSL lens pass; only on large surfaces and only in High. */
    val refractionEnabled: Boolean = true,
    val outlines: Boolean = true,
    val shadowScale: Float = 1f,
    /** Haze backdrop downsample (1.0 = full resolution). */
    val downsample: Float = 1f,
    /** Why blur is limited, shown under the control. Null when nothing is limited. */
    val limitedReason: String? = null,
)

val LocalDockGlassStyle = staticCompositionLocalOf { DockGlassStyle() }

/**
 * Resolves the concrete render values from the one [GlassSettings] model.
 *
 * Endpoints:
 *  - Dock transparency 0% -> 0.55 indigo alpha (dense), 100% -> 0.10 (see-through).
 *  - Blur 0% -> 0dp, 100% -> 40dp, scaled by the performance profile.
 *  - Glass clarity is intentionally NOT read here; it belongs to non-dock surfaces only.
 */
fun resolveDockGlass(
    settings: AppSettings,
    glass: GlassSettings,
    profile: PerformanceProfile,
    systemReason: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DockGlassStyle {
    val transparency = glass.dockTransparency.coerceIn(0f, 1f)
    val blurStrength = glass.blurStrength.coerceIn(0f, 1f)

    val tint = GlassMapping.dockFillAlpha(transparency)
    val effectiveBlur = GlassMapping.blurDp(blurStrength, profile.blurScale)
    val apiSupportsBlur = sdkInt >= 31
    val blurEnabled = apiSupportsBlur && profile.blurEnabled && effectiveBlur > 0.5f

    val reason = when {
        sdkInt < 31 -> "Live blur needs Android 12 or newer"
        blurStrength <= 0.005f -> null
        !profile.blurEnabled -> "Live blur is off in Low performance mode"
        systemReason != null -> "Reduced by $systemReason"
        profile.blurScale < 1f -> "Reduced by Performance Mode (${profile.tier.label()})"
        else -> null
    }

    return DockGlassStyle(
        tintAlpha = tint,
        blurDp = effectiveBlur,
        blurEnabled = blurEnabled,
        refractionEnabled = blurEnabled && profile.refractionEnabled,
        outlines = settings.glassOutlines,
        shadowScale = profile.shadowScale,
        downsample = profile.blurDownsample,
        limitedReason = reason,
    )
}

private fun com.auralis.app.ui.performance.VisualPerformanceTier.label(): String = when (this) {
    com.auralis.app.ui.performance.VisualPerformanceTier.HIGH -> "High"
    com.auralis.app.ui.performance.VisualPerformanceTier.MEDIUM -> "Medium"
    com.auralis.app.ui.performance.VisualPerformanceTier.LOW -> "Low"
}