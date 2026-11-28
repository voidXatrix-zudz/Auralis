package com.auralis.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.theme.AuralisMotion
import kotlinx.coroutines.delay

/**
 * Rows and tiles float up and fade in one after another when a screen first appears.
 *
 * [index] sets the delay (capped at [maxStagger] steps so a long list never waits). Pass
 * `enabled = false` once the screen's intro is over so rows that scroll in later appear instantly.
 * Alpha and offset are applied in the draw phase, so it costs no recomposition per frame.
 */
@Composable
fun Modifier.staggeredEntrance(
    index: Int,
    enabled: Boolean = true,
    maxStagger: Int = 8,
    stepMillis: Int = 38,
): Modifier {
    if (!enabled || rememberReduceMotion()) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, maxStagger) * stepMillis.toLong())
        progress.animateTo(1f, tween(AuralisMotion.Medium + 60, easing = AuralisMotion.Creamy))
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 20.dp.toPx()
    }
}
