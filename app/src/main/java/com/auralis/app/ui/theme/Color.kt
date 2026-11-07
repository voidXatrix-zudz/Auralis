package com.auralis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens ported from the Auralis web reference (src/styles.css). The reference defines
 * colours in OKLCH; these are the exact sRGB equivalents.
 */
data class AuralisPalette(
    val background: Color,
    val foreground: Color,
    val surface: Color,
    val surface2: Color,
    val popover: Color,
    val secondary: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val destructive: Color,
    val onDestructive: Color,
    val primaryForeground: Color,
    /** Hairline border: white at 9% on dark, black at 9% on light. */
    val border: Color,
)

val DarkPalette = AuralisPalette(
    background = Color(0xFF0A0C0F),
    foreground = Color(0xFFEFF2F5),
    surface = Color(0xFF13161A),
    surface2 = Color(0xFF1E2226),
    popover = Color(0xFF181B1E),
    secondary = Color(0xFF202429),
    muted = Color(0xFF1C2023),
    mutedForeground = Color(0xFFA0A5AB),
    accent = Color(0xFF25292E),
    destructive = Color(0xFFF14D4C),
    onDestructive = Color(0xFF1B0808),
    primaryForeground = Color(0xFF04100E),
    border = Color(0x17FFFFFF),
)

val LightPalette = AuralisPalette(
    background = Color(0xFFF5F7F9),
    foreground = Color(0xFF0E1216),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE8EBEF),
    popover = Color(0xFFFFFFFF),
    secondary = Color(0xFFE5E8EC),
    muted = Color(0xFFE8EBEF),
    mutedForeground = Color(0xFF51565B),
    accent = Color(0xFFE1E5EA),
    destructive = Color(0xFFF14D4C),
    onDestructive = Color(0xFF1B0808),
    primaryForeground = Color(0xFF04100E),
    border = Color(0x17000000),
)

/** Selectable accent colours, same names and order as the web reference. */
object Accents {
    val all: LinkedHashMap<String, Color> = linkedMapOf(
        "Teal" to Color(0xFF41D2B3),
        "Green" to Color(0xFF56D57B),
        "Violet" to Color(0xFFAC8FF8),
        "Blue" to Color(0xFF52B5F4),
        "Amber" to Color(0xFFF9B64F),
        "Rose" to Color(0xFFF87584),
    )

    fun color(name: String): Color = all[name] ?: all.getValue("Teal")
}
