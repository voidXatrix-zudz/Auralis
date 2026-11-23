package com.auralis.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.R

/** Loading placeholders shaped like the real layout. */
@Composable
fun HomeSkeleton(style: HomeStyle, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "homeShimmer")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "homeShimmerPulse",
    )
    val fill = lerp(HomeCardPalette.Surface, HomeCardPalette.SurfaceAlt, pulse)
    Column(modifier.fillMaxWidth()) {
        SkeletonBox(
            Modifier.padding(start = HomeDimens.ScreenPadding, top = 18.dp).width(140.dp).height(18.dp),
            RoundedCornerShape(9.dp),
            fill,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeDimens.ScreenPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(HomeDimens.CardGap),
            userScrollEnabled = false,
        ) {
            items(3) {
                SkeletonBox(
                    Modifier.width(HomeDimens.TopPickWidth).height(HomeDimens.TopPickHeight),
                    RoundedCornerShape(HomeDimens.TopPickRadius),
                    fill,
                )
            }
        }
        SkeletonBox(
            Modifier.padding(start = HomeDimens.ScreenPadding, top = 6.dp).width(120.dp).height(18.dp),
            RoundedCornerShape(9.dp),
            fill,
        )
        repeat(4) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeDimens.ScreenPadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(
                    Modifier.size(HomeDimens.SongCover),
                    RoundedCornerShape(HomeDimens.SongCoverRadius),
                    fill,
                )
                Column(Modifier.weight(1f).padding(start = HomeDimens.SongRowGap)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.7f).height(14.dp), RoundedCornerShape(7.dp), fill)
                    Spacer(Modifier.height(6.dp).width(1.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.45f).height(12.dp), RoundedCornerShape(6.dp), fill)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBox(modifier: Modifier, shape: Shape, color: Color) {
    Box(modifier.clip(shape).background(color))
}

@Composable
fun HomeErrorBanner(
    message: String,
    refreshing: Boolean,
    style: HomeStyle,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.ScreenPadding, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(style.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            Modifier.weight(1f),
            color = style.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry, enabled = !refreshing) {
            Text(stringResource(R.string.home_retry), color = style.accent)
        }
    }
}

@Composable
fun HomeEmpty(style: HomeStyle, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.home_empty_title),
            color = style.text,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.home_empty_body),
            color = style.textDim,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onRetry,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, style.accent),
        ) {
            Text(stringResource(R.string.home_try_again), color = style.accent)
        }
    }
}

/** The one "Connect YouTube Music" card, restyled to the black Home look. */
@Composable
fun HomeConnectCard(
    expired: Boolean,
    style: HomeStyle,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = style.surface,
        border = BorderStroke(1.dp, style.outline.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.ScreenPadding, vertical = 12.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(if (expired) R.string.home_reconnect_title else R.string.home_connect_title),
                color = style.text,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.home_connect_body),
                color = style.textDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = onConnect,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = style.accent,
                    contentColor = HomeCardPalette.Canvas,
                ),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Text(stringResource(if (expired) R.string.home_reconnect_action else R.string.home_connect_action))
            }
        }
    }
}

/** Local-library empty state (no tracks, or no folder chosen yet). */
@Composable
fun HomeEmptyLibrary(
    noFolder: Boolean,
    scanning: Boolean,
    style: HomeStyle,
    onAddMusic: () -> Unit,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Rounded.LibraryMusic,
            contentDescription = null,
            tint = style.accent,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(if (noFolder) R.string.home_no_folder_title else R.string.home_offline_title),
            color = style.text,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(if (noFolder) R.string.home_no_folder_body else R.string.home_offline_body),
            color = style.textDim,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (noFolder) onOpenSettings else onScan,
            enabled = !scanning,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = style.accent,
                contentColor = HomeCardPalette.Canvas,
            ),
        ) {
            Text(
                stringResource(
                    when {
                        noFolder -> R.string.home_choose_folder
                        scanning -> R.string.home_scanning
                        else -> R.string.home_scan
                    },
                ),
            )
        }
        OutlinedButton(
            onClick = onAddMusic,
            enabled = !scanning,
            shape = CircleShape,
            border = BorderStroke(1.dp, style.accent),
        ) {
            Text(stringResource(R.string.home_add_folder), color = style.accent)
        }
    }
}
