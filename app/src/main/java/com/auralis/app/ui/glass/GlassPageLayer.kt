package com.auralis.app.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.app.domain.model.PlayerBackground

/**
 * Gives a whole page its own Kyant backdrop layer, so every glass surface on it (rows, cards, chips,
 * header buttons) samples, blurs, refracts and rims the real backdrop instead of the flat tint
 * fallback.
 *
 * The app root records every page into one layer and therefore provides a null backdrop to pages
 * (a surface sampling the layer it is recorded into re-enters its RenderNode and aborts the
 * renderer). This wrapper uses the same safe pattern as Settings and the full-screen player: the
 * ambient background is recorded into a page-local layer and the page content is a sibling above it.
 *
 * [enabled] is false for pages that already own a layer (Settings and History), so they are never
 * nested. Normal theme and Android < 12 compose the content unchanged with no backdrop.
 */
@Composable
fun GlassPageLayer(
    playerBackground: PlayerBackground,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        SettingsLiquidGlass(playerBackground, modifier, glows = false, content = content)
    } else {
        content()
    }
}
