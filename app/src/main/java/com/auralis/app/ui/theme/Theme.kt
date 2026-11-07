package com.auralis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.auralis.app.ui.performance.LocalVisualPerformance
import com.auralis.app.ui.performance.VisualPerformanceTier

/** Tokens Material 3 has no slot for: layered surfaces, hairline borders and the glass treatment. */
@Immutable
data class AuralisExtras(
    val surface2: Color,
    val mutedForeground: Color,
    val border: Color,
    /** Translucent fill for floating glass panels (nav bar, mini player). */
    val glass: Color,
    val glassBorder: Color,
    val isDark: Boolean,
)

internal val LocalAuralisExtras = staticCompositionLocalOf {
    AuralisExtras(
        surface2 = DarkPalette.surface2,
        mutedForeground = DarkPalette.mutedForeground,
        border = DarkPalette.border,
        glass = DarkPalette.surface.copy(alpha = 0.72f),
        glassBorder = DarkPalette.foreground.copy(alpha = 0.12f),
        isDark = true,
    )
}

object Auralis {
    val extras: AuralisExtras
        @Composable @ReadOnlyComposable get() = LocalAuralisExtras.current
}

/** Radii follow the web reference (base radius 0.9rem). */
private val AuralisShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun AuralisAppTheme(
    darkTheme: Boolean,
    accentName: String,
    visualPerformance: VisualPerformanceTier = VisualPerformanceTier.MEDIUM,
    content: @Composable () -> Unit,
) {
    val p = if (darkTheme) DarkPalette else LightPalette
    val primary = Accents.color(accentName)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = primary, onPrimary = p.primaryForeground,
            secondary = p.secondary, onSecondary = p.foreground,
            tertiary = primary, onTertiary = p.primaryForeground,
            background = p.background, onBackground = p.foreground,
            surface = p.surface, onSurface = p.foreground,
            surfaceVariant = p.muted, onSurfaceVariant = p.mutedForeground,
            surfaceContainerLowest = p.background, surfaceContainerLow = p.surface,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
            surfaceContainerHighest = p.secondary,
            outline = p.border, outlineVariant = p.border,
            error = p.destructive, onError = p.onDestructive,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = p.primaryForeground,
            secondary = p.secondary, onSecondary = p.foreground,
            tertiary = primary, onTertiary = p.primaryForeground,
            background = p.background, onBackground = p.foreground,
            surface = p.surface, onSurface = p.foreground,
            surfaceVariant = p.muted, onSurfaceVariant = p.mutedForeground,
            surfaceContainerLowest = p.background, surfaceContainerLow = p.surface,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
            surfaceContainerHighest = p.secondary,
            outline = p.border, outlineVariant = p.border,
            error = p.destructive, onError = p.onDestructive,
        )
    }

    val extras = AuralisExtras(
        surface2 = p.surface2,
        mutedForeground = p.mutedForeground,
        border = p.border,
        // Same glass opacity on every performance profile.
        glass = p.surface.copy(alpha = if (darkTheme) 0.72f else 0.78f),
        glassBorder = p.foreground.copy(alpha = 0.12f),
        isDark = darkTheme,
    )

    CompositionLocalProvider(LocalAuralisExtras provides extras, LocalVisualPerformance provides visualPerformance) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AuralisTypography,
            shapes = AuralisShapes,
            content = content,
        )
    }
}
