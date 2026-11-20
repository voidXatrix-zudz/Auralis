package com.auralis.app.ui.glass

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the system "Remove animations" / animator duration scale = 0 setting is active.
 * Compose springs normally honour the platform scale, but the dock morph and the selection
 * capsule are driven manually, so we check explicitly and snap instead of animating.
 *
 * Reads the global animator scale directly instead of the API 33 `ValueAnimator.getDurationScale()`,
 * so the check works from API 26 up.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}
