package com.auralis.app.ui.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.app.domain.model.Track

/**
 * Keeps metadata retries tied to the screen that shows them.
 *
 * A row joins the retry set when it first appears (and again when the screen returns to the
 * foreground) and leaves it only when it disappears from the list or the screen is left. Diffing
 * membership this way means one row finishing never cancels and restarts another row's window.
 */
@Composable
fun MetadataFillEffect(
    targets: List<Track>,
    onVisible: (List<Track>) -> Unit,
    onHidden: (List<String>) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Ids this effect owns right now, so a membership change releases only the rows that left.
    val requested = remember { mutableStateListOf<String>() }
    val latestTargets by rememberUpdatedState(targets)
    val visible by rememberUpdatedState(onVisible)
    val hidden by rememberUpdatedState(onHidden)

    val ids = targets.map { it.id }
    LaunchedEffect(ids) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        val removed = requested.filterNot(ids::contains)
        if (removed.isNotEmpty()) {
            requested.removeAll(removed)
            hidden(removed)
        }
        val added = latestTargets.filterNot { track -> track.id in requested }
        if (added.isNotEmpty()) {
            requested.addAll(added.map { it.id })
            visible(added)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val current = latestTargets
                    requested.clear()
                    requested.addAll(current.map { it.id })
                    if (current.isNotEmpty()) visible(current)
                }
                Lifecycle.Event.ON_STOP -> requested.toList().takeIf { it.isNotEmpty() }?.let { leaving ->
                    requested.clear()
                    hidden(leaving)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            requested.toList().takeIf { it.isNotEmpty() }?.let(hidden)
            requested.clear()
        }
    }
}

/** One neutral loading bar shown instead of a raw video id while metadata is still being fetched. */
@Composable
fun MetadataSkeleton(
    widthFraction: Float,
    height: Dp,
    modifier: Modifier = Modifier,
    alpha: Float = 0.16f,
) {
    Box(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(height / 3f))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
    )
}

/** Title for a row, never the internal video id and never an empty line. */
val Track.displayTitle: String
    get() = title.takeUnless { it.isBlank() || (isRemote && it == remoteId) }
        ?: displayName.takeUnless { it.isBlank() || (isRemote && it == remoteId) }
        ?: "Unknown title"