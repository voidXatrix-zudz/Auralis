package com.auralis.app.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.auralis.app.ui.theme.GlassTokens
import dev.chrisbanes.haze.HazeState
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The THREE Appearance glass controls, resolved to 0..1 once at the app root.
 *
 * This is the single source of truth for every glass surface:
 *  - [dockTransparency] only feeds [GlassKind.Dock] (mini player, nav pill, search circle).
 *  - [glassClarity] only feeds [GlassKind.Surface] (cards, rows, chips, dialogs, Now Playing).
 *  - [blurStrength] feeds every surface, dock included.
 *
 * Components never read DataStore or hardcode an alpha/blur value; they read
 * [LocalGlassSettings] and route through `Modifier.glassSurface(kind, ...)`.
 */
@Immutable
data class GlassSettings(
    val dockTransparency: Float = GlassMapping.DEFAULT_DOCK_TRANSPARENCY,
    val glassClarity: Float = GlassMapping.DEFAULT_GLASS_CLARITY,
    val blurStrength: Float = GlassMapping.DEFAULT_BLUR_STRENGTH,
) {
    companion object {
        /** Builds the model from the persisted 0..100 int preferences. */
        fun fromPercent(
            dockTransparencyPercent: Int,
            glassClarityPercent: Int,
            blurPercent: Int,
        ) = GlassSettings(
            dockTransparency = GlassMapping.percent(dockTransparencyPercent),
            glassClarity = GlassMapping.percent(glassClarityPercent),
            blurStrength = GlassMapping.percent(blurPercent),
        )
    }
}

/** The persisted, committed controls. Provided at the root. */
val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }

/**
 * Transient copy used while a slider is being dragged. The Appearance screen writes here on every
 * tick so the whole app reacts live, then persists once on `onValueChangeFinished`. Null means
 * "use the persisted [LocalGlassSettings]".
 */
class GlassDragState {
    var value by mutableStateOf<GlassSettings?>(null)
}

val LocalGlassDrag = staticCompositionLocalOf { GlassDragState() }

/**
 * The one Haze capture state. Provided at the root so a surface never has to thread a parameter
 * (or silently pass `null`, which is what previously disabled blur on every card).
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Which family of glass a surface belongs to. This is the ONLY scoping switch in the pipeline. */
enum class GlassKind { Dock, Surface }

/**
 * Central mapping from the three 0..1 controls to concrete alphas/radii.
 *
 * Direction, per the controls' intent:
 *  - higher dock transparency -> lower dock tint alpha (0.90 -> 0.05), colour = warm black;
 *  - higher glass clarity      -> lower surface fill alpha (0.40 -> 0.04);
 *  - higher blur strength      -> larger backdrop radius (0dp -> 80dp), never tier-scaled.
 */
object GlassMapping {
    // Defaults shown by the Reset action (and used for fresh installs).
    const val DEFAULT_DOCK_TRANSPARENCY = 0.45f
    const val DEFAULT_GLASS_CLARITY = 0.50f
    const val DEFAULT_BLUR_STRENGTH = 0.55f

    const val DEFAULT_DOCK_TRANSPARENCY_PERCENT = 45
    const val DEFAULT_GLASS_CLARITY_PERCENT = 50
    const val DEFAULT_BLUR_PERCENT = 55

    /** Full-scale backdrop radius: 0% -> 0dp, 100% -> 80dp. Never scaled by the performance tier. */
    const val MAX_BLUR_DP = 80f

    /** Dock tint stops (Dock transparency 0..1) -> tint alpha over the blurred backdrop. */
    private val DOCK_TINT_STOPS = floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f)
    private val DOCK_TINT_ALPHAS = floatArrayOf(0.90f, 0.66f, 0.42f, 0.20f, 0.05f)

    private const val SURFACE_FILL_MAX = 0.40f
    private const val SURFACE_FILL_MIN = 0.04f

    /**
     * Fixed dock rim alpha. Dock transparency must move only the fill, so the glass edge does not
     * fade in and out with the slider.
     */
    const val DOCK_BORDER_ALPHA = 0.16f

    /**
     * Faint black base under every [GlassKind.Surface] fill. Over the flat black backdrop it is
     * invisible; over album art / scrolling artwork it keeps cream text legible. The dock family no
     * longer uses this: it uses the adaptive [dockTextScrimAlpha] instead.
     */
    const val LEGIBILITY_SCRIM = 0.26f

    /** API < 31 or blur disabled: denser fill so the surface still reads as intentional glass. */
    const val NO_BLUR_MIN_FILL = 0.20f

    /**
     * Legacy dock floor. Superseded by [DOCK_NO_BLUR_TINT_FLOOR]; retained only while the old
     * window/popup dock path still reads it, removed when that path is retired.
     */
    const val DOCK_NO_BLUR_MIN_FILL = 0.22f

    /** Warm lift added to [GlassTokens.Canvas] for the dock tint colour. */
    private const val DOCK_TINT_WARM = 0.03f

    /** Blur radius at which the Haze input-scale cost factor bottoms out. */
    private const val HAZE_COST_REFERENCE_DP = 28f

    /** Scrim behind a dialog/sheet window: the app content must not mix with dialog text. */
    const val WINDOW_SCRIM = 0.86f

    /** Cream tint floor for window glass. The scrim above carries the readability. */
    const val WINDOW_MIN_FILL = 0.10f

    /** Dim applied behind a dialog panel so the window blur has a calm base. */
    const val DIALOG_SCRIM = 0.55f

    /**
     * Small controls nested inside a panel keep a faint fill so crystal clarity does not erase
     * their shape. Much lower than [NO_BLUR_MIN_FILL] so Glass clarity stays effective on them.
     */
    const val NESTED_MIN_FILL = 0.08f

    private const val BORDER_STRONG = 0.20f
    private const val BORDER_FAINT = 0.08f

    fun percent(value: Int): Float = value.coerceIn(0, 100) / 100f

    fun percentToInt(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()

    /**
     * Dock transparency -> warm-black tint alpha over the blurred backdrop.
     * Piecewise table (0% 0.90, 25% 0.66, 50% 0.42, 75% 0.20, 100% 0.05) so the slider reads as a
     * large, obvious change: dense smoked glass -> almost fully see-through.
     */
    fun dockTintAlpha(transparency: Float): Float {
        val t = transparency.coerceIn(0f, 1f)
        for (i in 0 until DOCK_TINT_STOPS.lastIndex) {
            val a = DOCK_TINT_STOPS[i]
            val b = DOCK_TINT_STOPS[i + 1]
            if (t <= b) {
                val f = ((t - a) / (b - a)).coerceIn(0f, 1f)
                return DOCK_TINT_ALPHAS[i] + (DOCK_TINT_ALPHAS[i + 1] - DOCK_TINT_ALPHAS[i]) * f
            }
        }
        return DOCK_TINT_ALPHAS.last()
    }

    /**
     * The dock tint colour: pure black lifted a few percent toward warm cream so the smoked glass
     * reads warm instead of dead grey. Alpha is supplied separately by [dockTintAlpha].
     */
    fun dockTintColor(): Color = lerpColor(GlassTokens.Canvas, GlassTokens.Cream, DOCK_TINT_WARM)

    /** Thin warm frost over the blurred backdrop: strongest when the dock is densest. */
    fun dockFrostAlpha(transparency: Float): Float =
        (0.02f + 0.06f * (1f - transparency.coerceIn(0f, 1f))).coerceIn(0f, 0.08f)

    /**
     * Adaptive text/icon-only scrim. Replaces the old fixed minimum fill: it fades from 0.16 at 0%
     * blur to 0 at full blur, so it never exceeds 0.16 and never fights the backdrop once blur is
     * doing the legibility work. [blurFraction] = effective blur / [MAX_BLUR_DP].
     */
    fun dockTextScrimAlpha(blurFraction: Float): Float = 0.16f * (1f - blurFraction.coerceIn(0f, 1f))

    /** Backdrop saturation boost, 1.0 at 0% blur -> 1.30 at full blur. */
    fun dockSaturation(blurFraction: Float): Float = 1.0f + 0.30f * blurFraction.coerceIn(0f, 1f)

    /** The tint floor kept ONLY when blur cannot run (Android <12 / Low mode). */
    const val DOCK_NO_BLUR_TINT_FLOOR = 0.50f

    /** Brightness lift applied by the lens pass over the blurred backdrop. */
    const val DOCK_BRIGHTNESS = 0.03f

    /**
     * Haze cost factor: a larger blur radius is sampled at a lower input scale, 1/(1+radius/28),
     * clamped to [0.25, 1]. Applied as [dev.chrisbanes.haze.HazeInputScale.Fixed]; it changes render
     * cost only, never the visible radius.
     */
    fun hazeInputScale(radiusDp: Float): Float =
        (1f / (1f + radiusDp.coerceAtLeast(0f) / HAZE_COST_REFERENCE_DP)).coerceIn(0.25f, 1f)

    fun surfaceFillAlpha(clarity: Float): Float =
        lerp(SURFACE_FILL_MAX, SURFACE_FILL_MIN, clarity).coerceIn(SURFACE_FILL_MIN, SURFACE_FILL_MAX)

    fun fillAlpha(kind: GlassKind, settings: GlassSettings): Float = when (kind) {
        GlassKind.Dock -> dockTintAlpha(settings.dockTransparency)
        GlassKind.Surface -> surfaceFillAlpha(settings.glassClarity)
    }

    /** Hairline alpha follows the same direction and stays subtle. */
    fun borderAlpha(value: Float): Float = lerp(BORDER_STRONG, BORDER_FAINT, value)

    /**
     * 0..1 strength -> 0..80dp via `80 * s^1.15`, rounded to the nearest 0.5 dp. Deliberately NOT
     * scaled by the performance tier: the tier changes cost (Haze input scale) or turns blur off,
     * it never changes the radius.
     */
    fun blurDp(strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        val raw = MAX_BLUR_DP * s.pow(1.15f)
        return (raw.coerceIn(0f, MAX_BLUR_DP) * 2f).roundToInt() / 2f
    }

    /**
     * Compatibility overload for call sites that still pass a performance scale. The tier no longer
     * changes the radius (it only changes Haze cost or turns blur off), so [scale] is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun blurDp(strength: Float, scale: Float): Float = blurDp(strength)

    private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
        val t = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (end.red - start.red) * t,
            green = start.green + (end.green - start.green) * t,
            blue = start.blue + (end.blue - start.blue) * t,
            alpha = 1f,
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return start + (end - start) * t
    }
}