package com.auralis.app.ui.performance

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.auralis.app.logging.AppLog

/**
 * Turns the resolved [VisualPerformanceTier] into a display refresh-rate request.
 *
 * The app never hard-codes a rate: it reads the panel's own `supportedModes` and asks for the
 * fastest mode that is at or below the tier's target. If the panel cannot reach the target (a
 * 90 Hz phone asked for 60/90/120, or a 60 Hz phone asked for 120) it falls back to the closest
 * mode the panel *can* do, so no mode ever errors or forces an unsupported rate.
 *
 * Motion is unchanged by this: every animation duration in the app is in milliseconds, so only the
 * number of drawn frames changes, never the feel.
 */
object RefreshRateTargeting {
    /** Target refresh rate per tier: High 120 Hz, Medium 90 Hz, Low 60 Hz. */
    fun targetHz(tier: VisualPerformanceTier): Int = when (tier) {
        VisualPerformanceTier.HIGH -> 120
        VisualPerformanceTier.MEDIUM -> 90
        VisualPerformanceTier.LOW -> 60
    }

    /**
     * Picks the display-mode id to request.
     *
     * Rule: the fastest supported mode whose refresh rate is at or below [targetHz]. When *every*
     * mode is above the target (e.g. a 90 Hz-only panel asked for 60 Hz) the slowest mode is used,
     * because we must pick something the panel supports and must never request more than it can do.
     *
     * @param modes (modeId to refreshRateHz) pairs exactly as the display reports them.
     * @return the chosen modeId, or null when the display reported no modes.
     */
    fun pickModeId(modes: List<Pair<Int, Float>>, targetHz: Int): Int? {
        if (modes.isEmpty()) return null
        val tolerance = 0.5f
        val atOrBelow = modes.filter { it.second <= targetHz + tolerance }
        return if (atOrBelow.isNotEmpty()) {
            atOrBelow.maxBy { it.second }.first
        } else {
            modes.minBy { it.second }.first
        }
    }

    /**
     * The refresh rate to request for legacy code paths, i.e. the same choice as [pickModeId] but as
     * a Hz value. Returns 0f when the display reported no modes.
     */
    fun pickRefreshRate(modes: List<Pair<Int, Float>>, targetHz: Int): Float {
        val id = pickModeId(modes, targetHz) ?: return 0f
        return modes.firstOrNull { it.first == id }?.second ?: 0f
    }
}

/** The refresh rate this profile asks the display for. */
val PerformanceProfile.targetFrameRateHz: Int
    get() = RefreshRateTargeting.targetHz(tier)

@Suppress("DEPRECATION")
private fun displayOf(activity: Activity): Display? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
    else activity.windowManager.defaultDisplay
} catch (error: Throwable) {
    AppLog.w("RefreshRate", "Could not read the activity display", error)
    null
}

/**
 * Applies the tier's refresh-rate request to [activity]'s window.
 *
 * Uses `preferredDisplayModeId` (API 26+, and the framework's own mode-switch path) and falls back
 * to the deprecated `preferredRefreshRate` only when the display reported no usable mode id. Both
 * are cheap window-attribute writes that take effect without recreating the activity.
 */
fun applyRefreshRate(activity: Activity, tier: VisualPerformanceTier) {
    try {
        val display = displayOf(activity) ?: return
        val modes = display.supportedModes
            .mapNotNull { mode -> mode?.let { it.modeId to it.refreshRate } }
        if (modes.isEmpty()) return

        val targetHz = RefreshRateTargeting.targetHz(tier)
        val preferredId = RefreshRateTargeting.pickModeId(modes, targetHz)
        val preferredRate = RefreshRateTargeting.pickRefreshRate(modes, targetHz)

        val attrs = activity.window.attributes
        var changed = false
        if (preferredId != null && preferredId != 0 && attrs.preferredDisplayModeId != preferredId) {
            attrs.preferredDisplayModeId = preferredId
            changed = true
        }
        // Keep the rate hint consistent even when the mode id path is ignored by the vendor.
        if (!preferredRate.isNaN() && attrs.preferredRefreshRate != preferredRate) {
            attrs.preferredRefreshRate = preferredRate
            changed = true
        }
        if (changed) activity.window.attributes = attrs
    } catch (error: Throwable) {
        // A device that rejects the attribute must simply keep its current rate, not crash.
        AppLog.w("RefreshRate", "Could not apply the refresh-rate request", error)
    }
}

/**
 * Requests the tier's refresh rate for the hosting activity and keeps it in force:
 *  - immediately, and again whenever [tier] changes (no restart, no activity recreation);
 *  - on every resume, because the system may have reset the mode while backgrounded;
 *  - when the display changes (dock/undock, external display, or a panel that switches its own rate).
 */
@Composable
fun RefreshRateEffect(tier: VisualPerformanceTier) {
    val activity = LocalActivity.current ?: return
    val latestTier by rememberUpdatedState(tier)

    LaunchedEffect(activity, tier) { applyRefreshRate(activity, tier) }

    LifecycleResumeEffect(activity, tier) {
        applyRefreshRate(activity, tier)
        onPauseOrDispose { }
    }

    DisposableEffect(activity) {
        val context = activity.applicationContext
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                applyRefreshRate(activity, latestTier)
            }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
}
