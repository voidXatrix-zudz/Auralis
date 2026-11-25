package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.ui.performance.PerformanceProfile
import com.auralis.app.ui.theme.GlassTokens

/**
 * The performance/optics flags every glass surface renders with, plus the dock's resolved fill and
 * blur for the Appearance preview.
 *
 * The three user controls live in [GlassSettings]; this object resolves them together with the
 * one blur policy ([GlassBlurPolicy]) and the performance *cost* flags (downsample, refraction,
 * outlines), and carries the dock's resolved fill/blur for the Appearance preview. Non-dock
 * surfaces read [GlassMapping.fillAlpha] with their own [GlassKind], so the dock and the cards can
 * never leak into each other.
 */
@Immutable
data class DockGlassStyle(
    /** Dock-only resolved fill alpha. Never used by other surfaces. */
    val fillAlpha: Float = GlassMapping.dockFill(GlassMapping.DEFAULT_DOCK_OPACITY).alpha,
    /** The 0..1 "Dock opacity" control (0 = clear, 1 = opaque solid). Never used by other surfaces. */
    val opacity: Float = GlassMapping.DEFAULT_DOCK_OPACITY,
    /** Fixed backdrop blur radius for the resolved tier (all surfaces). */
    val blurDp: Float = GlassTokens.BLUR_DP_MEDIUM,
    /** True when Live blur is allowed (see [GlassBlurPolicy]) and the radius is non-zero. */
    val blurEnabled: Boolean = true,
    /** AGSL lens pass; allowed by the profile (High only). */
    val refractionEnabled: Boolean = false,
    val outlines: Boolean = true,
    val shadowScale: Float = 1f,
    /** Haze backdrop downsample (1.0 = full resolution). */
    val downsample: Float = 1f,
    /** Why blur is limited, shown under the control. Null when nothing is limited. */
    val limitedReason: String? = null,
)

val LocalDockGlassStyle = compositionLocalOf { DockGlassStyle() }

/**
 * Resolves the concrete render values from the one [GlassSettings] bridge (itself derived from
 * [GlassConfig]).
 *
 * Endpoints:
 *  - Dock transparency 0% -> fully transparent fill (blur + rim only), 100% -> opaque solid.
 *  - Blur is a fixed [GlassTokens] constant per tier (10 dp High/Medium, 16 dp Low).
 *  - Glass clarity is intentionally NOT read here; it belongs to non-dock surfaces only.
 */
fun resolveDockGlass(
    settings: AppSettings,
    glass: GlassSettings,
    profile: PerformanceProfile,
    @Suppress("UNUSED_PARAMETER") systemReason: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DockGlassStyle {
    val opacity = glass.dockOpacity.coerceIn(0f, 1f)

    val fillAlpha = GlassMapping.dockFill(opacity).alpha
    // Blur is a fixed constant now (STEP 2.4): the tier only chooses the radius, never on/off.
    val effectiveBlur = GlassTokens.blurDpFor(profile.tier)
    val blurAllowed = GlassBlurPolicy.allowed(settings, sdkInt)
    val blurEnabled = blurAllowed && effectiveBlur > 0.5f

    // The reason must state the TRUE cause. Battery Saver / thermal no longer limit blur, so they
    // are deliberately absent; there is no blur-strength slider any more either.
    val reason = when {
        sdkInt < 31 -> "Live blur needs Android 12 or newer"
        settings.reduceBlurAndEffects -> "Blur is off: Reduce blur and effects is on"
        settings.performanceProfile == PerformanceMode.LOW -> "Blur is off: Performance is set to Low"
        else -> null
    }

    return DockGlassStyle(
        fillAlpha = fillAlpha,
        opacity = opacity,
        blurDp = effectiveBlur,
        blurEnabled = blurEnabled,
        refractionEnabled = blurEnabled && profile.refractionEnabled,
        outlines = settings.glassOutlines,
        shadowScale = profile.shadowScale,
        downsample = profile.blurDownsample,
        limitedReason = reason,
    )
}