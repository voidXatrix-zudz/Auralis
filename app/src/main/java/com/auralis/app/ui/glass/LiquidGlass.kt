package com.auralis.app.ui.glass

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.auralis.app.ui.theme.GlassTokens

/**
 * the in-composition glass entry point.
 *
 * Every card, row, chip, panel, sheet-in-tree and Now Playing surface routes through here, and here
 * alone: [GlassKind] only chooses the role (Dock vs Panel), and the single [auralisGlass] modifier
 * does the rest by reading [LocalGlassConfig] + [LocalAppBackdrop]. No alpha, blur or outline
 * literal lives below this line.
 *
 * `refraction`, `cornerRadius` and `blur` are kept as parameters purely so the existing call
 * sites (dozens) compile unchanged; the backdrop engine owns all of that now.
 */
@Composable
fun Modifier.glassSurface(
    kind: GlassKind,
    shape: Shape,
    @Suppress("UNUSED_PARAMETER") refraction: Boolean = false,
    @Suppress("UNUSED_PARAMETER") cornerRadius: Dp = GlassTokens.RadiusXl,
    @Suppress("UNUSED_PARAMETER") blur: Boolean = true,
): Modifier {
    val role = if (kind == GlassKind.Dock) GlassRole.Dock else GlassRole.Panel
    return this.auralisGlass(shape, role)
}

/**
 * the ONE dock material, used by the dock tab pill, the dock circles, the mini-player
 * pill and the Appearance preview, so all four stay identical.
 *
 * - SOLID (Normal theme): opaque palette surface with the dock's soft shadow. No blur, no rim.
 * - GLASS: the single [auralisGlass] Dock surface (fixed blur + tint + rim from [GlassTokens]).
 *   A nested control (`blurBackdrop = false`, e.g. the mini-player play/next buttons) must NOT
 *   sample the backdrop a second time; it draws only a faint cream bubble that reads as raised.
 */
@Composable
fun Modifier.dockGlassMaterial(
    style: com.auralis.app.ui.components.DockStyle,
    shape: Shape,
    solidFill: Color? = null,
    @Suppress("UNUSED_PARAMETER") cornerRadius: Dp = GlassTokens.RadiusXl,
    blurBackdrop: Boolean = true,
): Modifier {
    if (style == com.auralis.app.ui.components.DockStyle.SOLID) {
        val fill = solidFill ?: MaterialTheme.colorScheme.surface
        return this
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(fill)
    }

    return if (blurBackdrop) {
        this.auralisGlass(shape, GlassRole.Dock)
    } else {
        // A nested control inside an already-glassed pill: no second backdrop sample.
        this
            .clip(shape)
            .background(GlassTokens.Cream.copy(alpha = GlassTokens.NESTED_BUBBLE_ALPHA))
    }
}

/**
 * The active-tab lens: a clear white bubble rather than a solid cream capsule. It carries no
 * backdrop blur of its own (it sits on the already-glassed nav pill). Its rim follows the same
 * "Glass outlines" setting as every other surface .
 */
@Composable
fun Modifier.glassBubble(shape: Shape = CircleShape): Modifier {
    val config = LocalGlassConfig.current
    var modifier = this
        .clip(shape)
        .background(Color.White.copy(alpha = GlassTokens.BUBBLE_FILL_ALPHA))
    if (config.outlinesEnabled) {
        modifier = modifier.border(GlassTokens.RIM_WIDTH_DP.dp, dockRim(), shape)
    }
    return modifier
}

/**
 * Tint-only glass for small controls that live *inside* an already-blurred panel (chips, icon
 * buttons, search fields). It follows the central panel tint mapping but skips a nested backdrop
 * sample: sampling again for a 40dp chip costs frames for no visible difference.
 */
@Composable
fun Modifier.glassTint(
    shape: Shape,
    kind: GlassKind = GlassKind.Surface,
): Modifier {
    val config = LocalGlassConfig.current
    val tintAlpha = when (kind) {
        GlassKind.Dock -> GlassMappings.dockTintAlpha(config.dockTransparency)
        GlassKind.Surface -> GlassMappings.panelTintAlpha(config.glassClarity)
    }
    val fill = maxOf(tintAlpha, GlassMapping.NESTED_MIN_FILL)
    var modifier = this.background(GlassTokens.Cream.copy(alpha = fill.coerceIn(0f, 0.94f)))
    if (config.outlinesEnabled) {
        modifier = modifier.border(
            GlassTokens.RIM_WIDTH_DP.dp,
            GlassTokens.Cream.copy(alpha = GlassTokens.RIM_ALPHA_BOTTOM_RIGHT),
            shape,
        )
    }
    return modifier
}

/** Dock rim: a 1dp white sweep, 60% at the top-left, 8% in the middle and 35% at the bottom-right. */
private fun dockRim(): Brush = Brush.linearGradient(
    0.00f to Color.White.copy(alpha = 0.60f),
    0.50f to Color.White.copy(alpha = 0.08f),
    1.00f to Color.White.copy(alpha = 0.35f),
)

/**
 * window-level glass fill for dialogs, menus and sheets.
 *
 * Those surfaces live in their own window and cannot sample the app's in-composition layer, so
 * [DialogBackdropBlur] blurs the page behind the window (API 31+) and this fill carries readability
 * with a high panel tint. Never transparent, so cream text always has a base.
 */
@Composable
fun glassWindowPanelColor(): Color {
    val config = LocalGlassConfig.current
    val blurAllowed = LocalGlassBlurAllowed.current
    val blurDp = if (blurAllowed) GlassMappings.blurDp(config.tier) else 0f
    val canBlur = Build.VERSION.SDK_INT >= 31 && blurDp > 0.5f
    val alpha = if (canBlur) {
        // "High panel tint" : the window cannot sample the layer, so lean solid.
        maxOf(GlassMappings.panelTintAlpha(config.glassClarity), 0.62f)
    } else {
        GlassMapping.WINDOW_NO_BLUR_TINT
    }
    return GlassTokens.Canvas.copy(alpha = alpha)
}

/**
 * The shared material for every surface that lives in a dialog or bottom-sheet window: rounded clip,
 * panel tint and, when outlines are on, the palette hairline.
 */
@Composable
fun Modifier.glassWindowSurface(
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusCard),
    outlined: Boolean = true,
): Modifier {
    val config = LocalGlassConfig.current
    var modifier = this
        .clip(shape)
        .background(glassWindowPanelColor())
    if (outlined && config.outlinesEnabled) {
        modifier = modifier.border(GlassTokens.RIM_WIDTH_DP.dp, GlassTokens.Cream14, shape)
    }
    return modifier
}

/**
 * Blurs the app content behind a dialog/sheet/popup window on Android 12+. Those windows do not
 * sample the Activity's layer, so this is how the glass setting reaches them. The radius follows the
 * same fixed [GlassTokens] constant as every other surface.
 */
@Composable
fun DialogBackdropBlur() {
    val view = LocalView.current
    val config = LocalGlassConfig.current
    val blurAllowed = LocalGlassBlurAllowed.current
    val radiusDp = if (blurAllowed) GlassMappings.blurDp(config.tier) else 0f
    val radiusPx = with(LocalDensity.current) { radiusDp.dp.roundToPx() }
    LaunchedEffect(radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            runCatching {
                if (radiusPx > 0) {
                    dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    dialogWindow.setBackgroundBlurRadius(radiusPx)
                } else {
                    dialogWindow.setBackgroundBlurRadius(0)
                }
                // The panel draws its own tint; keep the platform dim minimal.
                dialogWindow.setDimAmount(0f)
            }
            return@LaunchedEffect
        }
        applyPopupBlurBehind(view, radiusPx)
    }
}

/**
 * Menu popups are added straight to the WindowManager, so the only handle on their window is the
 * root view's layout params. Updating them re-applies the same blur-behind request the dialog
 * branch uses.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun applyPopupBlurBehind(view: View, radiusPx: Int) {
    val root = view.rootView
    val params = root.layoutParams as? WindowManager.LayoutParams ?: return
    val windowManager = root.context.getSystemService(WindowManager::class.java) ?: return
    runCatching {
        if (radiusPx > 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = radiusPx
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.blurBehindRadius = 0
        }
        windowManager.updateViewLayout(root, params)
    }
}
