package com.auralis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.performance.LocalVisualPerformance
import com.auralis.app.ui.performance.VisualPerformanceTier

/**
 * Glassmorphic design tokens.
 *
 * The Glassmorphic theme uses exactly three base colours and nothing else: pure Black (#000000)
 * for the one app background, Vanilla Cream (#F0E7D5) for everything drawn on top of it, and
 * Midnight Indigo (#212842) only as the inverted foreground on cream fills (selected chips,
 * primary buttons, the active nav icon, switch thumbs). Every other value below is one of those
 * at a fixed opacity. Components must read these tokens instead of hardcoding a colour.
 */
object GlassTokens {
    /** Pure Black — the one app background; every page is transparent on top of it. */
    val Canvas = Color(0xFF000000)

    /** Midnight Indigo — the inverted (on-cream) foreground only. Never a background. */
    val Indigo = Color(0xFF212842)

    /** Vanilla Cream — primary text, icons, borders and the only accent. */
    val Cream = Color(0xFFF0E7D5)

    // Cream at each opacity the palette spells out. Derive new steps from Cream, never re-hex.
    /** Surface fill over the black canvas. */
    val Cream6 = Cream.copy(alpha = 0.06f)
    /** Pressed surface, dividers. */
    val Cream10 = Cream.copy(alpha = 0.10f)
    /** 1px surface border. */
    val Cream14 = Cream.copy(alpha = 0.14f)
    /** Unfilled part of a slider or progress bar. */
    val Cream20 = Cream.copy(alpha = 0.20f)
    /** Text selection background. */
    val Cream30 = Cream.copy(alpha = 0.30f)
    /** Tertiary text, hints, placeholders. */
    val Cream40 = Cream.copy(alpha = 0.40f)
    /** Inactive icons. */
    val Cream55 = Cream.copy(alpha = 0.55f)
    /** Off switches (track border and thumb). */
    val Cream60 = Cream.copy(alpha = 0.60f)
    /** Secondary text. */
    val Cream65 = Cream.copy(alpha = 0.65f)

    // Material slots cannot carry alpha, so surfaces are pre-composited cream over the canvas.
    val Surface = blend(Cream, Canvas, 0.06f)
    val Surface2 = blend(Cream, Canvas, 0.10f)
    val SurfaceHigh = blend(Cream, Canvas, 0.14f)

    // Semantic aliases kept so every existing call site recolours through this one file.
    val Obsidian = Canvas
    val Silver = Cream
    val SilverMuted = Cream65
    /** The old accent; the palette has no separate accent hue. */
    val Violet = Cream
    val Ice = Cream
    val OnAccent = Indigo
    val VioletContainer = Surface2
    val Error = Cream
    val Hairline = Cream14
    val SpecularLight = Cream.copy(alpha = 0.26f)
    val SpecularDark = Cream6

    /** Flat cream hairline: no specular gradient, no off-palette hues. */
    val BorderBrush = Brush.linearGradient(listOf(Cream14, Cream14))

    /** Primary fill: cream at full strength. */
    val AccentGradient = Brush.linearGradient(listOf(Cream, Cream))

    /** The old glass sheen added a second white layer; the palette fill is already exact. */
    val SurfaceSheen = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    val RadiusSm = 14.dp
    val RadiusMd = 20.dp
    val RadiusCard = 24.dp
    val RadiusLg = 26.dp
    val RadiusXl = 32.dp
}

/** Opaque composite of [foreground] over [background] at [alpha], for Material colour slots. */
private fun blend(foreground: Color, background: Color, alpha: Float): Color = Color(
    red = background.red * (1f - alpha) + foreground.red * alpha,
    green = background.green * (1f - alpha) + foreground.green * alpha,
    blue = background.blue * (1f - alpha) + foreground.blue * alpha,
    alpha = 1f,
)

/**
 * Live optical parameters for the liquid-glass material. Derived from the persisted
 * "Glass clarity" slider (+ the legacy dock fine-tuning sliders) so the dock, mini-player,
 * dialogs and settings preview all change together.
 */
@Immutable
data class GlassOptics(
    /** 0 = tinted/smoked, 1 = clear/crystal. */
    val clarity: Float = 0.4f,
    val outlines: Boolean = true,
    /** Smoked tint alpha applied over the blurred backdrop. */
    val tintAlpha: Float = 0.42f,
    val blurDp: Float = 30f,
    /** Backdrop saturation boost applied by the lens shader on API 33+. */
    val saturation: Float = 1.22f,
)

val LocalGlassOptics = staticCompositionLocalOf { GlassOptics() }

private val GlassColorScheme = darkColorScheme(
    primary = GlassTokens.Cream, onPrimary = GlassTokens.Indigo,
    primaryContainer = GlassTokens.Surface2, onPrimaryContainer = GlassTokens.Cream,
    secondary = GlassTokens.Cream, onSecondary = GlassTokens.Indigo,
    secondaryContainer = GlassTokens.Surface2, onSecondaryContainer = GlassTokens.Cream,
    tertiary = GlassTokens.Cream, onTertiary = GlassTokens.Indigo,
    background = GlassTokens.Canvas, onBackground = GlassTokens.Cream,
    surface = GlassTokens.Surface, onSurface = GlassTokens.Cream,
    surfaceVariant = GlassTokens.Surface2, onSurfaceVariant = GlassTokens.Cream65,
    surfaceTint = GlassTokens.Cream,
    surfaceContainerLowest = GlassTokens.Canvas, surfaceContainerLow = GlassTokens.Surface,
    surfaceContainer = GlassTokens.Surface, surfaceContainerHigh = GlassTokens.Surface2,
    surfaceContainerHighest = GlassTokens.SurfaceHigh,
    outline = GlassTokens.Cream20, outlineVariant = GlassTokens.Cream14,
    error = GlassTokens.Cream, onError = GlassTokens.Indigo,
    errorContainer = GlassTokens.Surface2, onErrorContainer = GlassTokens.Cream,
    inverseSurface = GlassTokens.Cream, inverseOnSurface = GlassTokens.Indigo,
    inversePrimary = GlassTokens.Indigo,
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(GlassTokens.RadiusSm),
    medium = RoundedCornerShape(GlassTokens.RadiusMd),
    large = RoundedCornerShape(GlassTokens.RadiusLg),
    extraLarge = RoundedCornerShape(GlassTokens.RadiusXl),
)

/** Typography using the bundled Sora and Manrope fonts. */
private val GlassTypography: Typography = Typography(
    displayLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 48.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.02).em),
    headlineLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.015).em),
    headlineMedium = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

/** Which visual style is active; Normal-only code can ignore it, glass variants branch on it. */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.NORMAL }

/**
 * Switch colours for the active theme. NORMAL returns Material's own defaults unchanged;
 * GLASSMORPHIC applies the palette rules: ON = cream track + indigo thumb, OFF = cream-14 track
 * with a cream-60 border and a cream-60 thumb (so the off switch reads against the black canvas).
 */
@Composable
fun auralisSwitchColors(): SwitchColors =
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        SwitchDefaults.colors(
            checkedThumbColor = GlassTokens.Indigo,
            checkedTrackColor = GlassTokens.Cream,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = GlassTokens.Indigo,
            uncheckedThumbColor = GlassTokens.Cream60,
            uncheckedTrackColor = GlassTokens.Cream14,
            uncheckedBorderColor = GlassTokens.Cream60,
            uncheckedIconColor = GlassTokens.Cream60,
        )
    } else {
        SwitchDefaults.colors()
    }

/** Slider colours for the active theme; NORMAL keeps Material defaults. */
@Composable
fun auralisSliderColors(): SliderColors =
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        SliderDefaults.colors(
            thumbColor = GlassTokens.Cream,
            activeTrackColor = GlassTokens.Cream,
            inactiveTrackColor = GlassTokens.Cream20,
        )
    } else {
        SliderDefaults.colors()
    }

/** Single entry point: NORMAL delegates to [AuralisAppTheme], GLASSMORPHIC to the glass tokens. */
@Composable
fun AuralisTheme(
    themeStyle: ThemeStyle,
    darkTheme: Boolean,
    accentName: String,
    visualPerformance: VisualPerformanceTier = VisualPerformanceTier.MEDIUM,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalThemeStyle provides themeStyle) {
        when (themeStyle) {
            ThemeStyle.NORMAL -> AuralisAppTheme(
                darkTheme = darkTheme,
                accentName = accentName,
                visualPerformance = visualPerformance,
                content = content,
            )
            ThemeStyle.GLASSMORPHIC -> GlassTheme(visualPerformance, content)
        }
    }
}

@Composable
private fun GlassTheme(visualPerformance: VisualPerformanceTier, content: @Composable () -> Unit) {
    val extras = remember {
        AuralisExtras(
            surface2 = GlassTokens.Surface2,
            mutedForeground = GlassTokens.Cream65,
            border = GlassTokens.Cream14,
            glass = GlassTokens.Surface,
            glassBorder = GlassTokens.Cream14,
            isDark = true,
        )
    }
    CompositionLocalProvider(
        LocalAuralisExtras provides extras,
        LocalVisualPerformance provides visualPerformance,
    ) {
        MaterialTheme(
            colorScheme = GlassColorScheme,
            typography = GlassTypography,
            shapes = GlassShapes,
            content = content,
        )
    }
}
