package com.auralis.app.ui.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
 * [onVisible] is called when the targets first appear and again whenever the screen returns to the
 * foreground; [onHidden] cancels those ids when the screen is disposed or the app is backgrounded,
 * so a retry never runs for a song the user can no longer see.
 */
@Composable
fun MetadataFillEffect(
    targets: List<Track>,
    onVisible: (List<Track>) -> Unit,
    onHidden: (List<String>) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val ids = remember(targets) { targets.map { it.id } }
    val latestTargets by rememberUpdatedState(targets)
    val visible by rememberUpdatedState(onVisible)
    val hidden by rememberUpdatedState(onHidden)

    DisposableEffect(lifecycleOwner, ids) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> latestTargets.takeIf { it.isNotEmpty() }?.let(visible)
                Lifecycle.Event.ON_STOP -> latestTargets.takeIf { it.isNotEmpty() }
                    ?.let { hidden(it.map { track -> track.id }) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            latestTargets.takeIf { it.isNotEmpty() }?.let(visible)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestTargets.takeIf { it.isNotEmpty() }?.let { hidden(it.map { track -> track.id }) }
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