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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
 * - Blur 0%, Low mode, or API < 31: the mapped fill + hairline. The dock draws only
 *   [GlassMapping.dockFill] (fully clear at 0% opacity); other surfaces add a faint legibility
 *   scrim under the cream fill.
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
    // Dock opacity must move ONLY the dock fill's alpha, so the dock rim is a fixed optical
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
        // The dock family (nav pill, circles, mini-player and its controls) has exactly ONE
        // material; see [dockGlassMaterial].
        return this.dockGlassMaterial(
            style = com.auralis.app.ui.components.DockStyle.GLASS,
            shape = shape,
            hazeState = if (blur) hazeState else null,
            cornerRadius = cornerRadius,
            refraction = refraction,
            blurBackdrop = blur,
        )
    }

    return this.liquidGlass(
        hazeState = if (blur) hazeState else null,
        shape = shape,
        cornerRadius = cornerRadius,
        fillAlpha = fill,
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
 * The ONE dock material, used by the dock tab pill, the dock circles, the mini-player pill and the
 * Appearance preview, so all four stay byte-identical.
 *
 * - SOLID: opaque palette surface with the dock's soft shadow. No blur, no rim.
 * - GLASS: real Haze backdrop blur (heavy), the single "Dock opacity" fill
 *   ([GlassMapping.dockFill]), a 1dp cream rim bright on opposite corners, and a soft inner top
 *   sheen. No refraction by default; the AGSL lens is a DEBUG-only experiment
 *   ([com.auralis.app.domain.model.AppSettings.dockLensExperimental]).
 *
 * Every alpha/radius comes from [GlassMapping] via [LocalGlassSettings] / [LocalDockGlassStyle];
 * the caller only chooses the [style], the [shape] and (for SOLID) the fill. Icons, labels and
 * artwork are children and therefore always stay crisp: only what is *behind* is ever blurred.
 */
@Composable
fun Modifier.dockGlassMaterial(
    style: com.auralis.app.ui.components.DockStyle,
    shape: Shape,
    hazeState: HazeState? = LocalHazeState.current,
    solidFill: Color? = null,
    cornerRadius: Dp = GlassTokens.RadiusXl,
    /** Off by default: the shipped dock has no AGSL lens. See [DockGlassStyle.lensExperimental]. */
    refraction: Boolean = false,
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
    val opacity = settings.dockOpacity.coerceIn(0f, 1f)

    // ONE fill for the whole dock family. At/above 98% it is effectively solid, so a Haze sample and
    // the AGSL lens pass would cost frames for pixels nobody can see: skip both.
    val fill = GlassMapping.dockFill(opacity)
    val opaque = fill.alpha >= 0.98f

    val blurWanted = blurBackdrop && !opaque && profile.blurEnabled && dock.blurDp > 0.5f
    val canBlur = blurWanted && hazeState != null
    val blurFraction = (dock.blurDp / GlassMapping.MAX_BLUR_DP).coerceIn(0f, 1f)

    // The tier never changes the radius; it only changes Haze cost (Medium is 0.8x the input
    // scale) or turns blur off entirely (Low).
    val tier = profile.tier
    val costFactor = if (tier == com.auralis.app.ui.performance.VisualPerformanceTier.MEDIUM) 0.8f else 1f
    val hazeScale = (GlassMapping.hazeInputScale(dock.blurDp) * costFactor).coerceIn(0.25f, 1f)

    // The shipped dock has NO refraction. The AGSL lens only runs when the DEBUG experiment is on;
    // when it is, the layer is forced offscreen first so the shader cannot sample its own output.
    val canRefract = (refraction || dock.lensExperimental) && canBlur && dock.refractionEnabled &&
        tier != com.auralis.app.ui.performance.VisualPerformanceTier.LOW &&
        Build.VERSION.SDK_INT >= 33

    val edgePx = with(LocalDensity.current) { 16.dp.toPx() }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    // Refraction is 0.6x at 0% blur and 1.0x at full blur, so the edge never reads as warped glass.
    val refrScale = 0.6f + 0.4f * blurFraction
    val saturation = GlassMapping.dockSaturation(blurFraction)

    // Haze contributes ONLY the blurred backdrop. Noise stays zero (so nothing greys the artwork);
    // the 4% additive white tint is the brightness lift, done without an AGSL pass.
    val hazeStyle = remember(dock.blurDp) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(Color.White.copy(alpha = GlassMapping.DOCK_BRIGHTNESS), BlendMode.Plus)),
            blurRadius = dock.blurDp.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.Transparent),
        )
    }

    // A translucent pane must not show the black elevation shadow through itself: through the
    // fill it reads as dirt, not depth. Keep the shadow only once the fill is dense enough.
    var modifier = this
    if (fill.alpha >= 0.6f) {
        modifier = modifier.shadow(
            elevation = 10.dp * dock.shadowScale,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.20f),
            spotColor = Color.Black.copy(alpha = 0.28f),
        )
    }
    modifier = modifier.clip(shape)

    if (canRefract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier = modifier.lensRefraction(
            radiusPx = radiusPx,
            edgePx = edgePx,
            refract = 0.35f * refrScale,
            chroma = 0.012f * refrScale,
            saturation = saturation,
            brightness = 0f,
            // The fill is the brightness lift (Haze tint); the lens must not double it.
            offscreenLayer = dock.lensExperimental,
        )
    }
    modifier = if (canBlur) {
        modifier.hazeEffect(hazeState, hazeStyle) {
            // Explicit: Haze defaults blur off below Android 12 and would draw a scrim instead.
            this.blurEnabled = true
            // Radius comes from the dock style (0..80dp), never from Haze's own default.
            this.blurRadius = dock.blurDp.dp
            inputScale = if (dock.hazeDefaultScale) {
                HazeInputScale.Auto
            } else {
                HazeInputScale.Fixed(hazeScale)
            }
        }
    } else {
        modifier
    }

    // Nested controls (blurBackdrop = false) sit inside an already-blurred pill: they must NOT draw
    // the dock fill again, only a faint cream bubble + the rim so they read as raised glass.
    modifier = if (blurBackdrop) {
        // The ONE dock fill, over the blurred backdrop. No black scrim, no tint floor: opacity 0
        // stays genuinely clear (blur + rim only) even when the platform cannot blur.
        modifier.background(fill)
    } else {
        modifier.background(GlassTokens.Cream.copy(alpha = 0.10f))
    }

    // Rim is gated by "Glass outlines"; the inner sheen stays either way.
    if (dock.outlines) modifier = modifier.border(1.dp, dockRim(), shape)
    return modifier.dockInnerSheen()
}

/**
 * Dock rim: a 1dp cream sweep, 55% at the top-left fading to 12% at the bottom-right. The dip in
 * the middle is what makes a flat translucent shape read as a glass edge.
 */
private fun dockRim(): Brush = Brush.linearGradient(
    0.00f to GlassTokens.Cream.copy(alpha = 0.55f),
    0.50f to GlassTokens.Cream.copy(alpha = 0.10f),
    1.00f to GlassTokens.Cream.copy(alpha = 0.12f),
)

/** Soft inner top sheen: cream 8% at the top fading to nothing by 40% of the height. */
private fun Modifier.dockInnerSheen(): Modifier = this.drawWithCache {
    val sheen = Brush.verticalGradient(
        0f to GlassTokens.Cream.copy(alpha = 0.08f),
        1f to Color.Transparent,
        startY = 0f,
        endY = (size.height * 0.40f).coerceAtLeast(1f),
    )
    onDrawBehind { drawRect(sheen) }
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