package com.auralis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.app.R
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.HomeUiState
import com.auralis.app.ui.components.ArtistAvatar
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.glass.GlassSheet
import com.auralis.app.ui.home.HomeCardPalette
import com.auralis.app.ui.home.HomeConnectCard
import com.auralis.app.ui.home.HomeEmpty
import com.auralis.app.ui.home.HomeErrorBanner
import com.auralis.app.ui.home.HomeDimens
import com.auralis.app.ui.home.HomeRowBlock
import com.auralis.app.ui.home.HomeSkeleton
import com.auralis.app.ui.home.HomeTopBar
import com.auralis.app.ui.home.homeRowPriority
import com.auralis.app.ui.home.orderHomeRows
import com.auralis.app.ui.home.rememberHomeStyle
import com.auralis.app.ui.home.toHomeRowUi

/**
 * AURALIS Home for the YouTube-powered feed, shown for both themes.
 *
 * It only collects [HomeUiState], maps each row to a shared `HomeRowUi`, and draws the black
 * canvas. Every card shape, the top bar and the states live in `ui/home`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeHomeScreen(
    state: HomeUiState,
    accountName: String?,
    avatarUrl: String?,
    signedIn: Boolean,
    favorites: Set<String>,
    currentId: String?,
    playlists: List<PlaylistEntity>,
    onAddToPlaylist: (Long, Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    /** True when a stored session was rejected, so the Connect card says "Reconnect". */
    expired: Boolean = false,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onOpenCard: (YtCard) -> Unit,
    onLike: (Track) -> Unit,
    onLoadMore: (String) -> Unit,
    onCloseCollection: () -> Unit,
    onSignIn: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-load on first render; loadHome() is a no-op when content for this category is cached.
    LaunchedEffect(Unit) { onOpen() }

    val style = rememberHomeStyle()
    val rows = remember(state.rows, onOpenCard) {
        orderHomeRows(state.rows.values.mapNotNull { it.toHomeRowUi(onOpenCard) })
    }
    val error = state.homeError
    val loading = state.rows.values.any { it.loading }
    // The Connect card belongs after Recents when that row exists, otherwise at the top.
    val connectAfter = remember(rows) {
        val recents = rows.indexOfLast { homeRowPriority(it.title) == 1 }
        if (recents >= 0) recents else if (rows.isNotEmpty()) 0 else -1
    }
    val bottomPadding = rememberHomeBottomPadding(style.glass)
    // The root paints the one black canvas in Glass; Normal Home keeps its own black field.
    val backdrop = if (style.glass) Modifier else Modifier.background(HomeCardPalette.Canvas)

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().then(backdrop),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().then(backdrop),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(key = "topbar") {
                HomeTopBar(
                    avatarUrl = avatarUrl,
                    accountName = accountName,
                    style = style,
                    onAvatarClick = onOpenSettings,
                )
            }
            if (error != null) {
                item(key = "error") {
                    HomeErrorBanner(error, state.refreshing, style, onRetry = onRefresh)
                }
            }
            if (!signedIn && rows.isEmpty()) {
                item(key = "connect") { HomeConnectCard(expired, style, onConnect = onSignIn) }
            }
            rows.forEachIndexed { index, row ->
                item(key = "row:${row.key}") {
                    HomeRowBlock(
                        row = row,
                        style = style,
                        favorites = favorites,
                        currentId = currentId,
                        playlists = playlists,
                        onPlay = onPlayTrack,
                        onFavorite = onLike,
                        onPlayNext = onPlayNext,
                        onAddToPlaylist = onAddToPlaylist,
                        onOpenCard = onOpenCard,
                        onShowAll = onOpenExplore,
                        onLoadMore = { onLoadMore(row.key) },
                    )
                }
                if (!signedIn && index == connectAfter) {
                    item(key = "connect") { HomeConnectCard(expired, style, onConnect = onSignIn) }
                }
            }
            if (rows.isEmpty() && error == null) {
                when {
                    state.refreshing || loading -> item(key = "skeleton") { HomeSkeleton(style) }
                    signedIn -> item(key = "empty") { HomeEmpty(style, onRetry = onRefresh) }
                }
            }
        }
    }

    val collectionTitle = state.collectionTitle
    if (collectionTitle != null) {
        CollectionSheet(
            title = collectionTitle,
            artistBrowseId = state.collectionArtistBrowseId,
            artistImageUrl = state.collectionArtistImageUrl,
            tracks = state.collectionTracks,
            loading = state.collectionLoading,
            onPlayTrack = onPlayTrack,
            onLike = onLike,
            onDismiss = onCloseCollection,
        )
    }
}

@Composable
private fun rememberHomeBottomPadding(glass: Boolean): Dp {
    val inset = LocalDockBottomInset.current
    return if (glass) HomeDimens.ListBottomPadding + inset else HomeDimens.ListBottomPadding
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionSheet(
    title: String,
    tracks: List<Track>,
    loading: Boolean,
    onPlayTrack: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onDismiss: () -> Unit,
    artistBrowseId: String? = null,
    artistImageUrl: String? = null,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    GlassSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (artistBrowseId != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistAvatar(name = title, browseId = artistBrowseId, imageUrl = artistImageUrl, size = 56.dp)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        when {
            loading && tracks.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            tracks.isEmpty() -> Text(
                stringResource(R.string.home_no_songs),
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(tracks, key = { it.id }) { track ->
                    CollectionRow(track, onPlayTrack, onLike)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CollectionRow(track: Track, onPlay: (Track) -> Unit, onLike: (Track) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay(track) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(track = track, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onLike(track) }) {
            Icon(Icons.Rounded.FavoriteBorder, contentDescription = stringResource(R.string.home_like_song, track.title))
        }
        IconButton(onClick = { onPlay(track) }) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.home_play_song, track.title))
        }
    }
}