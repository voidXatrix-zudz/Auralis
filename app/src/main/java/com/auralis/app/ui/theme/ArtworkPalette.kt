package com.auralis.app.ui.theme

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Small, dependency-free artwork palette extractor. It samples a bounded grid, ignores near-white
 * and near-black pixels, and prefers a frequent but saturated cluster so the result reads as an
 * accent rather than a muddy average.
 */
object ArtworkPalette {
    fun dominantColor(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val stepX = max(1, bitmap.width / 48)
        val stepY = max(1, bitmap.height / 48)
        val buckets = HashMap<Int, Double>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val alpha = (color ushr 24) and 0xFF
                if (alpha >= 32) {
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val value = max(r, max(g, b)) / 255.0
                    val minChannel = min(r, min(g, b)) / 255.0
                    val saturation = if (value <= 0.0) 0.0 else (value - minChannel) / value
                    if (value in 0.10..0.96 && saturation > 0.12) {
                        val key = ((r / 24) shl 16) or ((g / 24) shl 8) or (b / 24)
                        buckets[key] = (buckets[key] ?: 0.0) + (0.4 + saturation)
                    }
                }
                x += stepX
            }
            y += stepY
        }
        val best = buckets.maxByOrNull { it.value }?.key ?: return null
        val r = ((best shr 16) and 0xFF) * 24 + 12
        val g = ((best shr 8) and 0xFF) * 24 + 12
        val b = (best and 0xFF) * 24 + 12
        return (0xFF shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
    }

    /** Keeps an extracted accent readable as a Material primary colour in dark or light mode. */
    fun readableAccent(argb: Int, darkTheme: Boolean): Int {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return argb
        var r = ((argb shr 16) and 0xFF) / 255.0
        var g = ((argb shr 8) and 0xFF) / 255.0
        var b = (argb and 0xFF) / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        if (darkTheme && luma < 0.45) {
            val lift = (0.45 - luma) / max(0.001, 1.0 - luma)
            r += (1.0 - r) * lift
            g += (1.0 - g) * lift
            b += (1.0 - b) * lift
        } else if (!darkTheme && luma > 0.70) {
            val drop = (luma - 0.70) / max(0.001, luma)
            r -= r * drop
            g -= g * drop
            b -= b * drop
        }
        // Keep a little chroma when the source is close to grey.
        if (maxC - minC < 0.08) {
            r = max(0.0, r - 0.04)
            g = min(1.0, g + 0.06)
            b = min(1.0, b + 0.04)
        }
        return (0xFF shl 24) or
            (r.coerceIn(0.0, 1.0).times(255).toInt() shl 16) or
            (g.coerceIn(0.0, 1.0).times(255).toInt() shl 8) or
            b.coerceIn(0.0, 1.0).times(255).toInt()
    }

    /** Blends two ARGB colours; used for gradients and smooth accent transitions. */
    fun mix(first: Int, second: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (first shr shift) and 0xFF
            val b = (second shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun isSimilar(first: Int, second: Int): Boolean =
        abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)) < 10 &&
            abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)) < 10 &&
            abs((first and 0xFF) - (second and 0xFF)) < 10
}
