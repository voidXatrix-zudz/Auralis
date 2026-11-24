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
 *  - [dockOpacity] only feeds [GlassKind.Dock] (mini player, nav pill, search circle).
 *  - [glassClarity] only feeds [GlassKind.Surface] (cards, rows, chips, dialogs, Now Playing).
 *  - [blurStrength] feeds every surface, dock included.
 *
 * Components never read DataStore or hardcode an alpha/blur value; they read
 * [LocalGlassSettings] and route through `Modifier.glassSurface(kind, ...)`.
 */
@Immutable
data class GlassSettings(
    /** Dock opacity: 0 = fully see-through, 1 = fully opaque solid. */
    val dockOpacity: Float = GlassMapping.DEFAULT_DOCK_OPACITY,
    val glassClarity: Float = GlassMapping.DEFAULT_GLASS_CLARITY,
    val blurStrength: Float = GlassMapping.DEFAULT_BLUR_STRENGTH,
) {
    companion object {
        /** Builds the model from the persisted 0..100 int preferences. */
        fun fromPercent(
            dockOpacityPercent: Int,
            glassClarityPercent: Int,
            blurPercent: Int,
        ) = GlassSettings(
            dockOpacity = GlassMapping.percent(dockOpacityPercent),
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
 *  - higher dock opacity   -> denser dock fill (0 = clear, 1 = opaque solid);
 *  - higher glass clarity  -> lower surface fill alpha (0.40 -> 0.04);
 *  - higher blur strength  -> larger backdrop radius (0dp -> 80dp), never tier-scaled.
 */
object GlassMapping {
    // Defaults shown by the Reset action (and used for fresh installs).
    const val DEFAULT_DOCK_OPACITY = 0.50f
    const val DEFAULT_GLASS_CLARITY = 0.50f
    const val DEFAULT_BLUR_STRENGTH = 0.55f

    const val DEFAULT_DOCK_OPACITY_PERCENT = 50
    const val DEFAULT_GLASS_CLARITY_PERCENT = 50
    const val DEFAULT_BLUR_PERCENT = 55

    /** Full-scale backdrop radius: 0% -> 0dp, 100% -> 80dp. Never scaled by the performance tier. */
    const val MAX_BLUR_DP = 80f

    private const val SURFACE_FILL_MAX = 0.40f
    private const val SURFACE_FILL_MIN = 0.04f

    /**
     * Fixed dock rim alpha. Dock opacity must move only the fill, so the glass edge does not fade
     * in and out with the slider.
     */
    const val DOCK_BORDER_ALPHA = 0.16f

    /**
     * Faint black base under every [GlassKind.Surface] fill. Over the flat black backdrop it is
     * invisible; over album art / scrolling artwork it keeps cream text legible. The dock family no
     * longer uses this: its tint comes from [dockFill].
     */
    const val LEGIBILITY_SCRIM = 0.26f

    /** API < 31 or blur disabled: denser fill so the surface still reads as intentional glass. */
    const val NO_BLUR_MIN_FILL = 0.20f

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
     * The fully-opaque dock colour: pure-black canvas lifted ~6% toward cream so 100% opacity reads
     * as a solid smoked pane, never a dead pure black.
     */
    val DOCK_SOLID: Color = lerpColor(GlassTokens.Canvas, GlassTokens.Cream, 0.06f)

    /**
     * Dock opacity -> the dock's one fill colour+alpha. One function, one surface, one direction:
     *  - 0.0 .. 0.5 (see-through half): colour stays frost cream, alpha rises 0.00 -> 0.10;
     *  - 0.5 .. 1.0 (translucent -> solid): smoothstep blend frost -> [DOCK_SOLID], alpha 0.10 -> 1.00.
     *
     * Contract the unit tests hold to: `dockFill(0f)` is fully transparent, `dockFill(1f)` is
     * exactly alpha 1.0 in [DOCK_SOLID], alpha never decreases, and no 1% step moves alpha by more
     * than 0.04.
     */
    fun dockFill(o: Float): Color {
        val opacity = o.coerceIn(0f, 1f)
        if (opacity <= 0f) return GlassTokens.Cream.copy(alpha = 0f)
        if (opacity <= 0.5f) {
            val alpha = lerp(0f, 0.10f, opacity / 0.5f)
            return GlassTokens.Cream.copy(alpha = alpha)
        }
        // Exact endpoint so 100% is bit-identical to DOCK_SOLID (alpha exactly 1.0).
        if (opacity >= 1f) return DOCK_SOLID
        val f = smoothstep((opacity - 0.5f) / 0.5f)
        return lerpColor(GlassTokens.Cream, DOCK_SOLID, f).copy(alpha = lerp(0.10f, 1f, f))
    }

    /** Backdrop saturation boost, 1.0 at 0% blur -> 1.30 at full blur. */
    fun dockSaturation(blurFraction: Float): Float = 1.0f + 0.30f * blurFraction.coerceIn(0f, 1f)

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
        GlassKind.Dock -> dockFill(settings.dockOpacity).alpha
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

    /** Classic Hermite smoothstep on an already-normalised 0..1 input. */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}