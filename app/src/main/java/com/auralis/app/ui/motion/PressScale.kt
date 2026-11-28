package com.auralis.app.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.theme.AuralisMotion

/**
 * Tactile press feedback: the surface sinks a few percent while a finger is down and springs back
 * with a small overshoot on release. The scale is read in the draw phase only, so a press never
 * recomposes the screen. Respects "Remove animations".
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.965f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = AuralisMotion.springPress,
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
