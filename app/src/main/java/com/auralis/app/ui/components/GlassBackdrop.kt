package com.auralis.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.theme.GlassTokens

/**
 * App-wide background. NORMAL reproduces the original solid background exactly.
 * GLASSMORPHIC draws obsidian with slow artwork-derived ambient gradients (no blur, so it is cheap).
 * The same background is drawn on every performance profile.
 */
@Composable
fun AuralisBackdrop(
    themeStyle: ThemeStyle,
    artColor: Int?,
    tier: VisualPerformanceTier,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = MaterialTheme.colorScheme.background
    if (themeStyle != ThemeStyle.GLASSMORPHIC) {
        Box(modifier.fillMaxSize().background(base), content = content)
        return
    }

    val seed = if (artColor != null) Color(artColor) else GlassTokens.Violet
    val targets = remember(seed) { Triple(seed.tone(0f), seed.tone(38f), seed.tone(-42f)) }
    // The ambient pools are decorative: Low mode and "reduce animations" snap them instead of
    // running a per-frame colour animation. Everything else keeps the exact same palette.
    val profile = LocalPerformanceProfile.current
    val reduceMotion = rememberReduceMotion()
    val animateAmbient = profile.ambientMotionEnabled && !reduceMotion
    val c0 = if (animateAmbient) animateColorAsState(targets.first, tween(700), label = "ambient0") else rememberUpdatedState(targets.first)
    val c1 = if (animateAmbient) animateColorAsState(targets.second, tween(700), label = "ambient1") else rememberUpdatedState(targets.second)
    val c2 = if (animateAmbient) animateColorAsState(targets.third, tween(700), label = "ambient2") else rememberUpdatedState(targets.third)

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            // Cache the four full-screen brushes; only the animated colours or a size change
            // invalidates the cache, so unrelated redraws stay allocation-free.
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h)
                val pool0 = Brush.radialGradient(listOf(c0.value.copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.80f, h * 0.15f), r * 0.65f)
                val pool1 = Brush.radialGradient(listOf(c1.value.copy(alpha = 0.30f), Color.Transparent), Offset(w * 0.18f, h * 0.72f), r * 0.60f)
                val pool2 = Brush.radialGradient(listOf(c2.value.copy(alpha = 0.22f), Color.Transparent), Offset(w * 0.50f, h * 0.40f), r * 0.50f)
                val vignette = Brush.verticalGradient(listOf(base.copy(alpha = 0.20f), base.copy(alpha = 0.52f), base.copy(alpha = 0.88f)))
                onDrawBehind {
                    // Atmospheric Neo lighting: dual complementary luminous pools
                    drawRect(pool0)
                    drawRect(pool1)
                    drawRect(pool2)
                    // Velvety dark vignette overlay ensuring supreme foreground contrast
                    drawRect(vignette)
                }
            },
        content = content,
    )
}

/** Hue-shifted, saturation-capped variant so ambient glow stays soft and text contrast stays high. */
private fun Color.tone(hueShift: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
    hsv[1] = hsv[1].coerceIn(0.25f, 0.60f)
    hsv[2] = 0.78f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
