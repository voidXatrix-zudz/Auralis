package com.auralis.app.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
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
 *  - higher dock transparency -> lower dock fill alpha (0.55 -> 0.10), colour always indigo;
 *  - higher glass clarity      -> lower surface fill alpha (0.40 -> 0.04);
 *  - higher blur strength      -> larger backdrop radius (0dp -> 40dp).
 */
object GlassMapping {
    // Defaults shown by the Reset action (and used for fresh installs).
    const val DEFAULT_DOCK_TRANSPARENCY = 0.45f
    const val DEFAULT_GLASS_CLARITY = 0.50f
    const val DEFAULT_BLUR_STRENGTH = 0.55f

    const val DEFAULT_DOCK_TRANSPARENCY_PERCENT = 45
    const val DEFAULT_GLASS_CLARITY_PERCENT = 50
    const val DEFAULT_BLUR_PERCENT = 55

    /** Full-scale backdrop radius. Blur strength 0.55 -> ~22dp, 1.0 -> 40dp. */
    const val MAX_BLUR_DP = 40f

    private const val DOCK_FILL_MAX = 0.55f
    /**
     * Even at maximum see-through the dock keeps a whisper of indigo so cream labels stay legible
     * over bright album art. Colour is always [com.auralis.app.ui.theme.GlassTokens.Canvas].
     */
    private const val DOCK_FILL_MIN = 0.10f
    private const val SURFACE_FILL_MAX = 0.40f
    private const val SURFACE_FILL_MIN = 0.04f

    /**
     * Fixed dock rim alpha. Dock transparency must move only the fill, so the glass edge does not
     * fade in and out with the slider.
     */
    const val DOCK_BORDER_ALPHA = 0.16f

    /** No backdrop blur available: denser indigo so the dock still reads as smoked glass. */
    const val DOCK_NO_BLUR_MIN_FILL = 0.22f

    /**
     * Faint indigo base under every glass fill. Over the flat indigo backdrop it is invisible;
     * over album art / scrolling artwork it keeps cream text legible even at maximum see-through.
     */
    const val LEGIBILITY_SCRIM = 0.26f

    /** API < 31 or blur disabled: denser fill so the surface still reads as intentional glass. */
    const val NO_BLUR_MIN_FILL = 0.20f

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

    fun dockFillAlpha(transparency: Float): Float =
        lerp(DOCK_FILL_MAX, DOCK_FILL_MIN, transparency).coerceIn(DOCK_FILL_MIN, DOCK_FILL_MAX)

    fun surfaceFillAlpha(clarity: Float): Float =
        lerp(SURFACE_FILL_MAX, SURFACE_FILL_MIN, clarity).coerceIn(SURFACE_FILL_MIN, SURFACE_FILL_MAX)

    fun fillAlpha(kind: GlassKind, settings: GlassSettings): Float = when (kind) {
        GlassKind.Dock -> dockFillAlpha(settings.dockTransparency)
        GlassKind.Surface -> surfaceFillAlpha(settings.glassClarity)
    }

    /** Hairline alpha follows the same direction and stays subtle. */
    fun borderAlpha(value: Float): Float = lerp(BORDER_STRONG, BORDER_FAINT, value)

    /** 0..1 strength -> 0..40dp, then scaled by the performance profile and whole-dp quantized. */
    fun blurDp(strength: Float, scale: Float): Float {
        val raw = MAX_BLUR_DP * strength.coerceIn(0f, 1f) * scale.coerceIn(0f, 1f)
        return raw.coerceIn(0f, MAX_BLUR_DP).roundToInt().toFloat()
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return start + (end - start) * t
    }
}