package com.auralis.app.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.NavState
import com.auralis.app.ui.theme.AuralisMotion

/** Directional navigation transitions that respect the app's animation scale. */
fun auralisPageTransition(initial: NavState, target: NavState, scale: Float): ContentTransform {
    if (scale == 0f) return EnterTransition.None togetherWith ExitTransition.None

    if (target.top == Destination.About) {
        return fadeIn(tween(ms(180, scale), easing = AuralisMotion.Creamy)) togetherWith
            fadeOut(tween(ms(120, scale), easing = AuralisMotion.Creamy))
    }

    val outMs = ms(110, scale)
    val inMs = ms(400, scale)
    val inDelay = ms(70, scale)
    val depthDelta = target.stack.size - initial.stack.size
    val direction = when {
        depthDelta > 0 -> 1
        depthDelta < 0 -> -1
        target.tab.ordinal != initial.tab.ordinal -> if (target.tab.ordinal > initial.tab.ordinal) 1 else -1
        else -> 1
    }
    val isTabSwitch = depthDelta == 0 && target.tab != initial.tab
    val travelIn = if (isTabSwitch) 0.07f else 0.20f
    val travelOut = if (isTabSwitch) 0.05f else 0.07f

    val enter = fadeIn(tween(inMs, inDelay, AuralisMotion.Creamy)) +
        slideInHorizontally(tween(inMs, inDelay, AuralisMotion.Creamy)) { (it * travelIn * direction).toInt() } +
        scaleIn(tween(inMs, inDelay, AuralisMotion.Creamy), initialScale = 0.985f)
    val exit = fadeOut(tween(outMs, easing = AuralisMotion.Creamy)) +
        slideOutHorizontally(tween(ms(260, scale), easing = AuralisMotion.Creamy)) { (-it * travelOut * direction).toInt() } +
        scaleOut(tween(ms(260, scale), easing = AuralisMotion.Creamy), targetScale = 0.985f)

    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}

private fun ms(base: Int, scale: Float): Int = (base * scale).toInt().coerceAtLeast(1)
