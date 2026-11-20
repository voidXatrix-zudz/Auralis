package com.auralis.app.ui.performance

import androidx.compose.runtime.Immutable

/**
 * The single, concrete description of how much visual work the app may do right now.
 *
 * Every expensive rendering site reads this through [LocalPerformanceProfile] instead of checking
 * the raw user preference. The user preference is only an input to the resolver; this profile is
 * the source of truth at render time.
 *
 * Rule: animation durations, curves and spring constants are identical in every profile. A lower
 * profile reduces the cost of expensive extras (blur radius, shaders, secondary glow, looping
 * decoration), it never changes the feel of navigation or the dock morph. Backdrop blur itself is
 * never switched off by a profile while the user has Live blur on — only its radius scales.
 */
@Immutable
data class PerformanceProfile(
    val tier: VisualPerformanceTier,
    // --- Glass material -----------------------------------------------------
    /** Live backdrop blur is allowed at all. */
    val blurEnabled: Boolean,
    /** Multiplier on the user's chosen blur radius: 1.0 / 0.7 / 0.4 (never 0 while Live blur is on). */
    val blurScale: Float,
    /** Haze backdrop downsampling: 1.0 = full resolution, 0.5 = half. */
    val blurDownsample: Float,
    /** AGSL lens/refraction pass on large glass surfaces (API 33+). */
    val refractionEnabled: Boolean,
    /** Inner specular sheen gradient on glass surfaces. */
    val glassSheenEnabled: Boolean,
    // --- Secondary effects --------------------------------------------------
    /** Coloured glow / elevated shadows. */
    val glowEnabled: Boolean,
    /** Multiplier for shadow elevation: 1.0 / 0.7 / 0.35. */
    val shadowScale: Float,
    /** Animated ambient background colours and looping decorative blobs. */
    val ambientMotionEnabled: Boolean,
    /** Animated artwork / crossfades on artwork changes. */
    val animatedArtworkEnabled: Boolean,
    /** Expensive full-screen artwork blur behind Now Playing and Lyrics. */
    val backgroundBlurEnabled: Boolean,
    // --- Artwork ------------------------------------------------------------
    val artworkCrossfade: Boolean,
    /** Upper bound for decoded artwork, in pixels (longest side). */
    val artworkMaxPx: Int,
    // --- Lists --------------------------------------------------------------
    /** Emit stable content types on lazy lists. */
    val listContentTypes: Boolean,
) {
    val isLow: Boolean get() = tier == VisualPerformanceTier.LOW

    companion object {
        /** Safe default used before the controller has resolved anything. */
        val Default: PerformanceProfile = PerformanceProfiles.Medium
    }
}

/** Concrete presets. Kept in one object so tests and the resolver share the exact same knobs. */
object PerformanceProfiles {

    val High = PerformanceProfile(
        tier = VisualPerformanceTier.HIGH,
        blurEnabled = true,
        blurScale = 1f,
        blurDownsample = 1f,
        refractionEnabled = true,
        glassSheenEnabled = true,
        glowEnabled = true,
        shadowScale = 1f,
        ambientMotionEnabled = true,
        animatedArtworkEnabled = true,
        backgroundBlurEnabled = true,
        artworkCrossfade = true,
        artworkMaxPx = 2048,
        listContentTypes = true,
    )

    val Medium = PerformanceProfile(
        tier = VisualPerformanceTier.MEDIUM,
        blurEnabled = true,
        blurScale = 0.7f,
        blurDownsample = 0.5f,
        refractionEnabled = false,
        glassSheenEnabled = true,
        glowEnabled = true,
        shadowScale = 0.7f,
        ambientMotionEnabled = true,
        animatedArtworkEnabled = true,
        backgroundBlurEnabled = false,
        artworkCrossfade = true,
        artworkMaxPx = 1280,
        listContentTypes = true,
    )

    val Low = PerformanceProfile(
        tier = VisualPerformanceTier.LOW,
        // Blur stays on at ~40% radius: never zero while the user has Live blur on, so the glass
        // still reads as glass. The saved cost comes from a coarser downsample and simpler shadows.
        blurEnabled = true,
        blurScale = 0.4f,
        blurDownsample = 0.5f,
        refractionEnabled = false,
        glassSheenEnabled = false,
        glowEnabled = false,
        shadowScale = 0.35f,
        ambientMotionEnabled = false,
        animatedArtworkEnabled = false,
        backgroundBlurEnabled = false,
        artworkCrossfade = false,
        artworkMaxPx = 640,
        listContentTypes = true,
    )

    fun forTier(tier: VisualPerformanceTier): PerformanceProfile = when (tier) {
        VisualPerformanceTier.HIGH -> High
        VisualPerformanceTier.MEDIUM -> Medium
        VisualPerformanceTier.LOW -> Low
    }
}
