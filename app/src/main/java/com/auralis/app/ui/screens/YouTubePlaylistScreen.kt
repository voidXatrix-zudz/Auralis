package com.auralis.app.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.GlassYouTubePlaylistScreen
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * A YouTube Music playlist/album page. Opening a card lands here; nothing plays until the user taps
 * a song, Play or Shuffle. The song list is the songs-only filtered list, so the count is honest.
 */
@Composable
fun YouTubePlaylistScreen(
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
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassYouTubePlaylistScreen(
            title, owner, coverUrl, tracks, loading, error, loaded, currentId, favorites,
            onPlay, onShuffle, onPlayNext, onQueue, onFavorite, onRetry, modifier,
        )
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(
                    track = null,
                    artUri = coverUrl ?: tracks.firstOrNull()?.artworkUrl,
                    modifier = Modifier.size(152.dp),
                    shape = RoundedCornerShape(18.dp),
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        title.ifBlank { "Playlist" },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(owner.ifBlank { null }, if (loaded) "${tracks.size} songs" else null)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { tracks.firstOrNull()?.let(onPlay) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            // Skeleton only while the first page is in flight and there is nothing cached to show.
            loading && tracks.isEmpty() -> items(10) { PlaylistSkeletonRow() }
            error != null && tracks.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
            loaded && tracks.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("No songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> itemsIndexed(tracks, key = { _, track -> track.id }) { _, track ->
                YouTubePlaylistRow(
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
private fun YouTubePlaylistRow(
    track: Track,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(track, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(
                track.title.ifBlank { track.displayName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favorite) "Remove from liked songs" else "Like",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** A shimmer-free placeholder row: enough shape to read as "songs are loading". */
@Composable
private fun PlaylistSkeletonRow() {
    val block = Modifier.background(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        RoundedCornerShape(6.dp),
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.62f).height(12.dp).then(block))
            Box(Modifier.fillMaxWidth(0.38f).height(10.dp).then(block))
        }
    }
}