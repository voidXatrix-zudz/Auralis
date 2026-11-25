package com.auralis.app.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalGlassOptics
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * The one glass material used by the dock, mini-player, cards and dialogs.
 *
 * - Blur allowed + API 33+: Haze backdrop blur + an AGSL refraction pass on non-dock surfaces.
 * - Blur allowed + API 31-32: Haze backdrop blur only.
 * - Blur 0%, manual Low mode, Reduce blur and effects, or API < 31: the mapped fill + hairline.
 *   The dock draws only [GlassMapping.dockFill] (fully clear at 0% opacity); other surfaces add a
 *   faint legibility scrim under the cream fill. Automatic never disables blur.
 *
 * The caller only chooses [GlassKind]; every alpha/radius comes from the central
 * [GlassMapping] via [LocalGlassSettings]. No component passes a hardcoded tint or radius.
 */
@Composable
fun Modifier.glassSurface(
    kind: GlassKind,
    shape: Shape,
    hazeState: HazeState? = LocalHazeState.current,
    refraction: Boolean = false,
    cornerRadius: Dp = GlassTokens.RadiusXl,
    blur: Boolean = true,
): Modifier {
    val settings = LocalGlassSettings.current
    val profile = LocalPerformanceProfile.current
    val perf = LocalDockGlassStyle.current
    val optics = LocalGlassOptics.current
    // Blur is a property of the glass look, not of the performance tier. It comes from the single
    // policy provided at the root, so the dock, cards and preview can never disagree about it.
    val blurAllowed = LocalGlassBlurAllowed.current

    val fill = GlassMapping.fillAlpha(kind, settings)
    // Dock opacity must move ONLY the dock fill's alpha, so the dock rim is a fixed optical
    // value; Glass clarity keeps driving the hairline of every other surface.
    val borderAlpha = if (!perf.outlines) {
        0f
    } else if (kind == GlassKind.Dock) {
        GlassMapping.DOCK_BORDER_ALPHA
    } else {
        GlassMapping.borderAlpha(settings.glassClarity)
    }
    val blurDp = if (blur && blurAllowed) GlassTokens.blurDpFor(profile.tier) else 0f

    if (kind == GlassKind.Dock) {
        // The dock family (nav pill, circles, mini-player and its controls) has exactly ONE
        // material; see [dockGlassMaterial].
        return this.dockGlassMaterial(
            style = com.auralis.app.ui.components.DockStyle.GLASS,
            shape = shape,
            hazeState = hazeState,
            cornerRadius = cornerRadius,
            blurBackdrop = blur,
        )
    }

    return this.liquidGlass(
        hazeState = hazeState,
        shape = shape,
        cornerRadius = cornerRadius,
        fillAlpha = fill,
        blurDp = blurDp,
        saturation = optics.saturation,
        borderAlpha = borderAlpha,
        refraction = refraction && perf.refractionEnabled && profile.refractionEnabled,
        blurEnabled = blur && blurAllowed,
        downsample = perf.downsample,
        sheen = perf.shadowScale > 0.5f,
        scrimAlpha = GlassMapping.LEGIBILITY_SCRIM,
        noBlurMinAlpha = GlassMapping.NO_BLUR_MIN_FILL,
        fallbackSurface = GlassTokens.Cream,
    )
}

/**
 * The ONE dock material, used by the dock tab pill, the dock circles, the mini-player pill and the
 * Appearance preview, so all four stay byte-identical.
 *
 * - SOLID: opaque palette surface with the dock's soft shadow. No blur, no rim.
 * - GLASS: real Haze backdrop blur, the single "Dock opacity" fill ([GlassMapping.dockFill]),
 *   a 1dp rim, a 3dp inner stroke, a top specular and an additive 45° edge catch. No colour tint:
 *   the blurred content keeps its own colour, so the dock reads as frosted glass over the artwork,
 *   not as a coloured pane.
 *
 * The modifier order is load-bearing and must stay: outside-only shadow, `clip(shape)`,
 * `hazeEffect {}`, then the fill / specular / stroke / rim as INNER content. Nothing may sit above
 * the Haze effect that replaces the layer (a `graphicsLayer { renderEffect }`, a RuntimeShader or
 * `CompositingStrategy.Offscreen`): Haze 1.6 samples the layer behind the effect node, so such a
 * pass would make the dock sample its own output and blur would silently stop.
 *
 * Every alpha/radius comes from [GlassMapping] via [LocalGlassSettings] / [LocalDockGlassStyle];
 * whether blur runs comes from the single [LocalGlassBlurAllowed] policy, never from the
 * performance tier. Icons, labels and artwork are children and therefore always stay crisp: only
 * what is *behind* is ever blurred.
 */

/**
 * Frost grain for the dock blur. Enough that the blur reads as frosted glass rather than a smooth
 * gradient; small enough that it never reads as visible noise. Constant, so it never rebuilds the
 * remembered Haze style (the remember key stays the radius).
 */
private const val DOCK_FROST_NOISE = 0.035f

@Composable
fun Modifier.dockGlassMaterial(
    style: com.auralis.app.ui.components.DockStyle,
    shape: Shape,
    hazeState: HazeState? = LocalHazeState.current,
    solidFill: Color? = null,
    cornerRadius: Dp = GlassTokens.RadiusXl,
    /** False for controls nested inside an already-blurred pill (no second backdrop sample). */
    blurBackdrop: Boolean = true,
): Modifier {
    if (style == com.auralis.app.ui.components.DockStyle.SOLID) {
        val fill = solidFill ?: androidx.compose.material3.MaterialTheme.colorScheme.surface
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

    val profile = LocalPerformanceProfile.current
    val dock = LocalDockGlassStyle.current
    val settings = LocalGlassSettings.current
    // The ONE blur decision: platform + the user's explicit choices. The tier is not consulted.
    val blurAllowed = LocalGlassBlurAllowed.current
    val opacity = settings.dockOpacity.coerceIn(0f, 1f)

    // ONE fill for the whole dock family. At/above 98% it is effectively solid, so a Haze sample
    // would cost frames for pixels nobody can see.
    val fill = GlassMapping.dockFill(opacity)
    val opaque = fill.alpha >= 0.98f

    val blurWanted = blurBackdrop && !opaque && blurAllowed && dock.blurDp > 0.5f
    val canBlur = blurWanted && hazeState != null

    // The tier changes Haze's COST only: the input scale is 0.5 at High and 0.4 at Medium/Low for
    // the reference radius, and it never changes the radius or whether the blur runs at all.
    val tier = profile.tier
    val costFactor = if (tier == com.auralis.app.ui.performance.VisualPerformanceTier.MEDIUM) 0.8f else 1f
    val hazeScale = (GlassMapping.hazeInputScale(dock.blurDp) * costFactor).coerceIn(0.25f, 1f)

    // Haze contributes ONLY the blurred backdrop, with one faint NEUTRAL brightness lift. There is
    // deliberately NO colour tint: the earlier red tint in Saturation blend mode cast a colour over
    // the blurred content (warm artwork / orange stripes made the whole dock read red), which is not
    // the reference look. The blur, the rim and the specular carry the glass instead.
    // The style is remembered on a 0.5dp-snapped radius so slider noise cannot rebuild the Haze
    // child (and re-render its whole subtree) for a change nobody can see.
    val hazeBlurDp = (dock.blurDp * 2f).roundToInt() / 2f
    val hazeStyle = remember(hazeBlurDp) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(
                HazeTint(Color.White.copy(alpha = GlassMapping.DOCK_BRIGHTNESS), BlendMode.Plus),
            ),
            blurRadius = hazeBlurDp.dp,
            // A touch of grain so the blur reads as FROST rather than a smooth glass gradient —
            // the reference dock's frosted micro-texture. Constant (see [DOCK_FROST_NOISE]).
            noiseFactor = DOCK_FROST_NOISE,
            fallbackTint = HazeTint(Color.Transparent),
        )
    }

    // (1) Outside-only soft shadow, always on so a clear pane still floats (the reference glass
    // carries one at every opacity). Kept low — the alpha eases up a little with the fill and never
    // exceeds ~22% black — so through a translucent pane it reads as depth, never dirt. Nested
    // controls (blurBackdrop = false) sit inside an already-shadowed pill and skip it: a second
    // shadow on a 28dp bubble would read as grime on the glass, not a lift.
    var modifier = this
    if (blurBackdrop) {
        val shadowAlpha = 0.12f + 0.10f * fill.alpha
        modifier = modifier.shadow(
            elevation = 12.dp * dock.shadowScale,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = shadowAlpha * 0.7f),
            spotColor = Color.Black.copy(alpha = shadowAlpha),
        )
    }
    // (2) Clip, then (3) the Haze effect: the ONLY backdrop sample for this surface.
    modifier = modifier.clip(shape)
    if (canBlur) {
        modifier = modifier.hazeEffect(hazeState, hazeStyle) {
            // Explicit: Haze defaults blur off below Android 12 and would draw a scrim instead.
            this.blurEnabled = true
            // Radius comes from the dock style (0..80dp), never from Haze's own default.
            this.blurRadius = hazeBlurDp.dp
            inputScale = HazeInputScale.Fixed(hazeScale)
        }
    }

    // (4) Inner content. Nested controls (blurBackdrop = false) sit inside an already-blurred pill:
    // they must NOT draw the dock fill again, only a faint cream bubble so they read as raised.
    modifier = if (blurBackdrop) {
        // The ONE dock fill, over the blurred backdrop. No black scrim, no tint floor: opacity 0
        // stays genuinely clear (blur + rim only) even when the platform cannot blur.
        modifier.background(fill)
    } else {
        modifier.background(GlassTokens.Cream.copy(alpha = 0.10f))
    }

    // Top specular, 3dp inner stroke, additive 45° edge catch and the 1dp rim, all gated by
    // "Glass outlines".
    if (dock.outlines) {
        modifier = modifier
            .dockSpecular()
            .dockInnerStroke(shape)
            .dockEdgeHighlight(shape)
            .border(1.dp, dockRim(), shape)
    }
    return modifier
}

/**
 * Dock rim: a 1dp white sweep, 60% at the top-left, 8% in the middle and 35% at the bottom-right.
 * The dip in the middle is what makes a flat translucent shape read as a glass edge.
 */
private fun dockRim(): Brush = Brush.linearGradient(
    0.00f to Color.White.copy(alpha = 0.60f),
    0.50f to Color.White.copy(alpha = 0.08f),
    1.00f to Color.White.copy(alpha = 0.35f),
)

/** Specular top light: white 10% at the top edge, gone by 40% of the height. */
private fun Modifier.dockSpecular(): Modifier = this.drawWithCache {
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.10f),
        1f to Color.Transparent,
        startY = 0f,
        endY = (size.height * 0.40f).coerceAtLeast(1f),
    )
    onDrawBehind { drawRect(brush) }
}

/**
 * 3dp inner stroke that hugs the shape, white 14% at the top fading inward to 2%. No extra blur:
 * it is a plain gradient stroke, so it stays sharp on the dome of the glass.
 */
private fun Modifier.dockInnerStroke(shape: Shape): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.14f),
        1f to Color.White.copy(alpha = 0.02f),
    )
    onDrawBehind {
        drawOutline(outline, brush = brush, style = Stroke(width = 3.dp.toPx()))
    }
}

/**
 * Additive 45° rim catch: the directional light a glass edge gathers along its top-left, which is
 * the reference dock's signature highlight. Drawn with [BlendMode.Plus], so it can only ever
 * brighten the rim — it never tints the blurred backdrop, which is why the dock itself stays
 * colour-neutral. Cheap: one cached outline and one gradient stroke, no RenderEffect and no
 * second backdrop sample.
 */
private fun Modifier.dockEdgeHighlight(shape: Shape): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.linearGradient(
        0.00f to Color.White.copy(alpha = 0.28f),
        0.45f to Color.White.copy(alpha = 0.02f),
        1.00f to Color.White.copy(alpha = 0.12f),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    onDrawBehind {
        drawOutline(
            outline = outline,
            brush = brush,
            style = Stroke(width = 1.5.dp.toPx()),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * The active-tab lens: a clear white bubble rather than a solid cream capsule. It carries no
 * backdrop blur of its own — it sits on the already-blurred nav pill — so the dock keeps exactly
 * one blur layer per surface.
 */
@Composable
fun Modifier.glassBubble(shape: Shape = androidx.compose.foundation.shape.CircleShape): Modifier {
    return this
        .clip(shape)
        .background(Color.White.copy(alpha = 0.13f))
        .border(1.dp, dockRim(), shape)
}

/**
 * Tint-only glass for small controls that live *inside* an already-blurred panel (chips, icon
 * buttons, search fields). It follows the same central fill mapping, but skips a nested Haze
 * effect: sampling the backdrop again for a 40dp chip costs frames for no visible difference.
 */
@Composable
fun Modifier.glassTint(
    shape: Shape,
    kind: GlassKind = GlassKind.Surface,
): Modifier {
    val settings = LocalGlassSettings.current
    val perf = LocalDockGlassStyle.current
    val axis = when (kind) {
        GlassKind.Dock -> settings.dockOpacity
        GlassKind.Surface -> settings.glassClarity
    }
    val fill = maxOf(GlassMapping.fillAlpha(kind, settings), GlassMapping.NESTED_MIN_FILL)
    var modifier = this
        .background(GlassTokens.Cream.copy(alpha = fill.coerceIn(0f, 0.94f)))
    if (perf.outlines) {
        modifier = modifier.border(
            1.dp,
            GlassTokens.Cream.copy(alpha = GlassMapping.borderAlpha(axis)),
            shape,
        )
    }
    return modifier
}

/**
 * Window-level glass fill. Dialogs and sheets live in their own window and cannot sample the
 * app's Haze layer, so the fill itself must carry readability: a clarity-driven black tint over
 * the blurred backdrop, with a dense black fallback when no blur can run (API < 31 or the user's
 * explicit blur choice). Never transparent, so cream text always has a base.
 *
 * Used by [glassWindowSurface] and by Material sheets that draw their own container colour.
 */
@Composable
fun glassWindowPanelColor(): Color {
    val settings = LocalGlassSettings.current
    val profile = LocalPerformanceProfile.current
    val blurAllowed = LocalGlassBlurAllowed.current
    val blurDp = if (blurAllowed) GlassTokens.blurDpFor(profile.tier) else 0f
    val canBlur = Build.VERSION.SDK_INT >= 31 && blurDp > 0.5f
    val alpha = if (canBlur) {
        GlassMapping.windowTintAlpha(settings.glassClarity)
    } else {
        GlassMapping.WINDOW_NO_BLUR_TINT
    }
    return GlassTokens.Canvas.copy(alpha = alpha)
}

/**
 * The shared material for every surface that lives in a dialog or bottom-sheet window:
 * rounded clip, black scrim, clarity tint and the palette hairline. Pair it with
 * [DialogBackdropBlur] so the window behind is actually blurred on API 31+.
 */
@Composable
fun Modifier.glassWindowSurface(
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusCard),
    outlined: Boolean = true,
): Modifier {
    var modifier = this
        .clip(shape)
        .background(glassWindowPanelColor())
    if (outlined) {
        modifier = modifier.border(1.dp, GlassTokens.Cream14, shape)
    }
    return modifier
}

/**
 * Blurs the app content behind a dialog/sheet/popup window on Android 12+. Those windows do not
 * sample the Activity's Haze layer, so this is the only way the Live blur slider can affect them.
 * The blur radius follows the same 0..40dp mapping as in-window glass.
 *
 * Dialog and sheet content sits under a [DialogWindowProvider] and is blurred through its Window.
 * A `DropdownMenu` lives in a plain popup window with no Window handle, so its
 * `WindowManager.LayoutParams` are updated instead; the tint floor keeps it readable when that is
 * unavailable.
 */
@Composable
fun DialogBackdropBlur() {
    val view = LocalView.current
    val profile = LocalPerformanceProfile.current
    val blurAllowed = LocalGlassBlurAllowed.current
    val radiusDp = if (blurAllowed) GlassTokens.blurDpFor(profile.tier) else 0f
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
                // The panel draws its own black scrim; keep the platform dim minimal so the
                // blurred backdrop is what separates the dialog from the content.
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

@Composable
private fun Modifier.liquidGlass(
    hazeState: HazeState?,
    shape: Shape,
    cornerRadius: Dp,
    fillAlpha: Float,
    blurDp: Float,
    saturation: Float,
    borderAlpha: Float,
    refraction: Boolean,
    blurEnabled: Boolean,
    downsample: Float,
    sheen: Boolean,
    scrimAlpha: Float,
    noBlurMinAlpha: Float,
    fallbackSurface: Color,
): Modifier {
    val tint = fillAlpha.coerceIn(0f, 0.92f)
    val scrim = scrimAlpha.coerceIn(0f, 0.90f)
    val hazeStyle = remember(blurDp, fallbackSurface, noBlurMinAlpha) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            // Haze contributes ONLY the blurred backdrop. The cream fill is drawn by this
            // modifier below the effect, because Haze draws nothing when no source area is usable
            // (for example a card inside the captured scroll pane, whose source is its own
            // ancestor and is therefore excluded). Relying on Haze tints would make those
            // surfaces vanish.
            tints = emptyList(),
            blurRadius = blurDp.dp,
            noiseFactor = 0.04f,
            fallbackTint = HazeTint(fallbackSurface.copy(alpha = maxOf(tint, noBlurMinAlpha))),
        )
    }

    val platformCanBlur = blurEnabled && Build.VERSION.SDK_INT >= 31
    val canBlur = platformCanBlur && hazeState != null && blurDp > 0.5f
    val canRefract = refraction && canBlur && Build.VERSION.SDK_INT >= 33
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val edgePx = with(density) { 16.dp.toPx() }
    val refractPx = with(density) { 5.dp.toPx() }
    val chromaPx = with(density) { 1.6.dp.toPx() }

    // Border is outermost so it stays crisp; the graphics layer wraps the Haze output so the
    // shader can sample it; the sheen is innermost so it sits on top of the blur.
    var modifier = this
    if (borderAlpha > 0f) {
        modifier = modifier.border(1.dp, fallbackSurface.copy(alpha = borderAlpha.coerceIn(0f, 0.4f)), shape)
    }
    if (canRefract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier = modifier.lensRefraction(
            radiusPx = radiusPx,
            edgePx = edgePx,
            refract = refractPx / edgePx,
            chroma = chromaPx / edgePx,
            saturation = saturation,
            brightness = 0f,
        )
    }
    modifier = if (canBlur) {
        modifier.hazeEffect(hazeState, hazeStyle) {
            inputScale = HazeInputScale.Fixed(downsample.coerceIn(0.25f, 1f))
        }
    } else {
        modifier
    }
    // The mapped fill is ALWAYS drawn, underneath the blur (a draw modifier after hazeEffect is
    // inner content, so it paints on top of the blurred backdrop). This keeps clarity working and
    // keeps the surface legible even when the platform cannot blur or the slider is at 0.
    val fill = if (platformCanBlur) tint else maxOf(tint, noBlurMinAlpha)
    modifier = modifier
        .background(GlassTokens.Canvas.copy(alpha = scrim))
        .background(fallbackSurface.copy(alpha = fill.coerceIn(0f, 0.94f)))
    if (sheen) modifier = modifier.background(GlassTokens.SurfaceSheen)
    return modifier
}

/**
 * The AGSL refraction layer. Isolated behind [RequiresApi] because every RuntimeShader call used
 * here only exists on API 33+, which is also the only level where a backdrop blur can be sampled.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.lensRefraction(
    radiusPx: Float,
    edgePx: Float,
    refract: Float,
    chroma: Float,
    saturation: Float,
    brightness: Float,
    offscreenLayer: Boolean = false,
): Modifier {
    val shader = remember { LensShader.create() } ?: return this
    val effect = remember(shader) { LensShader.effect(shader) } ?: return this
    return this.graphicsLayer {
        // The experimental dock lens must capture its subtree before the shader runs, otherwise the
        // shader can read back the layer it is drawing into (which is what broke the dock blur).
        if (offscreenLayer) compositingStrategy = CompositingStrategy.Offscreen
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uEdge", edgePx)
        shader.setFloatUniform("uRefract", refract)
        shader.setFloatUniform("uChroma", chroma)
        shader.setFloatUniform("uSaturation", saturation)
        shader.setFloatUniform("uBrightness", brightness)
        renderEffect = effect
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LensShader {
    fun create(): RuntimeShader? = runCatching { RuntimeShader(AGSL_LENS) }.getOrNull()

    fun effect(shader: RuntimeShader): androidx.compose.ui.graphics.RenderEffect? = runCatching {
        android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }.getOrNull()
}

/**
 * Rounded-rect lens: pulls the sampled backdrop inward at the rim, splits the channels for a
 * subtle chromatic edge, boosts saturation, and adds a directional specular catch.
 */
private const val AGSL_LENS = """
uniform shader content;
uniform float2 uSize;
uniform float uRadius;
uniform float uEdge;
uniform float uRefract;
uniform float uChroma;
uniform float uSaturation;
uniform float uBrightness;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

half4 main(float2 fragCoord) {
    float2 center = uSize * 0.5;
    float2 p = fragCoord - center;
    float2 halfSize = center;
    float r = min(uRadius, min(halfSize.x, halfSize.y));
    float d = sdRoundRect(p, halfSize, r);

    // 0 at the rim, 1 once we are ~16dp inside.
    float edge = clamp(-d / max(uEdge, 1.0), 0.0, 1.0);
    float bend = 1.0 - edge;
    bend = bend * bend;

    float eps = 0.75;
    float dx = sdRoundRect(p + float2(eps, 0.0), halfSize, r)
             - sdRoundRect(p - float2(eps, 0.0), halfSize, r);
    float dy = sdRoundRect(p + float2(0.0, eps), halfSize, r)
             - sdRoundRect(p - float2(0.0, eps), halfSize, r);
    float2 n = normalize(float2(dx, dy) + float2(0.0001));

    // uRefract / uChroma are fractions of the 16dp edge band, so the lens scales with the band.
    float displace = uRefract * uEdge * bend;
    float2 inward = -n * displace;
    float2 radial = normalize(p + float2(0.0001)) * (-displace * 0.30);
    float2 uv = fragCoord + inward + radial;
    float2 ca = -n * (uChroma * uEdge * bend);

    half4 base = content.eval(uv);
    half rc = content.eval(uv + ca).r;
    half bc = content.eval(uv - ca).b;
    half4 col = half4(rc, base.g, bc, base.a);

    float lum = dot(float3(col.rgb), float3(0.2126, 0.7152, 0.0722));
    col.rgb = half3(mix(float3(lum), float3(col.rgb), uSaturation));

    float2 lightDir = normalize(float2(-0.65, -0.75));
    float spec = pow(max(dot(n, lightDir), 0.0), 3.0) * bend;
    col.rgb = col.rgb + half3(half(spec * 0.30));
    col.rgb = col.rgb * half(1.0 - 0.05 * bend);
    col.rgb = col.rgb + half3(half(uBrightness));

    return col;
}
"""