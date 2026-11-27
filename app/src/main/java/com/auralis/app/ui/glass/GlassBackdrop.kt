package com.auralis.app.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.auralis.app.glass.backdrop.backdrops.LayerBackdrop
import com.auralis.app.ui.theme.GlassTokens

/**
 * the ONE recorded backdrop every glass surface samples.
 *
 * The app root records the page container into a single [LayerBackdrop] and provides it here.
 * Null in the Normal theme and on API < 31, where no layer is recorded and every glass surface
 * falls back to a plain translucent tint . `staticCompositionLocalOf` is right
 * because the instance only changes when the theme itself does (a full recomposition).
 */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * the recording lambda MUST draw an opaque background first, then the content.
 *
 * If the recorded layer is transparent anywhere, glass shows sharp, unblurred text through it -
 * the exact cause of the old "text shows through the dock" bug. A single stable lambda keeps
 * `rememberLayerBackdrop` from rebuilding the layer on every recomposition. No allocations here.
 */
val OpaqueLayerOnDraw: ContentDrawScope.() -> Unit = {
    drawRect(GlassTokens.Canvas)
    drawContent()
}
