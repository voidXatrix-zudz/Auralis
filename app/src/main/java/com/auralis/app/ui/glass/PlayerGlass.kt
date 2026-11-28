package com.auralis.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.theme.GlassTokens

/**
 * Glass for the full-screen player's controls: the favourite and more buttons, the seek and volume
 * tracks, the queue shuffle/repeat pill and the selected bottom icon.
 *
 * It is the same material as the dock. The surface goes through [auralisGlass] with
 * [GlassRole.Dock], so the Appearance "Dock transparency" and outline settings drive it too, and it
 * samples whatever [LocalAppBackdrop] holds. The player provides a layer that records only its
 * background, so the controls blur and refract the artwork behind them.
 *
 * [lens] = false is for thin bars (slider tracks), where the refraction lens does not fit.
 * Without a backdrop (Android 11 and older) the control keeps a flat translucent white fill.
 */
@Composable
fun Modifier.playerGlass(
    shape: Shape,
    lens: Boolean = true,
    flatFillAlpha: Float = FLAT_FILL_ALPHA,
    flatBorderAlpha: Float = FLAT_BORDER_ALPHA,
): Modifier {
    val config = LocalGlassDrag.current.value ?: LocalGlassConfig.current
    val backdrop = LocalAppBackdrop.current
    return if (backdrop != null && config.isGlassCapable) {
        this
            .auralisGlass(shape, GlassRole.Dock, lens)
            .background(Color.White.copy(alpha = GlassTokens.BUBBLE_FILL_ALPHA), shape)
    } else {
        this
            .clip(shape)
            .background(Color.White.copy(alpha = flatFillAlpha))
            .then(
                if (flatBorderAlpha > 0f) {
                    Modifier.border(1.dp, Color.White.copy(alpha = flatBorderAlpha), shape)
                } else {
                    Modifier
                },
            )
    }
}

private const val FLAT_FILL_ALPHA = 0.16f
private const val FLAT_BORDER_ALPHA = 0.25f
