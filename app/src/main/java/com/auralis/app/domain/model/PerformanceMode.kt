package com.auralis.app.domain.model

/**
 * Visual workload preference. This setting never changes decoder, bitrate, or audio quality.
 *
 * Renamed from `PerformanceProfile` so the resolved, concrete knob set can own that name.
 * The persisted values (AUTOMATIC/HIGH/MEDIUM/LOW) and the DataStore/JSON keys are unchanged.
 */
enum class PerformanceMode {
    AUTOMATIC,
    HIGH,
    MEDIUM,
    LOW,
}
