package com.auralis.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.glass.backdrop.backdrops.LayerBackdrop
import com.auralis.app.glass.backdrop.backdrops.layerBackdrop
import com.auralis.app.glass.backdrop.backdrops.rememberLayerBackdrop
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle
import kotlin.math.roundToInt

/**
 * Liquid glass for the Settings hub and every Settings sub-page.
 *
 * Pages are composed inside the root's recorded layer, and the root deliberately provides
 * `LocalAppBackdrop = null` to them: a surface must never sample the layer it is being recorded
 * into (that re-enters the RenderNode and aborts the renderer). That is why Settings used to get
 * only the flat tint fallback while the dock, which sits outside that layer, got the real glass.
 *
 * This scaffold gives Settings a layer of its own, the same pattern the full-screen player uses:
 *  - the ambient background (the app wallpaper, or soft cream glows) is recorded into a
 *    settings-local [LayerBackdrop];
 *  - the page content is a *sibling* drawn above it, with [LocalAppBackdrop] pointing at that
 *    layer, so every existing `GlassPanel` / `glassSurface` / `playerGlass` call inside Settings
 *    blurs, saturates, refracts and rims through the same [auralisGlass] pipeline as the dock.
 *
 * Nothing here adds a glass setting: blur, lens, tint and outlines still come from
 * [LocalGlassConfig] and [GlassTokens]. On the Normal theme and below Android 12 the content is
 * composed exactly as before, with no backdrop.
 *
 * The content slot keeps the same position in the tree whether or not glass is active, so
 * switching theme on the Appearance page does not recreate the page (scroll position and the
 * selector animation survive).
 */
@Composable
fun SettingsLiquidGlass(
    playerBackground: PlayerBackground,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val capable = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC && LocalGlassConfig.current.isGlassCapable
    val backdrop = rememberLayerBackdrop(onDraw = OpaqueLayerOnDraw)
    Box(modifier.fillMaxSize()) {
        if (capable) SettingsAmbient(backdrop, playerBackground)
        CompositionLocalProvider(LocalAppBackdrop provides if (capable) backdrop else null) {
            content()
        }
    }
}

/** Where the ambient layer sits in the window, so the wallpaper can be drawn window-aligned. */
private data class AmbientFrame(val origin: IntOffset = IntOffset.Zero, val rootSize: IntSize = IntSize.Zero)

/**
 * The recorded source. Only this subtree is recorded; the page content is never inside it.
 *
 * With a chosen player background the photo is drawn at the full window size and offset back by
 * this box's position in the window. It therefore lands on exactly the same pixels as the root's
 * own wallpaper behind it: no seam under the status bar, and the glass samples what is really
 * behind each card. Without a wallpaper the page is pure black, where blur and refraction would
 * have nothing to bend, so a few faint cream glows (palette cream at a low opacity, no new hue)
 * give the glass something to catch.
 */
@Composable
private fun SettingsAmbient(backdrop: LayerBackdrop, playerBackground: PlayerBackground) {
    var frame by remember { mutableStateOf(AmbientFrame()) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInRoot()
                val next = AmbientFrame(
                    origin = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    rootSize = coordinates.findRootCoordinates().size,
                )
                if (next != frame) frame = next
            }
            .clipToBounds()
            .layerBackdrop(backdrop),
    ) {
        if (playerBackground != PlayerBackground.NONE) {
            Box(
                Modifier.layout { measurable, constraints ->
                    val current = frame
                    val placeable = measurable.measure(
                        Constraints.fixed(current.rootSize.width, current.rootSize.height),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(-current.origin.x, -current.origin.y)
                    }
                },
            ) {
                PlayerBackgroundImage(playerBackground)
            }
        } else if (AMBIENT_GLOWS_ENABLED) {
            Box(Modifier.fillMaxSize().drawBehind { drawAmbientGlows() })
        }
    }
}

/** Set to false for a pure black Settings background (the glass then has nothing to refract). */
private const val AMBIENT_GLOWS_ENABLED = true

private fun DrawScope.drawAmbientGlows() {
    val reach = size.maxDimension
    glow(Offset(size.width * 0.12f, size.height * 0.08f), reach * 0.55f, 0.16f)
    glow(Offset(size.width * 0.96f, size.height * 0.46f), reach * 0.50f, 0.10f)
    glow(Offset(size.width * 0.28f, size.height * 0.96f), reach * 0.55f, 0.12f)
}

private fun DrawScope.glow(center: Offset, radius: Float, alpha: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(GlassTokens.Cream.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
    )
}
