package com.auralis.app.ui.glass

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.theme.GlassTokens
import kotlin.math.roundToInt

/**
 * In-window glass overlays.
 *
 * Popup and dialog windows cannot sample the app's recorded backdrop layer, so a menu rendered
 * through `DropdownMenu` can only fake glass with a window blur and a dark tint. Overlays shown
 * through this portal are composed by [GlassPortalHost] in the activity window, as a sibling above
 * the recorded content, exactly like the dock. They therefore use the same [auralisGlass] material
 * (blur, tint, lens, rim) and follow the same Appearance settings.
 *
 * The portal is only provided while the glass layer is recorded (Glassmorphic theme on a capable
 * device). Everywhere else callers keep their stock popup or dialog.
 */
@Stable
class GlassPortalState {
    internal var hostView by mutableStateOf<View?>(null)

    internal class Slot(val key: Any, val entry: GlassPortalEntry)

    private val entries = mutableStateListOf<Slot>()

    internal val slots: List<Slot> get() = entries

    internal fun show(key: Any, entry: GlassPortalEntry) {
        remove(key)
        entries.add(Slot(key, entry))
    }

    internal fun remove(key: Any) {
        entries.removeAll { it.key === key }
    }
}

val LocalGlassPortal = staticCompositionLocalOf<GlassPortalState?> { null }

internal sealed interface GlassPortalPlacement {
    class Anchored(val bounds: State<Rect>) : GlassPortalPlacement
    object Centered : GlassPortalPlacement
    /** A floating glass sheet pinned to the bottom edge (above the navigation bar). */
    object Bottom : GlassPortalPlacement
}

internal class GlassPortalEntry(
    val placement: GlassPortalPlacement,
    val dismissOnOutsideTap: Boolean,
    val dismissOnBack: Boolean,
    val maxHeight: Dp,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Mount once, last, inside the root box that records the backdrop. */
@Composable
fun GlassPortalHost(state: GlassPortalState, modifier: Modifier = Modifier) {
    val view = LocalView.current
    SideEffect { if (state.hostView !== view) state.hostView = view }

    val slots = state.slots
    if (slots.isEmpty()) return
    val top = slots.last().entry
    BackHandler(enabled = top.dismissOnBack) { top.onDismiss() }

    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        slots.forEach { slot -> key(slot.key) { PortalLayer(slot.entry, origin) } }
    }
}

@Composable
private fun PortalLayer(entry: GlassPortalEntry, origin: Offset) {
    val density = LocalDensity.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(durationMillis = 170)) }

    val centered = entry.placement === GlassPortalPlacement.Centered
    val bottom = entry.placement === GlassPortalPlacement.Bottom
    val scrim = when {
        bottom -> GlassMapping.SHEET_SCRIM
        centered -> GlassMapping.DIALOG_SCRIM
        else -> GlassMapping.MENU_BACKDROP_SCRIM
    }
    val margin = with(density) { (if (centered) 28.dp else 12.dp).roundToPx() }
    val gap = with(density) { 4.dp.roundToPx() }
    val maxHeight = with(density) { entry.maxHeight.roundToPx() }
    val shape = RoundedCornerShape(GlassTokens.RadiusCard)

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(GlassTokens.Canvas.copy(alpha = scrim * reveal.value)) }
            .pointerInput(entry) {
                detectTapGestures { if (entry.dismissOnOutsideTap) entry.onDismiss() }
            },
    ) {
        Layout(
            content = { PortalSurface(entry, shape, reveal, centered, bottom) },
            modifier = Modifier
                .fillMaxSize()
                .then(if (bottom) Modifier.navigationBarsPadding() else Modifier),
        ) { measurables, constraints ->
            val availableWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)
            val availableHeight = (constraints.maxHeight - 2 * margin).coerceAtLeast(0)
            val placeable = measurables.first().measure(
                Constraints(maxWidth = availableWidth, maxHeight = minOf(availableHeight, maxHeight)),
            )
            val width = placeable.width
            val height = placeable.height
            val x: Int
            val y: Int
            when (val placement = entry.placement) {
                is GlassPortalPlacement.Anchored -> {
                    val anchor = placement.bounds.value.translate(-origin.x, -origin.y)
                    val rightSide = anchor.center.x > constraints.maxWidth / 2f
                    val preferredX = if (rightSide) anchor.right.roundToInt() - width else anchor.left.roundToInt()
                    x = preferredX.coerceIn(margin, (constraints.maxWidth - width - margin).coerceAtLeast(margin))
                    val below = anchor.bottom.roundToInt() + gap
                    y = if (below + height <= constraints.maxHeight - margin) {
                        below
                    } else {
                        val above = anchor.top.roundToInt() - gap - height
                        if (above >= margin) above else (constraints.maxHeight - height - margin).coerceAtLeast(margin)
                    }
                }
                GlassPortalPlacement.Centered -> {
                    x = (constraints.maxWidth - width) / 2
                    y = (constraints.maxHeight - height) / 2
                }
                GlassPortalPlacement.Bottom -> {
                    x = (constraints.maxWidth - width) / 2
                    y = (constraints.maxHeight - height - margin).coerceAtLeast(0)
                }
            }
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
        }
    }
}

@Composable
private fun PortalSurface(
    entry: GlassPortalEntry,
    shape: RoundedCornerShape,
    reveal: Animatable<Float, *>,
    centered: Boolean,
    bottom: Boolean,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .graphicsLayer {
                val scale = 0.94f + 0.06f * reveal.value
                if (bottom) {
                    // A sheet rises from the bottom edge instead of popping from the top.
                    translationY = (1f - reveal.value) * 56.dp.toPx()
                    alpha = reveal.value
                } else {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
            }
            .then(if (centered || bottom) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.widthIn(min = 188.dp, max = 300.dp).width(IntrinsicSize.Max))
            .auralisGlass(shape, GlassRole.Panel)
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(scroll)
            .then(if (centered || bottom) Modifier else Modifier.padding(horizontal = 4.dp, vertical = 6.dp)),
    ) {
        entry.content(this)
    }
}

@Composable
internal fun GlassPortalMenu(
    portal: GlassPortalState,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val anchor = remember { mutableStateOf(Rect.Zero) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val slotKey = remember { Any() }

    // Zero-size marker: the anchor is the parent layout, the same one DropdownMenu anchors to.
    Spacer(
        Modifier.onGloballyPositioned { coordinates ->
            anchor.value = (coordinates.parentLayoutCoordinates ?: coordinates).boundsInRoot()
        },
    )

    DisposableEffect(expanded) {
        if (expanded) {
            portal.show(
                slotKey,
                GlassPortalEntry(
                    placement = GlassPortalPlacement.Anchored(anchor),
                    dismissOnOutsideTap = true,
                    dismissOnBack = true,
                    maxHeight = maxHeight,
                    onDismiss = { dismiss() },
                    content = { body() },
                ),
            )
        }
        onDispose { portal.remove(slotKey) }
    }
}

/** Bottom sheet drawn by the portal host, so it samples the app's recorded KYANT0 backdrop. */
@Composable
internal fun GlassPortalSheet(
    portal: GlassPortalState,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val slotKey = remember { Any() }

    DisposableEffect(Unit) {
        portal.show(
            slotKey,
            GlassPortalEntry(
                placement = GlassPortalPlacement.Bottom,
                dismissOnOutsideTap = true,
                dismissOnBack = true,
                maxHeight = 640.dp,
                onDismiss = { dismiss() },
                content = { body() },
            ),
        )
        onDispose { portal.remove(slotKey) }
    }
}

@Composable
internal fun GlassPortalDialog(
    portal: GlassPortalState,
    onDismissRequest: () -> Unit,
    dismissOnOutsideTap: Boolean,
    dismissOnBack: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val slotKey = remember { Any() }

    DisposableEffect(Unit) {
        portal.show(
            slotKey,
            GlassPortalEntry(
                placement = GlassPortalPlacement.Centered,
                dismissOnOutsideTap = dismissOnOutsideTap,
                dismissOnBack = dismissOnBack,
                maxHeight = 640.dp,
                onDismiss = { dismiss() },
                content = { body() },
            ),
        )
        onDispose { portal.remove(slotKey) }
    }
}
