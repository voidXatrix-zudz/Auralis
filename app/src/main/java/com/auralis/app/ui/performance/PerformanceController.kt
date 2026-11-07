package com.auralis.app.ui.performance

import android.os.SystemClock
import android.util.Log
import com.auralis.app.BuildConfig
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PerformanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the single resolved [PerformanceProfile] for the whole app.
 *
 * It combines the persisted mode with live device conditions and re-evaluates immediately when
 * either changes. When Automatic is holding a step-down after Battery Saver/thermal clears, it
 * sleeps for the remaining hold and then re-evaluates, so the tier can step back up without polling.
 */
class PerformanceController(
    settings: StateFlow<AppSettings>,
    private val conditions: StateFlow<DeviceConditions>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val resolver: AdaptiveTierResolver = AdaptiveTierResolver(),
) {
    private val _profile = MutableStateFlow(PerformanceProfile.Default)
    val profile: StateFlow<PerformanceProfile> = _profile.asStateFlow()

    private val _mode = MutableStateFlow(PerformanceMode.AUTOMATIC)
    val mode: StateFlow<PerformanceMode> = _mode.asStateFlow()

    private val _tier = MutableStateFlow(VisualPerformanceTier.MEDIUM)
    val tier: StateFlow<VisualPerformanceTier> = _tier.asStateFlow()

    /** "Battery Saver" / "Device temperature" while Automatic is stepped down, else null. */
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    init {
        scope.launch {
            combine(
                settings.map { it.performanceProfile }.distinctUntilChanged(),
                conditions,
            ) { mode, snapshot -> mode to snapshot }
                .collectLatest { (mode, snapshot) ->
                    _mode.value = mode
                    apply(mode, snapshot)
                    val wait = resolver.holdRemainingMs(clock())
                    if (wait > 0L) {
                        delay(wait)
                        apply(mode, snapshot)
                    }
                }
        }
    }

    private fun apply(mode: PerformanceMode, snapshot: DeviceConditions) {
        val tier = resolver.resolve(mode, snapshot, clock())
        val resolved = PerformanceProfiles.forTier(tier)
        val reason = when {
            mode != PerformanceMode.AUTOMATIC -> null
            snapshot.batterySaver -> "Battery Saver"
            snapshot.thermalStatus >= AdaptiveTierResolver.THERMAL_MODERATE -> "Device temperature"
            else -> null
        }
        _tier.value = tier
        _profile.value = resolved
        _reason.value = reason
        if (BuildConfig.DEBUG) {
            Log.d(
                "AuralisPerf",
                "mode=$mode tier=$tier reason=$reason blur=${resolved.blurEnabled}/${resolved.blurScale} " +
                    "refraction=${resolved.refractionEnabled} shadow=${resolved.shadowScale} " +
                    "art=${resolved.artworkMaxPx}px visualizer=${resolved.visualizerFps}fps",
            )
        }
    }
}
