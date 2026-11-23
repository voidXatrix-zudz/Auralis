package com.auralis.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.R
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.glass.GlassMenu

/** Paged columns of four song rows (or four compact 16:9 video rows). */
@Composable
fun PagedSongColumns(
    row: HomeRowUi.PagedSongs,
    style: HomeStyle,
    favorites: Set<String>,
    currentId: String?,
    playlists: List<PlaylistEntity>,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: (() -> Unit)? = null,
) {
    val pages = remember(row.tracks) { row.tracks.chunked(HomeDimens.PageSize) }
    val state = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pageWidth = (maxWidth - HomeDimens.PagePeek).coerceAtLeast(240.dp)
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            contentPadding = PaddingValues(horizontal = HomeDimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(HomeDimens.PageGap),
        ) {
            items(pages.size) { index ->
                Column(Modifier.width(pageWidth)) {
                    pages[index].forEach { track ->
                        if (row.compact) {
                            CompactVideoRow(
                                track = track,
                                style = style,
                                favorites = favorites,
                                playlists = playlists,
                                onPlay = onPlay,
                                onFavorite = onFavorite,
                                onPlayNext = onPlayNext,
                                onAddToPlaylist = onAddToPlaylist,
                            )
                        } else {
                            SongRow(
                                track = track,
                                style = style,
                                favorite = track.id in favorites,
                                playing = track.id == currentId,
                                playlists = playlists,
                                onPlay = onPlay,
                                onFavorite = onFavorite,
                                onPlayNext = onPlayNext,
                                onAddToPlaylist = onAddToPlaylist,
                            )
                        }
                    }
                }
            }
            if (onLoadMore != null) {
                item(key = "more-page") {
                    Box(
                        Modifier
                            .width(pageWidth)
                            .height(HomeDimens.SongRowHeight * HomeDimens.PageSize.toFloat()),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.home_more), color = style.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    track: Track,
    style: HomeStyle,
    favorite: Boolean,
    playing: Boolean,
    playlists: List<PlaylistEntity>,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
) {
    var menu by remember(track.id) { mutableStateOf(false) }
    val title = track.title.ifBlank { track.displayName }
    val subtitle = remember(track) {
        listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(HomeDimens.SongRowHeight)
            .clickable(role = Role.Button, onClick = { onPlay(track) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            track = track,
            shape = RoundedCornerShape(HomeDimens.SongCoverRadius),
            modifier = Modifier.size(HomeDimens.SongCover),
        )
        Column(Modifier.weight(1f).padding(start = HomeDimens.SongRowGap, end = 8.dp)) {
            Text(
                title,
                color = if (playing) style.accent else style.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = HomeText.SongTitle,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = style.textDim,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.SongSubtitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.home_more_actions, title),
                    tint = style.textDim,
                    modifier = Modifier.size(24.dp),
                )
            }
            SongOptionsMenu(
                track = track,
                expanded = menu,
                onDismiss = { menu = false },
                favorite = favorite,
                playlists = playlists,
                onFavorite = onFavorite,
                onPlayNext = onPlayNext,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

@Composable
private fun CompactVideoRow(
    track: Track,
    style: HomeStyle,
    favorites: Set<String>,
    playlists: List<PlaylistEntity>,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
) {
    var menu by remember(track.id) { mutableStateOf(false) }
    val title = track.title.ifBlank { track.displayName }
    Row(
        Modifier
            .fillMaxWidth()
            .height(HomeDimens.SongRowHeight)
            .clickable(role = Role.Button, onClick = { onPlay(track) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            track = track,
            shape = RoundedCornerShape(HomeDimens.CompactRadius),
            modifier = Modifier
                .width(HomeDimens.CompactThumbWidth)
                .aspectRatio(16f / 9f),
        )
        Column(Modifier.weight(1f).padding(start = HomeDimens.SongRowGap, end = 4.dp)) {
            Text(
                title,
                color = style.text,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = HomeText.SongTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    color = style.textDim,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.SongSubtitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.home_more_actions, title),
                    tint = style.textDim,
                    modifier = Modifier.size(24.dp),
                )
            }
            SongOptionsMenu(
                track = track,
                expanded = menu,
                onDismiss = { menu = false },
                favorite = track.id in favorites,
                playlists = playlists,
                onFavorite = onFavorite,
                onPlayNext = onPlayNext,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

/** The app's existing song actions, shared by every Home row that has a three-dot button. */
@Composable
fun SongOptionsMenu(
    track: Track,
    expanded: Boolean,
    onDismiss: () -> Unit,
    favorite: Boolean,
    playlists: List<PlaylistEntity>,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
) {
    val downloads = LocalDownloadActions.current
    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_play_next)) },
            onClick = { onDismiss(); onPlayNext(track) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (favorite) R.string.home_unlike else R.string.home_like)) },
            onClick = { onDismiss(); onFavorite(track) },
        )
        if (track.isRemote) {
            val videoId = track.remoteId ?: track.id.removePrefix("yt:")
            if (videoId in downloads.downloadedIds) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_delete_download)) },
                    onClick = { onDismiss(); downloads.onDeleteDownload(track) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_download)) },
                    onClick = { onDismiss(); downloads.onDownload(track) },
                )
            }
        }
        playlists.forEach { list ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_add_to_playlist, list.name)) },
                onClick = { onDismiss(); onAddToPlaylist(list.id, track) },
            )
        }
    }
}
