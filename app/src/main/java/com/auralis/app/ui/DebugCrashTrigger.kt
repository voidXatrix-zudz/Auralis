package com.auralis.app.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.auralis.app.BuildConfig

/**
 * Debug-only long-press crash trigger.
 *
 * In release builds this is a no-op. In debug builds, long-pressing the element it is attached to
 * throws on the main thread, so the process's uncaught-exception handler records the report and
 * shows the crash screen — the manual way to verify the crash capture without breaking anything.
 */
fun Modifier.debugCrashTrigger(label: String): Modifier {
    if (!BuildConfig.DEBUG) return this
    return pointerInput(label) {
        detectTapGestures(
            onLongPress = {
                Handler(Looper.getMainLooper()).post {
                    throw RuntimeException("AURALIS debug crash trigger: $label")
                }
            },
        )
    }
}
