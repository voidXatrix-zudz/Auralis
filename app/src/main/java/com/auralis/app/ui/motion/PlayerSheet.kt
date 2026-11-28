package com.auralis.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.theme.GlassTokens
import kotlinx.coroutines.launch

/**
 * Open / close motion for the full-screen player.
 *
 * Instead of swapping the player and the pages instantly, the player is a sheet that rises from the
 * mini-player and settles with a soft spring, and falls back into it on close. One progress value
 * (0 = collapsed into the mini-player, 1 = fully open) drives everything, so the sheet, the pages
 * behind it and a finger drag on the grabber are always the same animation:
 *
 *  - the sheet slides up from the mini-player while growing from 88% to 100% and squaring its corners;
 *  - its content fades in a beat after the surface, so the artwork lands first and the controls follow;
 *  - the pages behind recede (scale + dim) like depth, and are skipped entirely once the sheet is open;
 *  - drag the handle at the top of the sheet to close it interactively; release decides by velocity.
 *
 * Every per-frame read happens in a graphicsLayer block (draw phase), so nothing recomposes while it
 * moves. "Remove animations" snaps the progress instead of animating it.
 */
@Stable
class PlayerSheetState internal constructor(internal val progress: Animatable<Float, AnimationVector1D>) {
    /** True from the first frame of opening until the last frame of closing. */
    val isVisible: Boolean by derivedStateOf { progress.value > 0.002f }
}

internal val PlayerSheetOpenSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 300f)
internal val PlayerSheetCloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 380f)

@Composable
fun rememberPlayerSheetState(open: Boolean, reduceMotion: Boolean): PlayerSheetState {
    val progress = remember { Animatable(if (open) 1f else 0f).apply { updateBounds(0f, 1f) } }
    LaunchedEffect(open, reduceMotion) {
        val target = if (open) 1f else 0f
        if (reduceMotion) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, if (open) PlayerSheetOpenSpec else PlayerSheetCloseSpec)
        }
    }
    return remember(progress) { PlayerSheetState(progress) }
}

/** Pages behind the sheet: they shrink and dim as it opens, and are not drawn once it is fully open. */
fun Modifier.recedeBehindPlayer(state: PlayerSheetState): Modifier = graphicsLayer {
    val p = state.progress.value.coerceIn(0f, 1f)
    val s = 1f - 0.06f * p
    scaleX = s
    scaleY = s
    alpha = if (p >= 0.999f) 0f else 1f - 0.5f * p
}

@Composable
fun PlayerSheet(
    state: PlayerSheetState,
    onDragDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val p = state.progress.value.coerceIn(0f, 1f)
                val peek = 140.dp.toPx() // the mini-player strip the sheet grows out of
                translationY = (1f - p) * (size.height - peek)
                val s = 0.88f + 0.12f * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
                val t = (p / 0.12f).coerceIn(0f, 1f)
                alpha = t * t * (3f - 2f * t)
                shape = RoundedCornerShape((1f - p) * 40.dp.toPx())
                clip = true
            }
            .background(GlassTokens.Canvas)
            // The sheet sits above the dock and pages: swallow touches that no child handled.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = ((state.progress.value - 0.05f) / 0.40f).coerceIn(0f, 1f)
            },
        ) { content() }

        val dragState = rememberDraggableState { delta ->
            scope.launch { state.progress.snapTo(state.progress.value - delta / heightPx * 1.15f) }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(160.dp)
                .height(28.dp)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (velocity > 1200f || state.progress.value < 0.72f) {
                            onDragDismiss()
                        } else {
                            scope.launch { state.progress.animateTo(1f, PlayerSheetOpenSpec) }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(GlassTokens.Cream.copy(alpha = 0.35f)),
            )
        }
    }
}
