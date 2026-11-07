package com.auralis.app.domain.model

import com.auralis.app.domain.audio.EqPresets

/** Persisted user settings (DataStore). Defaults match the web reference. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: String = "Teal",
    val dynamicTheme: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqGains: List<Float> = EqPresets.gains("Flat"),
    /** Device-band gains in millibels, in the order reported by the hardware equalizer. */
    val eqBandLevels: List<Int> = emptyList(),
    /** Number of device bands [eqBandLevels] was captured for; 0 means "not captured yet". */
    val eqBandCount: Int = 0,
    val preampDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val bassDb: Float = 0f,
    val bassBoostEnabled: Boolean = false,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val speed: Float = 1f,
    val balance: Float = 0f,
    val volume: Float = 1f,
    val replayGain: ReplayGainMode = ReplayGainMode.TRACK,
    val lyricSizeSp: Int = 22,
    val lyricsAutoScroll: Boolean = true,
    /** Global timing correction for synced lyrics, in milliseconds (positive = lyrics later). */
    val lyricsOffsetMs: Int = 0,
    val performanceProfile: PerformanceMode = PerformanceMode.AUTOMATIC,
    val gestureSeekEnabled: Boolean = true,
    val gestureSwipeEnabled: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val crossfadeSeconds: Int = 0,
    val resumeOnOutputReconnect: Boolean = false,
    val carKeepScreenOn: Boolean = true,
    val themeStyle: ThemeStyle = ThemeStyle.NORMAL,
    /** Glass dock tuning (Glassmorphism only): surface transparency 0-100%, default ~70%. */
    val dockGlassTransparency: Int = 70,
    /** Glass dock tuning: blur strength 0-100% mapping to ~0-30dp radius, default ~40%. */
    val dockGlassBlur: Int = 40,
    /** Glassmorphism "Glass clarity": 0 = tinted/smoked, 100 = clear/crystal. Default ~40. */
    val glassClarity: Int = 40,
    /** Draw the 1dp specular hairline on glass surfaces. */
    val glassOutlines: Boolean = true,
    /** Watch MediaStore/folders for new, changed and removed songs and update the library automatically. */
    val autoScanEnabled: Boolean = true,
)
