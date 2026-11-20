package com.auralis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.data.youtube.LiveSyncState
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * Tiny "Live" marker for Liked Songs only. Cream when live, dimmed when idle/offline/syncing, and a
 * short "Reconnect" label when the session expired. [pulseKey] is bumped once per batch of new likes
 * and drives a single scale pulse — there is no continuous animation.
 */
@Composable
fun LiveDot(
    state: LiveSyncState,
    pulseKey: Long,
    modifier: Modifier = Modifier,
) {
    if (state == LiveSyncState.Disabled) return
    if (state == LiveSyncState.AuthExpired) {
        Text(
            text = "Reconnect",
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val isGlass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val live = state == LiveSyncState.Live
    val base = if (isGlass) GlassTokens.Cream else MaterialTheme.colorScheme.primary
    val color = base.copy(alpha = if (live) 1f else 0.35f)
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        if (pulseKey > 0L) {
            scale.snapTo(1f)
            scale.animateTo(1.5f, animationSpec = tween(durationMillis = 160))
            scale.animateTo(1f, animationSpec = tween(durationMillis = 220))
        }
    }
    Box(
        modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(color),
    )
}
