package com.auralis.app.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
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

/**
 * The one glass material used by the dock, mini-player, cards and dialogs.
 *
 * - Blur enabled + API 33+: Haze backdrop blur + an AGSL runtime-shader lens pass.
 * - Blur enabled + API 31-32: Haze backdrop blur only.
 * - Blur 0%, Low mode, or API < 31: indigo scrim + translucent cream fill + hairline.
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

    val fill = GlassMapping.fillAlpha(kind, settings)
    // Dock transparency must move ONLY the dock fill's alpha, so the dock rim is a fixed optical
    // value; Glass clarity keeps driving the hairline of every other surface.
    val borderAlpha = if (!perf.outlines) {
        0f
    } else if (kind == GlassKind.Dock) {
        GlassMapping.DOCK_BORDER_ALPHA
    } else {
        GlassMapping.borderAlpha(settings.glassClarity)
    }
    val blurDp = if (blur && profile.blurEnabled) {
        GlassMapping.blurDp(settings.blurStrength, profile.blurScale)
    } else {
        0f
    }

    if (kind == GlassKind.Dock) {
        // The dock family (nav pill, Search circle, mini-player and its controls) has its own
        // liquid-glass recipe: real backdrop blur, a faint indigo tint (never a cream/white wash),
        // a gradient rim, an inner top highlight and one curved specular sheen.
        return this.dockLiquidGlass(
            hazeState = if (blur) hazeState else null,
            shape = shape,
            cornerRadius = cornerRadius,
            tintAlpha = fill,
            blurDp = blurDp,
            saturation = optics.saturation,
            borderAlpha = borderAlpha,
            refraction = refraction && perf.refractionEnabled && profile.refractionEnabled,
            blurEnabled = blur && profile.blurEnabled,
            downsample = perf.downsample,
            sheen = perf.shadowScale > 0.5f,
        )
    }

    return this.liquidGlass(
        hazeState = if (blur) hazeState else null,
        shape = shape,
        cornerRadius = cornerRadius,
        tintAlpha = fill,
        blurDp = blurDp,
        saturation = optics.saturation,
        borderAlpha = borderAlpha,
        refraction = refraction && perf.refractionEnabled && profile.refractionEnabled,
        blurEnabled = blur && profile.blurEnabled,
        downsample = perf.downsample,
        sheen = perf.shadowScale > 0.5f,
        scrimAlpha = GlassMapping.LEGIBILITY_SCRIM,
        noBlurMinAlpha = GlassMapping.NO_BLUR_MIN_FILL,
        fallbackSurface = GlassTokens.Cream,
    )
}

/**
 * Liquid glass for the dock family only: nav pill, Search circle, mini-player pill and the small
 * controls that sit on them.
 *
 * The recipe is deliberately thin so it stays smooth at 120 Hz:
 *  1. real Haze backdrop blur (one capture sample per surface, no second blur layer),
 *  2. a faint black tint — never cream/white — so artwork colours glow through,
 *  3. a thin gradient rim, bright on opposite corners,
 *  4. a soft inner top highlight plus one curved specular sheen,
 *  5. the caller's soft shadow (drawn outside this modifier).
 *
 * Icons, labels and artwork are children and therefore always stay crisp: only what is *behind*
 * the surface is ever blurred.
 */
@Composable
private fun Modifier.dockLiquidGlass(
    hazeState: HazeState?,
    shape: Shape,
    cornerRadius: Dp,
    tintAlpha: Float,
    blurDp: Float,
    saturation: Float,
    borderAlpha: Float,
    refraction: Boolean,
    blurEnabled: Boolean,
    downsample: Float,
    sheen: Boolean,
): Modifier {
    // Haze 1.6 blurs with RenderEffect on Android 12+ and with RenderScript on Android 11 and
    // below — but only when blur is explicitly enabled, otherwise it paints a flat scrim (which is
    // exactly the grey wash this material replaces). [platformCanBlur] therefore follows the
    // user/profile decision, not the SDK level.
    val platformCanBlur = blurEnabled && blurDp > 0.5f
    val canBlur = platformCanBlur && hazeState != null
    val canRefract = refraction && canBlur && Build.VERSION.SDK_INT >= 33

    // Haze contributes ONLY the blurred backdrop. Tints are empty and noise is zero because both
    // read as a grey film over the artwork; the black fill below is the only fill.
    val hazeStyle = remember(blurDp) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = emptyList(),
            blurRadius = blurDp.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(GlassTokens.Canvas.copy(alpha = 0.18f)),
        )
    }

    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val refractPx = with(density) { 6.dp.toPx() }
    val chromaPx = with(density) { 1.1.dp.toPx() }

    var modifier = this
    if (borderAlpha > 0f) {
        modifier = modifier.border(1.dp, glassRim(borderAlpha), shape)
    }
    if (canRefract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier = modifier.lensRefraction(radiusPx, refractPx, chromaPx, saturation)
    }
    modifier = if (canBlur) {
        modifier.hazeEffect(hazeState, hazeStyle) {
            // Explicit: Haze defaults blur off below Android 12 and would draw a scrim instead.
            this.blurEnabled = true
            inputScale = HazeInputScale.Fixed(downsample.coerceIn(0.25f, 1f))
        }
    } else {
        modifier
    }

    // Faint black fill. Colour is constant; Dock transparency only moves the alpha. Without a
    // usable backdrop blur the floor keeps cream text legible instead of washing the surface out.
    val fill = if (canBlur) {
        tintAlpha.coerceIn(0f, 0.60f)
    } else {
        maxOf(tintAlpha, GlassMapping.DOCK_NO_BLUR_MIN_FILL).coerceIn(0f, 0.60f)
    }
    modifier = modifier.background(GlassTokens.Canvas.copy(alpha = fill))

    if (sheen) modifier = modifier.glassInnerSheen()
    return modifier
}

/**
 * Thin rim that is bright on opposite corners (top-left and bottom-right) and almost invisible on
 * the other diagonal, which is what makes a flat translucent shape read as a glass edge.
 */
private fun glassRim(alpha: Float): Brush {
    val strong = GlassTokens.Cream.copy(alpha = (alpha * 3.1f).coerceIn(0f, 0.60f))
    val faint = GlassTokens.Cream.copy(alpha = (alpha * 0.55f).coerceIn(0f, 0.14f))
    val medium = GlassTokens.Cream.copy(alpha = (alpha * 0.95f).coerceIn(0f, 0.20f))
    return Brush.linearGradient(
        0.00f to strong,
        0.22f to faint,
        0.50f to medium,
        0.78f to faint,
        1.00f to strong,
    )
}

/**
 * One cheap gradient overlay: a soft inner top highlight (light falling from above) and a single
 * curved specular sheen in the upper-left. Both are size-aware but built once per size change.
 */
private fun Modifier.glassInnerSheen(): Modifier = this.drawWithCache {
    val width = size.width
    val height = size.height
    val topHighlight = Brush.verticalGradient(
        0f to GlassTokens.Cream.copy(alpha = 0.15f),
        0.62f to Color.Transparent,
        startY = 0f,
        endY = (height * 0.78f).coerceAtLeast(1f),
    )
    val curvedSheen = Brush.radialGradient(
        colors = listOf(GlassTokens.Cream.copy(alpha = 0.12f), Color.Transparent),
        center = Offset(width * 0.24f, height * 0.04f),
        radius = (width * 0.92f).coerceAtLeast(height * 1.15f),
    )
    onDrawBehind {
        drawRect(topHighlight)
        drawRect(curvedSheen)
    }
}

/**
 * The active-tab lens: a clear glass bubble rather than a solid cream capsule. It carries no
 * backdrop blur of its own — it sits on the already-blurred nav pill — so the dock keeps exactly
 * one blur layer per surface.
 */
@Composable
fun Modifier.glassBubble(shape: Shape = androidx.compose.foundation.shape.CircleShape): Modifier {
    return this
        .clip(shape)
        .background(GlassTokens.Cream.copy(alpha = 0.10f))
        .border(1.dp, glassRim(GlassMapping.DOCK_BORDER_ALPHA * 1.4f), shape)
        .glassInnerSheen()
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
        GlassKind.Dock -> settings.dockTransparency
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
 * app's Haze layer, so the fill itself must carry readability: a dense black scrim plus the
 * clarity-driven cream tint. Used by [glassWindowSurface] and by Material sheets that draw their
 * own container colour.
 */
@Composable
fun glassWindowPanelColor(): Color {
    val clarity = LocalGlassSettings.current.glassClarity
    val tint = maxOf(GlassMapping.surfaceFillAlpha(clarity), GlassMapping.WINDOW_MIN_FILL)
    return GlassTokens.Cream.copy(alpha = tint)
        .compositeOver(GlassTokens.Canvas.copy(alpha = GlassMapping.WINDOW_SCRIM))
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
 * Blurs the app content behind a dialog/sheet window on Android 12+. A dialog window does not
 * sample the Activity's Haze layer, so this is the only way the Live blur slider can affect it.
 * The blur radius follows the same 0..40dp mapping as in-window glass.
 */
@Composable
fun DialogBackdropBlur() {
    val view = LocalView.current
    val settings = LocalGlassSettings.current
    val profile = LocalPerformanceProfile.current
    val radiusDp = if (profile.blurEnabled) {
        GlassMapping.blurDp(settings.blurStrength, profile.blurScale)
    } else {
        0f
    }
    val radiusPx = with(LocalDensity.current) { radiusDp.dp.roundToPx() }
    LaunchedEffect(radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        runCatching {
            if (radiusPx > 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setBackgroundBlurRadius(radiusPx)
            } else {
                window.setBackgroundBlurRadius(0)
            }
            // The panel draws its own indigo scrim; keep the platform dim minimal so the
            // blurred backdrop is what separates the dialog from the content.
            window.setDimAmount(0f)
        }
    }
}

@Composable
private fun Modifier.liquidGlass(
    hazeState: HazeState?,
    shape: Shape,
    cornerRadius: Dp,
    tintAlpha: Float,
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
    val tint = tintAlpha.coerceIn(0f, 0.92f)
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
    val refractPx = with(density) { 5.dp.toPx() }
    val chromaPx = with(density) { 1.6.dp.toPx() }

    // Border is outermost so it stays crisp; the graphics layer wraps the Haze output so the
    // shader can sample it; the sheen is innermost so it sits on top of the blur.
    var modifier = this
    if (borderAlpha > 0f) {
        modifier = modifier.border(1.dp, fallbackSurface.copy(alpha = borderAlpha.coerceIn(0f, 0.4f)), shape)
    }
    if (canRefract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier = modifier.lensRefraction(radiusPx, refractPx, chromaPx, saturation)
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
        .background(GlassTokens.Indigo.copy(alpha = scrim))
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
    refractPx: Float,
    chromaPx: Float,
    saturation: Float,
): Modifier {
    val shader = remember { LensShader.create() } ?: return this
    val effect = remember(shader) { LensShader.effect(shader) } ?: return this
    return this.graphicsLayer {
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uRefract", refractPx)
        shader.setFloatUniform("uChroma", chromaPx)
        shader.setFloatUniform("uSaturation", saturation)
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
uniform float uRefract;
uniform float uChroma;
uniform float uSaturation;

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

    // 0 at the rim, 1 once we are ~16px inside.
    float edge = clamp(-d / 16.0, 0.0, 1.0);
    float bend = 1.0 - edge;
    bend = bend * bend;

    float eps = 0.75;
    float dx = sdRoundRect(p + float2(eps, 0.0), halfSize, r)
             - sdRoundRect(p - float2(eps, 0.0), halfSize, r);
    float dy = sdRoundRect(p + float2(0.0, eps), halfSize, r)
             - sdRoundRect(p - float2(0.0, eps), halfSize, r);
    float2 n = normalize(float2(dx, dy) + float2(0.0001));

    float2 inward = -n * uRefract * bend;
    float2 radial = normalize(p + float2(0.0001)) * (-uRefract * 0.30 * bend);
    float2 uv = fragCoord + inward + radial;
    float2 ca = -n * uChroma * bend;

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

    return col;
}
"""