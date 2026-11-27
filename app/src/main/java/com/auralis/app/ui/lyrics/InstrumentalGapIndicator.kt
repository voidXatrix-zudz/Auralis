package com.auralis.app.ui.lyrics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Instrumental gap indicator rendering 3 softly pulsing rhythmic dots
 * during musical breaks (>= 4000 ms).
 */
@Composable
fun InstrumentalGapIndicator(
    color: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "InstrumentalGapPulse")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse1",
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse2",
    )

    val pulse3 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse3",
    )

    Row(
        modifier = modifier.padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(pulse1)
                .background(color, shape = CircleShape),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(pulse2)
                .background(color, shape = CircleShape),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(pulse3)
                .background(color, shape = CircleShape),
        )
    }
}
