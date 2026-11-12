package com.auralis.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.theme.GlassTokens

@Composable
fun GlassTrackRows(
    tracks: List<Track>,
    favorites: Set<String>,
    currentId: String?,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    playlists: List<PlaylistEntity>,
    onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier,
    onQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    resolvingId: String? = null,
    downloadedIds: Set<String> = emptySet(),
    onDownload: (Track) -> Unit = {},
    onDeleteDownload: (Track) -> Unit = {},
) {
    if (tracks.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
            Text("No matching songs in your library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            GlassTrackRow(track, track.id == currentId, track.id in favorites, onPlay, onFavorite, onQueue, onPlayNext, playlists, onAddToPlaylist, resolving = track.id == resolvingId, downloaded = (track.remoteId ?: track.id.removePrefix("yt:")) in LocalDownloadActions.current.downloadedIds)
        }
    }
}

@Composable
fun GlassTrackRow(
    track: Track,
    current: Boolean,
    favorite: Boolean,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    playlists: List<PlaylistEntity>,
    onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier = Modifier,
    resolving: Boolean = false,
    downloaded: Boolean = false,
    onDownload: (Track) -> Unit = {},
    onDeleteDownload: (Track) -> Unit = {},
) {
    var menu by remember(track.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val dl = LocalDownloadActions.current
    GlassPanel(modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = { onPlay(track) }) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassArtwork(track, Modifier.size(52.dp), 14.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(
                    track.title.ifBlank { track.displayName },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (current) GlassTokens.Violet else colors.onSurface,
                )
                Text(
                    track.artist.ifBlank { "Unknown artist" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (downloaded) {
                Text("Downloaded", style = MaterialTheme.typography.labelSmall, color = GlassTokens.Violet, modifier = Modifier.padding(end = 4.dp))
            }
            if (track.isRemote && resolving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                GlassIconAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favorite) "Remove favorite" else "Add favorite",
                    { onFavorite(track) },
                    tint = if (favorite) GlassTokens.Violet else colors.onSurfaceVariant,
                )
                Box {
                    GlassIconAction(Icons.Rounded.MoreVert, "More actions for ${track.title}", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Play next") }, onClick = { onPlayNext(track); menu = false })
                        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onQueue(track); menu = false })
                        DropdownMenuItem(text = { Text(if (favorite) "Unlike" else "Like") }, onClick = { onFavorite(track); menu = false })
                        if (track.isRemote) {
                            if (downloaded) {
                                DropdownMenuItem(text = { Text("Delete download") }, onClick = { dl.onDeleteDownload(track); menu = false })
                            } else {
                                DropdownMenuItem(text = { Text("Download") }, onClick = { dl.onDownload(track); menu = false })
                            }
                        }
                        playlists.forEach { list ->
                            DropdownMenuItem(text = { Text("Add to ${list.name}") }, onClick = { onAddToPlaylist(list.id, track); menu = false })
                        }
                    }
                }
            }
        }
    }
}

/** Horizontal artwork carousel used on Home. */
@Composable
fun GlassCarousel(tracks: List<Track>, currentId: String?, onPlay: (Track) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(tracks, key = { it.id }) { track ->
            Column(Modifier.width(136.dp).clickable { onPlay(track) }) {
                GlassArtwork(track, Modifier.size(136.dp), 26.dp)
                Text(
                    track.title.ifBlank { track.displayName },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (track.id == currentId) GlassTokens.Violet else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    track.artist.ifBlank { "Unknown artist" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun GlassFolderChip(name: String) {
    GlassPanel(shape = CircleShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderOpen, null, tint = GlassTokens.Violet, modifier = Modifier.size(18.dp))
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
