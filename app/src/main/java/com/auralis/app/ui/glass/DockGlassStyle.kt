package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.ui.performance.PerformanceProfile

/**
 * The concrete glass values the dock, mini-player, cards and dialogs render with.
 *
 * One object is resolved at the root from the two user controls
 * ([AppSettings.dockGlassTransparency], [AppSettings.dockGlassBlur]), the Glass clarity slider
 * and the active [PerformanceProfile]. Everything reads it through [LocalDockGlassStyle], so a
 * slider drag updates the dock in the same frame as the settings screen.
 */
@Immutable
data class DockGlassStyle(
    /** Smoked tint alpha over the blurred backdrop. 0 = nearly clear, 1 = solid tinted. */
    val tintAlpha: Float = 0.38f,
    /** Effective backdrop blur radius in dp after the performance cap. */
    val blurDp: Float = 30f,
    /** True only when live backdrop blur can actually run (API 31+, not Low mode, radius > 0). */
    val blurEnabled: Boolean = true,
    /** AGSL lens pass; only on large surfaces and only in High. */
    val refractionEnabled: Boolean = true,
    val outlines: Boolean = true,
    val shadowScale: Float = 1f,
    /** Haze backdrop downsample (0.5 in Medium, 1.0 elsewhere). */
    val downsample: Float = 1f,
    /** Why blur is limited, shown under the control. Null when nothing is limited. */
    val limitedReason: String? = null,
)

val LocalDockGlassStyle = staticCompositionLocalOf { DockGlassStyle() }

/**
 * Clear endpoints:
 *  - Transparency 0%  -> ~0.05 tint (nearly clear glass)
 *  - Transparency 100% -> ~0.82 tint (solid tinted surface)
 *  - Blur 0% -> 0dp (no blur), Blur 100% -> 40dp, then scaled by clarity and the profile.
 */
fun resolveDockGlass(
    settings: AppSettings,
    profile: PerformanceProfile,
    systemReason: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DockGlassStyle {
    val clarity = settings.glassClarity.coerceIn(0, 100) / 100f
    val transparency = settings.dockGlassTransparency.coerceIn(0, 100) / 100f
    val blurPercent = settings.dockGlassBlur.coerceIn(0, 100) / 100f

    val baseTint = 0.05f + 0.77f * transparency
    // Clarity is frost strength: higher clarity = denser tint, lower clarity = clearer glass.
    val clarityScale = 0.88f + 0.30f * clarity
    var tint = (baseTint * clarityScale).coerceIn(0.04f, 0.86f)
    if (!profile.blurEnabled) {
        // No live blur: keep a little more tint so icons stay readable over moving artwork.
        tint = (tint + 0.06f).coerceIn(0.04f, 0.90f)
    }

    // Blur comes only from the Live blur slider; clarity never fights it.
    val userBlur = 40f * blurPercent
    val effectiveBlur = (userBlur * profile.blurScale).coerceIn(0f, 40f)
    val apiSupportsBlur = sdkInt >= 31
    val blurEnabled = apiSupportsBlur && profile.blurEnabled && effectiveBlur > 0.5f

    val reason = when {
        sdkInt < 31 -> "Live blur needs Android 12 or newer"
        userBlur <= 0.5f -> null
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
