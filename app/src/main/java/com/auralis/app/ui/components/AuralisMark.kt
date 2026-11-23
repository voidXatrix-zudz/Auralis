package com.auralis.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * AURALIS' original compact mark: three stacked, slightly offset arcs (a sound wave).
 *
 * Drawn in code so no reference-app artwork is bundled. Give it a fixed [Modifier.size] or width /
 * height; the arcs scale with the box.
 */
@Composable
fun AuralisMark(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.9f),
) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val stroke = size.minDimension * 0.09f
        val arcWidth = size.width * 0.84f
        val arcHeight = size.height * 0.28f
        val left = (size.width - arcWidth) / 2f
        val firstTop = size.height * 0.12f
        val step = size.height * 0.26f
        repeat(3) { index ->
            drawArc(
                color = color,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(left, firstTop + step * index),
                size = Size(arcWidth, arcHeight),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}