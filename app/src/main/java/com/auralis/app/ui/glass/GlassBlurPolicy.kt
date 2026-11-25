package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.app.domain.model.AppSettings

/**
 * The ONE rule that decides whether backdrop blur may run.
 *
 * Blur is a first-class part of the Glassmorphism look, so it must NOT depend on performance
 * heuristics. Device RAM, core count, heap class, Battery Saver and thermal state never decide
 * whether the glass blurs: at every tier the content behind the glass is blurred (Low uses a
 * larger, cheaper radius). Automatic NEVER disables blur.
 *
 * Blur is disallowed only when:
 *  - the platform cannot blur-plus-sample a recorded layer (API < 31), or
 *  - the user explicitly turned on "Reduce blur and effects".
 *
 * The old "Blur strength" slider is gone (STEP 2.6): blur is a fixed constant, so it is no longer
 * part of this decision.
 *
 * Every glass surface reads the pre-computed [LocalGlassBlurAllowed] instead of threading the raw
 * settings, so the dock, the mini-player, the cards and the Appearance preview can never disagree.
 */
object GlassBlurPolicy {
    fun allowed(settings: AppSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= 31 && !settings.reduceBlurAndEffects

    /**
     * Whether the single backdrop capture layer may exist at all. Capturing costs nothing while no
     * surface is consuming the layer, so it matches [allowed] now that blur is not slider-gated.
     */
    fun sourceAllowed(settings: AppSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        allowed(settings, sdkInt)
}

/**
 * Pre-computed [GlassBlurPolicy.allowed] value for the current composition, provided once at the
 * app root. Defaults to `false` so a surface rendered without a provider simply draws no blur
 * rather than silently sampling a backdrop layer.
 */
val LocalGlassBlurAllowed = staticCompositionLocalOf { false }
