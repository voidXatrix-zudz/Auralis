package com.auralis.app.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Softly dissolves content under the top edge without adding a color layer of its own.
 */
fun Modifier.fadeTopEdge(
    height: Dp = 20.dp,
    strength: () -> Float = { 1f },
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val s = strength().coerceIn(0f, 1f)
        if (s > 0f) {
            val h = height.toPx().coerceAtMost(size.height)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 1f - s), Color.Black),
                    startY = 0f,
                    endY = h,
                ),
                size = Size(size.width, h),
                blendMode = BlendMode.DstIn,
            )
        }
    }
