package com.auralis.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.auralis.app.R

/**
 * Single source of truth for the lyrics page font.
 *
 * Change [DEFAULT_FONT_NAME] and the `latin` family below to swap the bundled default. The fonts are
 * variable TTFs (SIL OFL, licence files under `assets/licenses/`) declared at the exact weights the
 * page uses, so the active line is ExtraBold in every script and never falls back to the device
 * font. Script-specific Noto Sans families are picked per line; the system font is only reached for
 * glyphs that none of the bundled families contain (e.g. CJK, Arabic, Hebrew).
 */
object LyricsTypography {
    const val DEFAULT_FONT_NAME = "Plus Jakarta Sans"

    private fun variableFamily(resId: Int): FontFamily = FontFamily(
        variableFont(resId, 400, FontWeight.Normal),
        variableFont(resId, 500, FontWeight.Medium),
        variableFont(resId, 600, FontWeight.SemiBold),
        variableFont(resId, 700, FontWeight.Bold),
        variableFont(resId, 800, FontWeight.ExtraBold),
    )

    @OptIn(ExperimentalTextApi::class)
    private fun variableFont(resId: Int, axisWeight: Int, weight: FontWeight): Font =
        Font(
            resId = resId,
            weight = weight,
            style = FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)),
        )

    private val latin: FontFamily = variableFamily(R.font.plus_jakarta_sans)
    private val devanagari: FontFamily = variableFamily(R.font.noto_sans_devanagari)
    private val tamil: FontFamily = variableFamily(R.font.noto_sans_tamil)
    private val gurmukhi: FontFamily = variableFamily(R.font.noto_sans_gurmukhi)

    private val all: List<FontFamily> = listOf(latin, devanagari, tamil, gurmukhi)

    /**
     * Chooses the bundled family for the dominant script in [text]. Lines that mix Latin with one
     * Indic script use the Indic family (its Latin punctuation/digits are still drawn), and the
     * platform fallback only handles scripts we do not bundle.
     */
    fun familyFor(text: String): FontFamily {
        if (text.isEmpty()) return latin
        var devanagariCount = 0
        var tamilCount = 0
        var gurmukhiCount = 0
        for (char in text) {
            when (char.code) {
                in 0x0900..0x097F, in 0xA8E0..0xA8FF, in 0x1CD0..0x1CFF -> devanagariCount++
                in 0x0B80..0x0BFF -> tamilCount++
                in 0x0A00..0x0A7F -> gurmukhiCount++
            }
        }
        return when {
            devanagariCount > 0 && devanagariCount >= tamilCount && devanagariCount >= gurmukhiCount -> devanagari
            tamilCount > 0 && tamilCount >= gurmukhiCount -> tamil
            gurmukhiCount > 0 -> gurmukhi
            else -> latin
        }
    }

    /** Same type scale as the active theme, with every family replaced by the bundled Latin face. */
    fun withFamily(base: Typography): Typography = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = latin),
        displayMedium = base.displayMedium.copy(fontFamily = latin),
        displaySmall = base.displaySmall.copy(fontFamily = latin),
        headlineLarge = base.headlineLarge.copy(fontFamily = latin),
        headlineMedium = base.headlineMedium.copy(fontFamily = latin),
        headlineSmall = base.headlineSmall.copy(fontFamily = latin),
        titleLarge = base.titleLarge.copy(fontFamily = latin),
        titleMedium = base.titleMedium.copy(fontFamily = latin),
        titleSmall = base.titleSmall.copy(fontFamily = latin),
        bodyLarge = base.bodyLarge.copy(fontFamily = latin),
        bodyMedium = base.bodyMedium.copy(fontFamily = latin),
        bodySmall = base.bodySmall.copy(fontFamily = latin),
        labelLarge = base.labelLarge.copy(fontFamily = latin),
        labelMedium = base.labelMedium.copy(fontFamily = latin),
        labelSmall = base.labelSmall.copy(fontFamily = latin),
    )

    /** Loads every bundled face once, before the first lyrics screen is shown. */
    suspend fun preload(resolver: FontFamily.Resolver) {
        for (family in all) {
            try {
                resolver.preload(family)
            } catch (_: Exception) {
                // A missing glyph set must never prevent the app from starting.
            }
        }
    }
}

/** Place once at the app root: warms the bundled lyric faces so the first open has no font flash. */
@Composable
fun LyricsFontPreloader() {
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(resolver) { LyricsTypography.preload(resolver) }
}