package com.auralis.app.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalGlassOptics
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * The one liquid-glass material used by the dock, mini-player, cards and dialogs.
 *
 * - Blur enabled + API 33+: Haze backdrop blur + an AGSL runtime-shader lens pass.
 * - Blur enabled + API 31-32: Haze backdrop blur only.
 * - Blur disabled (Low mode, user set 0, or API < 31): translucent smoked tint + hairline.
 *
 * The caller decides whether blur is allowed (see [DockGlassStyle]); this modifier never silently
 * overrides that decision, so the Settings hint always matches what is drawn.
 */
@Composable
fun Modifier.liquidGlass(
    hazeState: HazeState?,
    shape: Shape,
    cornerRadius: Dp,
    tintAlpha: Float,
    blurDp: Float,
    saturation: Float,
    outlines: Boolean,
    refraction: Boolean = true,
    blurEnabled: Boolean = true,
    downsample: Float = 1f,
    sheen: Boolean = true,
    fallbackSurface: Color = GlassTokens.Surface,
): Modifier {
    val tint = tintAlpha.coerceIn(0f, 0.92f)
    // Tint-only fallback: slightly stronger so the surface stays readable without blur.
    val fallbackAlpha = (tint + 0.10f).coerceIn(0.06f, 0.94f)
    val hazeStyle = remember(blurDp, tint, fallbackAlpha, fallbackSurface) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(fallbackSurface.copy(alpha = tint))),
            blurRadius = blurDp.dp,
            noiseFactor = 0.04f,
            fallbackTint = HazeTint(fallbackSurface.copy(alpha = fallbackAlpha)),
        )
    }

    val canBlur = blurEnabled && hazeState != null && Build.VERSION.SDK_INT >= 31 && blurDp > 0.5f
    val canRefract = refraction && canBlur && Build.VERSION.SDK_INT >= 33
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val refractPx = with(density) { 5.dp.toPx() }
    val chromaPx = with(density) { 1.6.dp.toPx() }

    val shader = if (canRefract) remember { LensShader.create() } else null
    val effect = remember(shader) { shader?.let { LensShader.effect(it) } }

    // Border is outermost so it stays crisp; the graphics layer wraps the Haze output so the
    // shader can sample it; the sheen is innermost so it sits on top of the blur.
    var modifier = this
    if (outlines) modifier = modifier.border(1.dp, GlassTokens.BorderBrush, shape)
    if (shader != null && effect != null) {
        modifier = modifier.graphicsLayer {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uRadius", radiusPx)
            shader.setFloatUniform("uRefract", refractPx)
            shader.setFloatUniform("uChroma", chromaPx)
            shader.setFloatUniform("uSaturation", saturation)
            renderEffect = effect
        }
    }
    modifier = if (canBlur) {
        modifier.hazeEffect(hazeState!!, hazeStyle) {
            // Medium downsamples the captured backdrop, cutting the blur cost roughly 4x.
            inputScale = HazeInputScale.Fixed(downsample.coerceIn(0.25f, 1f))
        }
    } else {
        modifier.background(fallbackSurface.copy(alpha = fallbackAlpha))
    }
    if (sheen) modifier = modifier.background(GlassTokens.SurfaceSheen)
    return modifier
}

/** One material, three intensities. All glass in the app routes through [glass]. */
enum class GlassLevel { STRONG, MEDIUM, LIGHT }

/**
 * Reusable glass surface. Maps the one resolved [DockGlassStyle] onto safe, readable bands per
 * level and feeds the single [liquidGlass] shader/fallback. Pass [style] when the caller has the
 * exact resolved style (the dock); otherwise the ambient one is used.
 */
@Composable
fun Modifier.glass(
    hazeState: HazeState?,
    shape: Shape,
    level: GlassLevel = GlassLevel.MEDIUM,
    refraction: Boolean = false,
    style: com.auralis.app.ui.glass.DockGlassStyle? = null,
    fallbackSurface: Color = GlassTokens.Surface,
): Modifier {
    val g = style ?: LocalDockGlassStyle.current
    val optics = LocalGlassOptics.current
    // Wide bands so the transparency/clarity sliders visibly move the whole range, while keeping
    // a readability floor. Strong glass can never go fully clear.
    val tint = when (level) {
        GlassLevel.STRONG -> g.tintAlpha.coerceIn(0.18f, 0.80f)
        GlassLevel.MEDIUM -> (g.tintAlpha * 0.92f).coerceIn(0.16f, 0.72f)
        GlassLevel.LIGHT -> (0.10f + 0.16f * g.tintAlpha).coerceIn(0.10f, 0.34f)
    }
    // Quantize the radius so the Haze/RenderEffect is only rebuilt per whole dp, not per frame.
    val blur = if (g.blurEnabled) when (level) {
        GlassLevel.STRONG -> g.blurDp.coerceIn(0f, 32f)
        GlassLevel.MEDIUM -> g.blurDp.coerceIn(0f, 28f)
        GlassLevel.LIGHT -> g.blurDp.coerceIn(0f, 20f)
    } else 0f
    val blurQ = blur.roundToInt().toFloat()
    return liquidGlass(
        hazeState = hazeState,
        shape = shape,
        cornerRadius = GlassTokens.RadiusXl,
        tintAlpha = tint,
        blurDp = blurQ,
        saturation = optics.saturation,
        outlines = g.outlines,
        refraction = refraction && g.refractionEnabled,
        blurEnabled = g.blurEnabled && blurQ > 0.5f,
        downsample = g.downsample,
        sheen = g.shadowScale > 0.5f,
        fallbackSurface = fallbackSurface,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LensShader {    fun create(): RuntimeShader? = runCatching { RuntimeShader(AGSL_LENS) }.getOrNull()

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
