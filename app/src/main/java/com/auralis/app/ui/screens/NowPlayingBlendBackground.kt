package com.auralis.app.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import com.auralis.app.ui.performance.LocalPerformanceProfile

/**
 * The lower half of Now Playing: a soft mesh built from the artwork's own colours.
 *
 * Layers, bottom to top: solid dark base, dominant→darkMuted gradient, three radial colour blobs,
 * a blurred copy of the artwork, and a bottom scrim that keeps white text readable.
 *
 * Cost follows the performance tier: HIGH adds the blurred artwork layer (RenderEffect on API 31+,
 * a high-quality upscale of the 96 px copy below that), MEDIUM keeps the gradients and blobs, LOW
 * keeps only the gradients. Every layer is static per track, so nothing here runs per frame except
 * the 600 ms colour glide on a track change.
 */
@Composable
fun NowPlayingBlendBackground(
    palette: ArtPalette,
    art: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val profile = LocalPerformanceProfile.current
    val low = profile.isLow
    val glide = tween<Color>(if (low) 200 else 600)
    val dominant by animateColorAsState(palette.dominant, glide, label = "npDominant")
    val vibrant by animateColorAsState(palette.vibrant, glide, label = "npVibrant")
    val muted by animateColorAsState(palette.muted, glide, label = "npMuted")
    val darkMuted by animateColorAsState(palette.darkMuted, glide, label = "npDarkMuted")
    val lightVibrant by animateColorAsState(palette.lightVibrant, glide, label = "npLightVibrant")

    Box(modifier.fillMaxSize()) {
        // 1. Base: the darkest art tone, pulled down so the bottom never goes milky.
        Box(Modifier.fillMaxSize().background(darkMuted.capLuma(0.14f)))
        // 2. Vertical gradient from the dominant tone into the dark one.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        dominant.capLuma(0.42f),
                        darkMuted.capLuma(0.22f),
                        darkMuted.capLuma(0.16f),
                    ),
                ),
            ),
        )
        // 3. Large, soft colour blobs: warm at lower left, vibrant in the middle, cool bottom right.
        if (!low) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val warm = Brush.radialGradient(
                            colors = listOf(lightVibrant.copy(alpha = 0.50f), Color.Transparent),
                            center = Offset(w * 0.16f, h * 0.64f),
                            radius = w * 0.62f,
                        )
                        val soft = Brush.radialGradient(
                            colors = listOf(vibrant.copy(alpha = 0.38f), Color.Transparent),
                            center = Offset(w * 0.56f, h * 0.80f),
                            radius = w * 0.55f,
                        )
                        val cool = Brush.radialGradient(
                            colors = listOf(muted.copy(alpha = 0.44f), Color.Transparent),
                            center = Offset(w * 0.94f, h * 0.97f),
                            radius = w * 0.58f,
                        )
                        onDrawBehind {
                            drawRect(warm)
                            drawRect(soft)
                            drawRect(cool)
                        }
                    },
            )
        }
        // 4. Blurred artwork wash. Built once per track; never re-blurred per frame.
        if (art != null && profile.backgroundBlurEnabled && !low) {
            if (Build.VERSION.SDK_INT >= 31) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.30f).blur(60.dp),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                )
            } else {
                // Older devices: the 96 px copy is already soft, so a high-quality upscale is the blur.
                Image(
                    bitmap = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.30f),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            }
        }
        // 5. Readability scrim: nothing over the art, deepening towards the bottom controls.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.40f to Color.Transparent,
                    0.72f to Color.Black.copy(alpha = 0.22f),
                    1f to Color.Black.copy(alpha = 0.42f),
                ),
            ),
        )
    }
}
