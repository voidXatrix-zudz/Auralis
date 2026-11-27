package com.auralis.app.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.auralis.app.BuildConfig
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.glass.backdrop.drawBackdrop
import com.auralis.app.glass.backdrop.effects.blur
import com.auralis.app.glass.backdrop.effects.colorControls
import com.auralis.app.glass.backdrop.effects.lens
import com.auralis.app.glass.backdrop.highlight.Highlight
import com.auralis.app.glass.backdrop.shadow.Shadow
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle
import kotlin.math.roundToInt

/** warn at most once per process that a glass surface fell back to a flat tint. */
private var warnedMissingBackdrop = false

/**
 * which family of glass a surface belongs to. This is the ONLY scoping switch: a Dock
 * surface reads `dockTransparency`, a Panel surface reads `glassClarity`. Nothing else differs.
 */
enum class GlassRole { Dock, Panel }

/**
 * the ONE glass modifier every glass surface in the app uses, and nothing else.
 *
 * It reads ONLY [LocalGlassConfig], [LocalAppBackdrop] and [GlassTokens]:
 *  - no surface may read DataStore, a preference int, or hardcode a blur/alpha/outline number;
 *  - [config] is part of the [remember] keys (with the tier inside it), so a settings change
 *    rebuilds the modifier and the surface repaints in the SAME frame ;
 *  - blur is a fixed [GlassTokens] constant chosen by the resolved tier, never a user slider.
 *
 * Capability fallbacks ():
 *  - no backdrop (Normal theme, API < 31) -> a plain translucent tint + optional hairline;
 *  - API 31-32 -> blur + rim, no lens; API 33+ & tier != Low -> blur + colour + lens + rim;
 *  - Low tier -> blur (16dp) + rim, no lens. The content behind stays blurred at every tier.
 */
@Composable
fun Modifier.auralisGlass(shape: Shape, role: GlassRole): Modifier {
    // read the transient drag value HERE, in the surface's own recompose scope, so a
    // slider tick rebuilds the dock / mini-player / Search circle on the same frame as the
    // Appearance preview. Reading only [LocalGlassConfig] left those surfaces stale: the local is
    // forwarded by the root provider, but a surface whose parameters did not change was skipped
    // and never re-ran this modifier. `LocalGlassDrag` is a static local holding one shared object,
    // so `value` is a plain snapshot read of the app-wide transient config - same instance the root
    // provider forwards, so the rendered values are identical; only the invalidation path differs.
    val config = LocalGlassDrag.current.value ?: LocalGlassConfig.current
    val dockTransparencyScale = if (role == GlassRole.Dock) LocalDockTransparencyScale.current else 1f
    val backdrop = LocalAppBackdrop.current
    // (debug only): a glass-capable surface drawn in the Glassmorphic theme with no
    // recorded backdrop is silently rendering the tint fallback. Warn once so a missing wiring is
    // visible in the in-app Logs screen instead of only on the phone screen.
    if (BuildConfig.DEBUG && backdrop == null && config.isGlassCapable &&
        !warnedMissingBackdrop && LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    ) {
        warnedMissingBackdrop = true
        AppLog.w("Glass", "A glass surface rendered without a recorded backdrop; drew the tint fallback.")
    }
    val glass = remember(config, backdrop, shape, role, dockTransparencyScale) {
        buildAuralisGlass(config, backdrop, shape, role, dockTransparencyScale)
    }
    return this.then(glass)
}

private fun buildAuralisGlass(
    config: GlassConfig,
    backdrop: com.auralis.app.glass.backdrop.backdrops.LayerBackdrop?,
    shape: Shape,
    role: GlassRole,
    dockTransparencyScale: Float,
): Modifier {
    val tintAlpha = when (role) {
        GlassRole.Dock -> GlassMappings.dockTintAlpha((config.dockTransparency * dockTransparencyScale).roundToInt()) *
            GlassTokens.DOCK_TINT_ALPHA_SCALE
        GlassRole.Panel -> GlassMappings.panelTintAlpha(config.glassClarity)
    }
    val tintColor = when (role) {
        GlassRole.Dock -> GlassTokens.TINT_COLOR_DOCK
        GlassRole.Panel -> GlassTokens.TINT_COLOR_PANEL
    }
    val outline = GlassMappings.outlineAlpha(config.outlinesEnabled) > 0f

    // --- Fallback (): no backdrop / API < 31. A solid translucent tint + optional hairline;
    //     never transparent, never sharp content behind. ---
    if (backdrop == null || !config.isGlassCapable) {
        return Modifier
            .clip(shape)
            .background(tintColor.copy(alpha = tintAlpha))
            .then(
                if (outline) {
                    Modifier.border(
                        GlassTokens.RIM_WIDTH_DP.dp,
                        GlassTokens.Cream.copy(alpha = GlassTokens.RIM_ALPHA_BOTTOM_RIGHT),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
    }

    // --- The real glass: sample the recorded layer, blur it, tint it, gate the rim on outlines. ---
    val blurDp = GlassMappings.blurDp(config.tier)
    return Modifier
        .clip(shape)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = GlassTokens.SATURATION)
                if (blurDp > 0f) blur(radius = blurDp * density)
                if (config.hasLens && shape is CornerBasedShape) {
                    lens(
                        refractionHeight = GlassTokens.LENS_HEIGHT_DP * density,
                        refractionAmount = GlassTokens.LENS_AMOUNT_DP * density,
                    )
                }
            },
            highlight = if (outline) {
                {
                    Highlight(
                        width = GlassTokens.RIM_WIDTH_DP.dp,
                        alpha = GlassTokens.RIM_ALPHA_TOP_LEFT,
                    )
                }
            } else {
                null
            },
            shadow = { Shadow.Default },
            onDrawSurface = {
                drawSurfaceTint(tintColor.copy(alpha = tintAlpha))
            },
        )
}

/** The panel/dock tint, drawn over the blurred backdrop and under the content. */
private fun DrawScope.drawSurfaceTint(tint: androidx.compose.ui.graphics.Color) {
    drawRect(tint)
}
