package com.auralis.app.ui.glass

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * True when the system "Remove animations" / animator duration scale = 0 setting is active.
 * Compose springs normally honour the platform scale, but the dock morph and the selection
 * capsule are driven manually, so we check explicitly and snap instead of animating.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return remember { ValueAnimator.getDurationScale() == 0f }
}
