package com.auralis.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Generic geometric artwork for artwork-less Top Pick cards. Picked by a stable hash of the id. */
enum class MoodShape { FOUR_POINT_STAR, CHEVRON_STACK, RINGS, DIAGONAL_BANDS, DOTS }

/** Deterministic shape so a card keeps the same shape between launches. */
fun moodShapeFor(seed: Int): MoodShape {
    val size = MoodShape.entries.size
    return MoodShape.entries[((seed % size) + size) % size]
}

/** A tone 12% lighter than [base], used for the drawn shape on a flat mood card. */
fun moodTint(base: Color): Color = lerp(base, HomeCardPalette.TintLight, 0.12f)

/** Draws the mood shape for [seed] over a flat [base] colour field. */
@Composable
fun MoodArtwork(seed: Int, base: Color, modifier: Modifier = Modifier) {
    val shape = remember(seed) { moodShapeFor(seed) }
    val tint = remember(base) { moodTint(base) }
    Canvas(modifier) { drawMoodShape(shape, tint) }
}

private fun DrawScope.drawMoodShape(shape: MoodShape, color: Color) {
    val side = size.minDimension * 0.9f
    if (side <= 0f) return
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    val centerX = left + side / 2f
    val centerY = top + side / 2f

    when (shape) {
        MoodShape.FOUR_POINT_STAR -> {
            val inner = side * 0.18f
            val path = Path().apply {
                moveTo(centerX, top)
                quadraticTo(centerX + inner, centerY - inner, left + side, centerY)
                quadraticTo(centerX + inner, centerY + inner, centerX, top + side)
                quadraticTo(centerX - inner, centerY + inner, left, centerY)
                quadraticTo(centerX - inner, centerY - inner, centerX, top)
                close()
            }
            drawPath(path, color)
        }

        MoodShape.CHEVRON_STACK -> {
            val chevronWidth = side * 0.20f
            val chevronHeight = side * 0.18f
            val step = side * 0.24f
            val path = Path()
            repeat(3) { index ->
                val x = left + side * 0.16f + step * index
                path.moveTo(x, centerY - chevronHeight / 2f)
                path.lineTo(x + chevronWidth, centerY)
                path.lineTo(x, centerY + chevronHeight / 2f)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = side * 0.055f, cap = StrokeCap.Round),
            )
        }

        MoodShape.RINGS -> {
            val stroke = 3.dp.toPx()
            listOf(0.16f, 0.28f, 0.40f).forEach { fraction ->
                drawCircle(
                    color = color,
                    radius = side * fraction,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = stroke),
                )
            }
        }

        MoodShape.DIAGONAL_BANDS -> {
            val band = 28.dp.toPx()
            repeat(4) { index ->
                val x = left - side * 0.24f + index * side * 0.30f
                drawLine(
                    color = color,
                    start = Offset(x, top + side),
                    end = Offset(x + side, top),
                    strokeWidth = band,
                    cap = StrokeCap.Butt,
                )
            }
        }

        MoodShape.DOTS -> {
            val cell = side / 6f
            val radius = side * 0.028f
            repeat(6) { row ->
                repeat(6) { column ->
                    drawCircle(
                        color = color,
                        radius = radius,
                        center = Offset(
                            left + cell * column + cell / 2f,
                            top + cell * row + cell / 2f,
                        ),
                    )
                }
            }
        }
    }
}