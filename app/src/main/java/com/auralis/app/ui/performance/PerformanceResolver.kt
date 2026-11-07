package com.auralis.app.ui.performance

import com.auralis.app.domain.model.PerformanceMode

/**
 * A snapshot of the device and system state that can lower the visual workload.
 *
 * Pure data so the resolver can be unit-tested on the JVM without Android stubs.
 */
data class DeviceConditions(
    val isLowRamDevice: Boolean = false,
    val totalMemBytes: Long = 4L * 1024 * 1024 * 1024,
    val availMemBytes: Long = 2L * 1024 * 1024 * 1024,
    val memoryClassMb: Int = 256,
    val cores: Int = 8,
    val sdkInt: Int = 33,
    val batterySaver: Boolean = false,
    /** Raw PowerManager thermal status. 0 = none, 2 = moderate, 3 = severe, ... */
    val thermalStatus: Int = 0,
) {
    val totalRamGb: Double get() = totalMemBytes / (1024.0 * 1024.0 * 1024.0)
    val availableRamGb: Double get() = availMemBytes / (1024.0 * 1024.0 * 1024.0)
}

/**
 * Maps a user mode + device conditions to a concrete [PerformanceProfile].
 *
 * Automatic behaviour:
 *  - Base tier from hardware (low-RAM flag, RAM, heap class, cores, SDK).
 *  - Battery Saver or thermal status MODERATE+ steps the tier down exactly one level.
 *  - The step-down is held for [AdaptiveTierResolver.restoreDelayMs] after conditions clear,
 *    which is the hysteresis that prevents flapping when a charger is plugged/unplugged or the
 *    device hovers around a thermal threshold.
 *
 * Manual modes (HIGH/MEDIUM/LOW) are always honoured exactly.
 */
class AdaptiveTierResolver(
    val restoreDelayMs: Long = 30_000L,
) {
    private var constrained = false
    private var holdUntilMs = 0L

    fun resolve(mode: PerformanceMode, conditions: DeviceConditions, nowMs: Long): VisualPerformanceTier {
        val base = baseTier(conditions)
        if (mode != PerformanceMode.AUTOMATIC) {
            constrained = false
            holdUntilMs = 0L
            return when (mode) {
                PerformanceMode.HIGH -> VisualPerformanceTier.HIGH
                PerformanceMode.MEDIUM -> VisualPerformanceTier.MEDIUM
                PerformanceMode.LOW -> VisualPerformanceTier.LOW
                PerformanceMode.AUTOMATIC -> base
            }
        }
        if (isConstrained(conditions)) {
            constrained = true
            holdUntilMs = nowMs + restoreDelayMs
            return stepDown(base)
        }
        if (constrained && nowMs < holdUntilMs) {
            // Conditions cleared, but stay stepped down until the hold expires.
            return stepDown(base)
        }
        constrained = false
        holdUntilMs = 0L
        return base
    }

    /** Milliseconds left in the hysteresis hold, or 0 when no re-evaluation is pending. */
    fun holdRemainingMs(nowMs: Long): Long =
        if (constrained) (holdUntilMs - nowMs).coerceAtLeast(0L) else 0L

    companion object {
        /** PowerManager.THERMAL_STATUS_MODERATE, inlined to keep this file JVM-testable. */
        const val THERMAL_MODERATE = 2

        fun baseTier(conditions: DeviceConditions): VisualPerformanceTier {
            val weak = conditions.isLowRamDevice ||
                conditions.totalRamGb < 3.5 ||
                conditions.availableRamGb < 0.75 ||
                conditions.cores <= 4 ||
                conditions.memoryClassMb < 192
            if (weak) return VisualPerformanceTier.LOW

            val strong = conditions.totalRamGb >= 6.0 &&
                conditions.availableRamGb >= 1.5 &&
                conditions.cores >= 8 &&
                conditions.memoryClassMb >= 256 &&
                conditions.sdkInt >= 31
            return if (strong) VisualPerformanceTier.HIGH else VisualPerformanceTier.MEDIUM
        }

        fun isConstrained(conditions: DeviceConditions): Boolean =
            conditions.batterySaver || conditions.thermalStatus >= THERMAL_MODERATE

        fun stepDown(tier: VisualPerformanceTier): VisualPerformanceTier = when (tier) {
            VisualPerformanceTier.HIGH -> VisualPerformanceTier.MEDIUM
            VisualPerformanceTier.MEDIUM, VisualPerformanceTier.LOW -> VisualPerformanceTier.LOW
        }
    }
}
