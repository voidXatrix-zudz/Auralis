package com.auralis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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

/** Premium Neo-inspired glassmorphic design tokens and specular materials. */
object GlassTokens {
    val Obsidian = Color(0xFF07080E)
    val Surface = Color(0xFF12141C)
    val Surface2 = Color(0xFF181B26)
    val SurfaceHigh = Color(0xFF222634)
    val Silver = Color(0xFFF1F5F9)
    val SilverMuted = Color(0xFF94A3B8)
    val Violet = Color(0xFFA78BFA)
    /** Soft cool-blue companion to the violet accent (deliberately not teal/bright cyan). */
    val Ice = Color(0xFF8FA8FF)
    val OnAccent = Color(0xFF07080E)
    val VioletContainer = Color(0xFF2A2244)
    val Error = Color(0xFFF87171)
    val Hairline = Color.White.copy(alpha = 0.12f)
    val SpecularLight = Color.White.copy(alpha = 0.26f)
    val SpecularDark = Color.White.copy(alpha = 0.04f)

    // Reusable specular refraction border brush: crisp top-left catch, soft fade bottom-right
    val BorderBrush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.16f)),
    )

    // Vibrant neo accent gradient (violet to ice cyan)
    val AccentGradient = Brush.linearGradient(listOf(Violet, Ice))

    // Subtle internal glass sheen
    val SurfaceSheen = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f), Color.Transparent),
    )

    // Radii (dp): sm 14, md 20, card 24, lg 26, xl 32
    val RadiusSm = 14.dp
    val RadiusMd = 20.dp
    val RadiusCard = 24.dp
    val RadiusLg = 26.dp
    val RadiusXl = 32.dp
}

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
    primary = GlassTokens.Violet, onPrimary = GlassTokens.OnAccent,
    primaryContainer = GlassTokens.VioletContainer, onPrimaryContainer = GlassTokens.Silver,
    secondary = GlassTokens.Ice, onSecondary = GlassTokens.OnAccent,
    secondaryContainer = GlassTokens.VioletContainer, onSecondaryContainer = GlassTokens.Silver,
    tertiary = GlassTokens.Ice, onTertiary = GlassTokens.OnAccent,
    background = GlassTokens.Obsidian, onBackground = GlassTokens.Silver,
    surface = GlassTokens.Surface, onSurface = GlassTokens.Silver,
    surfaceVariant = GlassTokens.Surface2, onSurfaceVariant = GlassTokens.SilverMuted,
    surfaceTint = GlassTokens.Violet,
    surfaceContainerLowest = GlassTokens.Obsidian, surfaceContainerLow = GlassTokens.Surface,
    surfaceContainer = GlassTokens.Surface, surfaceContainerHigh = GlassTokens.Surface2,
    surfaceContainerHighest = GlassTokens.SurfaceHigh,
    outline = Color.White.copy(alpha = 0.20f), outlineVariant = GlassTokens.Hairline,
    error = GlassTokens.Error, onError = Color(0xFF1A0508),
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(GlassTokens.RadiusSm),
    medium = RoundedCornerShape(GlassTokens.RadiusMd),
    large = RoundedCornerShape(GlassTokens.RadiusLg),
    extraLarge = RoundedCornerShape(GlassTokens.RadiusXl),
)

/** Neo-grotesque geometric typography utilizing bundled Sora and Manrope fonts. */
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
 * Single entry point. NORMAL calls the existing [AuralisAppTheme] with the same arguments as before,
 * so the Normal theme is untouched.
 */
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
            mutedForeground = GlassTokens.SilverMuted,
            border = GlassTokens.Hairline,
            // Same translucent smoked glass on every performance profile.
            glass = GlassTokens.Surface.copy(alpha = 0.72f),
            glassBorder = GlassTokens.Hairline,
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
