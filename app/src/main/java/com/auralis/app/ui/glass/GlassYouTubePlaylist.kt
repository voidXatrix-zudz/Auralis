package com.auralis.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.core.formatDuration
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.theme.GlassTokens

/** Glassmorphic YouTube playlist/album detail page. */
@Composable
fun GlassYouTubePlaylistScreen(
    title: String,
    owner: String,
    coverUrl: String?,
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    loaded: Boolean,
    currentId: String?,
    favorites: Set<String>,
    onPlay: (Track) -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onQueue: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp + LocalDockBottomInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                GlassArtwork(
                    track = null,
                    modifier = Modifier.size(152.dp),
                    radius = 20.dp,
                    artUri = coverUrl ?: tracks.firstOrNull()?.artworkUrl,
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    GlassHeader(title.ifBlank { "Playlist" })
                    Text(
                        listOfNotNull(owner.ifBlank { null }, if (loaded) "${tracks.size} songs" else null)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassTokens.SilverMuted,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassPrimaryButton(
                    "Play",
                    { tracks.firstOrNull()?.let(onPlay) },
                    icon = Icons.Rounded.PlayArrow,
                    enabled = tracks.isNotEmpty(),
                )
                GlassGhostButton(
                    "Shuffle",
                    onShuffle,
                    icon = Icons.Rounded.Shuffle,
                    enabled = tracks.isNotEmpty(),
                )
            }
        }
        when {
            loading && tracks.isEmpty() -> items(10) { GlassPlaylistSkeletonRow() }
            error != null && tracks.isEmpty() -> item {
                GlassEmptyState(error, "Check your connection and try again.", Modifier.fillMaxWidth()) {
                    GlassGhostButton("Retry", onRetry)
                }
            }
            loaded && tracks.isEmpty() -> item {
                GlassEmptyState("No songs", "This playlist has no songs to play.", Modifier.fillMaxWidth())
            }
            else -> itemsIndexed(tracks, key = { _, track -> track.id }) { _, track ->
                GlassPlaylistRow(
                    track = track,
                    playing = track.id == currentId,
                    favorite = track.id in favorites,
                    onPlay = { onPlay(track) },
                    onFavorite = { onFavorite(track) },
                    onPlayNext = { onPlayNext(track) },
                    onQueue = { onQueue(track) },
                )
            }
        }
    }
}

@Composable
private fun GlassPlaylistRow(
    track: Track,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    GlassPanel(
        Modifier.fillMaxWidth(),
        RoundedCornerShape(GlassTokens.RadiusMd),
        onClick = onPlay,
    ) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassArtwork(track, Modifier.size(48.dp), 14.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(
                    track.title.ifBlank { track.displayName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (playing) GlassTokens.Violet else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    track.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (track.durationMs > 0L) {
                Text(
                    formatDuration(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.SilverMuted,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favorite) "Remove from liked songs" else "Like",
                    tint = if (favorite) GlassTokens.Violet else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; onQueue() })
                }
            }
        }
    }
}

@Composable
private fun GlassPlaylistSkeletonRow() {
    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth(0.62f).height(12.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                )
                Box(
                    Modifier.fillMaxWidth(0.38f).height(10.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}