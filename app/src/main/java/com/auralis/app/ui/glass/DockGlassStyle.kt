package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.ui.performance.PerformanceProfile
import com.auralis.app.ui.performance.VisualPerformanceTier

/**
 * The performance/optics flags every glass surface renders with, plus the dock's resolved fill and
 * blur for the Appearance preview.
 *
 * The three user controls live in [GlassSettings]; this object only translates them for the
 * performance profile (blur allowed?, downsample, refraction, outlines) and carries the dock's
 * resolved fill/blur for the Appearance preview. Non-dock surfaces read [GlassMapping.fillAlpha]
 * with their own [GlassKind], so the dock and the cards can never leak into each other.
 */
@Immutable
data class DockGlassStyle(
    /** Dock-only resolved fill alpha. Never used by other surfaces. */
    val fillAlpha: Float = GlassMapping.dockFill(GlassMapping.DEFAULT_DOCK_OPACITY).alpha,
    /** The 0..1 "Dock opacity" control (0 = clear, 1 = opaque solid). Never used by other surfaces. */
    val opacity: Float = GlassMapping.DEFAULT_DOCK_OPACITY,
    /** Effective backdrop blur radius in dp after the performance cap (all surfaces). */
    val blurDp: Float = GlassMapping.blurDp(GlassMapping.DEFAULT_BLUR_STRENGTH),
    /** True when live backdrop blur can run (allowed by the profile and the radius is non-zero). */
    val blurEnabled: Boolean = true,
    /** AGSL lens pass; only on large surfaces and only in High. */
    val refractionEnabled: Boolean = true,
    /** DEBUG-only "Dock lens (experimental)": run the AGSL lens on the dock, off by default. */
    val lensExperimental: Boolean = false,
    /** DEBUG-only "Haze input scale: Default": stop pinning Haze's input scale. */
    val hazeDefaultScale: Boolean = false,
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
 *  - Dock opacity 0% -> fully transparent fill (blur + rim only), 100% -> opaque solid.
 *  - Blur 0% -> 0dp, 100% -> 80dp; the tier only changes Haze input scale, never the radius.
 *  - Glass clarity is intentionally NOT read here; it belongs to non-dock surfaces only.
 */
fun resolveDockGlass(
    settings: AppSettings,
    glass: GlassSettings,
    profile: PerformanceProfile,
    systemReason: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DockGlassStyle {
    val opacity = glass.dockOpacity.coerceIn(0f, 1f)
    val blurStrength = glass.blurStrength.coerceIn(0f, 1f)

    val fillAlpha = GlassMapping.dockFill(opacity).alpha
    val effectiveBlur = GlassMapping.blurDp(blurStrength)
    val apiSupportsBlur = sdkInt >= 31
    val blurEnabled = apiSupportsBlur && profile.blurEnabled && effectiveBlur > 0.5f

    val reason = when {
        sdkInt < 31 -> "Live blur needs Android 12 or newer"
        blurStrength <= 0.005f -> null
        !profile.blurEnabled -> "Live blur is off in Low performance mode"
        systemReason != null -> "Limited by $systemReason"
        else -> null
    }

    return DockGlassStyle(
        fillAlpha = fillAlpha,
        opacity = opacity,
        blurDp = effectiveBlur,
        blurEnabled = blurEnabled,
        refractionEnabled = blurEnabled &&
            (profile.tier == VisualPerformanceTier.HIGH || profile.tier == VisualPerformanceTier.MEDIUM),
        lensExperimental = com.auralis.app.BuildConfig.DEBUG && settings.dockLensExperimental,
        hazeDefaultScale = com.auralis.app.BuildConfig.DEBUG && settings.dockHazeDefaultScale,
        outlines = settings.glassOutlines,
        shadowScale = profile.shadowScale,
        downsample = profile.blurDownsample,
        limitedReason = reason,
    )
}