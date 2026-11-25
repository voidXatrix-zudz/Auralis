package com.auralis.app.ui.glass

import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.theme.GlassTokens
import kotlin.math.pow

/**
 * STEP 2.5 - the pure mapping functions every glass surface routes through. They are deliberately
 * free of Android/Compose state so they can be unit-tested on the JVM, and they are the ONLY place a
 * transparency/clarity value turns into an alpha, a blur radius or an effect scale.
 *
 * Fixed constants (blur dp, lens dp, saturation, rim alphas, resolution scales) live in
 * [GlassTokens] - see STEP 2.4 - so no blur/alpha/outline literal exists anywhere else.
 */
object GlassMappings {

    /**
     * "Dock transparency" -> the dock tint alpha.
     *
     * `t = dockTransparency / 100`; `alpha = 0.02 + 0.98 * t^1.4`.
     *  - 0   -> 0.02 (almost clear; the blur, lens and rim stay visible);
     *  - 100 -> 1.00 (fully opaque).
     *
     * Monotonic and clamped; it never adds a black or grey tint at any value.
     */
    fun dockTintAlpha(dockTransparency: Int): Float {
        val t = dockTransparency.coerceIn(0, 100) / 100f
        return (0.02f + 0.98f * t.pow(1.4f)).coerceIn(0f, 1f)
    }

    /**
     * "Glass clarity" -> the Panel tint alpha.
     *
     * clarity 100 = most see-through, 0 = most solid: `alpha = 0.92 - 0.80 * clarity/100`
     * (range 0.12 .. 0.92). Monotonic (decreasing) and clamped.
     */
    fun panelTintAlpha(glassClarity: Int): Float {
        val c = glassClarity.coerceIn(0, 100) / 100f
        return (0.92f - 0.80f * c).coerceIn(0f, 1f)
    }

    /**
     * The rim/hairline gate. Outlines OFF => 0 (no rim and no hairline on ANY surface, including the
     * dock and the selection lens). ON => 1, so the fixed [GlassTokens.RIM_ALPHA_TOP_LEFT] and
     * [GlassTokens.RIM_ALPHA_BOTTOM_RIGHT] stops are used at full strength.
     */
    fun outlineAlpha(outlinesEnabled: Boolean): Float = if (outlinesEnabled) 1f else 0f

    /** Fixed backdrop blur radius (dp) for a resolved tier. Low gets the larger fixed radius. */
    fun blurDp(tier: VisualPerformanceTier): Float = GlassTokens.blurDpFor(tier)

    /**
     * The effect-resolution scale for a tier (1.0 = full). The vendored backdrop 1.0.0 has no
     * `backdropScale` parameter, so this is applied through the layer block / documented as
     * full-resolution where the API cannot consume it (see AGENT_NOTES).
     */
    fun effectScale(tier: VisualPerformanceTier): Float = when (tier) {
        VisualPerformanceTier.HIGH -> GlassTokens.EFFECT_RESOLUTION_HIGH
        VisualPerformanceTier.MEDIUM -> GlassTokens.EFFECT_RESOLUTION_MEDIUM
        VisualPerformanceTier.LOW -> GlassTokens.EFFECT_RESOLUTION_LOW
    }
}
