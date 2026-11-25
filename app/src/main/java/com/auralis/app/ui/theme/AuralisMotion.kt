package com.auralis.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

/**
 * The one place AURALIS motion is defined.
 *
 * "Creamy" is one idea here: motion that *arrives* instead of stopping. Every spec is either a
 * low-damping spring — which eases out with a whisper of overshoot and settles — or a long ease-out
 * curve. Spatial changes (slide, scale, reveal, press) use the springs; anything that cannot spring
 * (fades, enter/exit transitions, colour) uses [Creamy]. Durations are deliberately generous: this
 * is a music app, so the motion should feel like the tail of a reverb, not a UI switch.
 *
 * All of it still respects "Remove animations" at the call site via `rememberReduceMotion()`.
 */
object AuralisMotion {

    // ---- Easings ------------------------------------------------------------------------------

    /** Long, gentle deceleration. The house curve for fades and size changes. */
    val Creamy: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** A touch more balanced, for elements that also travel a distance. */
    val Smooth: Easing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)

    // ---- Durations (ms) -----------------------------------------------------------------------

    const val Fast = 240
    const val Medium = 400
    const val Slow = 620
    const val Lazy = 820

    // ---- Springs ------------------------------------------------------------------------------

    /** Slow, soft settle. Cards, sheets and other large surfaces. No visible overshoot. */
    val springSoft: AnimationSpec<Float> = spring(dampingRatio = 0.90f, stiffness = 230f)

    /** Everyday spatial motion. A small overshoot, so it feels alive rather than mechanical. */
    val springMedium: AnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 360f)

    /** Large sliding surfaces (the dock capsule, selection lenses): a slower, softer settle. */
    val springSlide: AnimationSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 240f)

    /** Press / toggle feedback. Quick, but still cushioned so it never snaps. */
    val springPress: AnimationSpec<Float> = spring(dampingRatio = 0.70f, stiffness = 600f)

    /** Colour-spec spring; Material animates [Color] through the same spring engine. */
    val springColor: AnimationSpec<Color> = spring(dampingRatio = 0.85f, stiffness = 420f)

    // ---- Tweens -------------------------------------------------------------------------------

    /** A fade/transition tween on the [Creamy] curve, for specs that cannot use a spring. */
    fun <T> tweenCreamy(durationMillis: Int = Medium, delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = Creamy)
}
