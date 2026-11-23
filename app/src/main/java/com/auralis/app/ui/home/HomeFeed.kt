package com.auralis.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.auralis.app.R
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.data.youtube.YtPlaylist
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.screens.LibraryPlaylistRow

/** Draws one mapped Home section with the shared row components. */
@Composable
fun HomeRowBlock(
    row: HomeRowUi,
    style: HomeStyle,
    favorites: Set<String>,
    currentId: String?,
    playlists: List<PlaylistEntity>,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
    onOpenCard: (YtCard) -> Unit,
    onShowAll: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(row.title, style, onShowAll = if (row.showAll) onShowAll else null)
        when (row) {
            is HomeRowUi.TopPicks -> CardRow {
                items(row.picks, key = { it.key }) { pick ->
                    TopPickCard(pick, style, onClick = pick.onClick)
                }
                if (row.hasMore) {
                    item(key = "more") { HomeMoreCard(style, onClick = onLoadMore) }
                }
            }

            is HomeRowUi.PagedSongs -> PagedSongColumns(
                row = row,
                style = style,
                favorites = favorites,
                currentId = currentId,
                playlists = playlists,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onPlayNext = onPlayNext,
                onAddToPlaylist = onAddToPlaylist,
                onLoadMore = if (row.hasMore) onLoadMore else null,
            )

            is HomeRowUi.LibraryTiles -> CardRow {
                items(row.tiles, key = { it.key }) { tile -> LibraryTile(tile, style) }
            }

            is HomeRowUi.Videos -> CardRow {
                items(row.cards, key = { cardKey(it) }) { card ->
                    VideoCard(card, style, onClick = { onOpenCard(card) })
                }
                if (row.hasMore) {
                    item(key = "more") { HomeMoreCard(style, onClick = onLoadMore) }
                }
            }

            is HomeRowUi.SquareCards -> CardRow {
                items(row.cards, key = { cardKey(it) }) { card ->
                    SquareArtCard(card, style, onClick = { onOpenCard(card) })
                }
                if (row.hasMore) {
                    item(key = "more") { HomeMoreCard(style, onClick = onLoadMore) }
                }
            }

            is HomeRowUi.Artists -> CardRow {
                items(row.cards, key = { cardKey(it) }) { card ->
                    HomeArtistCard(card, style, onClick = { onOpenCard(card) })
                }
                if (row.hasMore) {
                    item(key = "more") { HomeMoreCard(style, onClick = onLoadMore) }
                }
            }
        }
    }
}

@Composable
private fun CardRow(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        contentPadding = PaddingValues(horizontal = HomeDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(HomeDimens.CardGap),
        content = content,
    )
}

private fun cardKey(card: YtCard): String = card.videoId ?: card.browseId ?: card.title

/**
 * Home for the local library (YouTube integration off). Shared by the Normal branch in
 * `HomeScreen` and the thin `GlassHomeScreen` wrapper; only [rememberHomeStyle] differs.
 */
@Composable
fun LocalHomeFeed(
    sources: List<MusicSource>,
    tracks: List<Track>,
    favorites: Set<String>,
    currentId: String?,
    libraryPlaylists: List<LibraryPlaylistRow>,
    scanning: Boolean,
    avatarUrl: String?,
    accountName: String?,
    playlists: List<PlaylistEntity>,
    onAddMusic: () -> Unit,
    onScan: () -> Unit,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenYouTubePlaylist: (YtPlaylist) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenExplore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val style = rememberHomeStyle()
    val context = LocalContext.current
    val you = stringResource(R.string.home_caption_you)
    val labels = LocalHomeLabels(
        topPicks = stringResource(R.string.home_top_picks_for_you),
        recents = stringResource(R.string.home_recents),
        fromLibrary = stringResource(R.string.home_from_library),
        quickPicks = stringResource(R.string.home_quick_picks),
    )

    val tiles = remember(tracks, favorites, libraryPlaylists, context, you) {
        buildList {
            val liked = tracks.filter { it.id in favorites }
            if (liked.isNotEmpty()) {
                add(
                    LibraryTileData(
                        key = "liked",
                        title = context.getString(R.string.home_liked_songs),
                        caption = context.getString(R.string.home_track_count, you, liked.size),
                        covers = liked.take(4),
                        coverUrl = null,
                        onClick = onOpenLibrary,
                    ),
                )
            }
            libraryPlaylists.forEach { row ->
                add(
                    LibraryTileData(
                        key = row.key,
                        title = row.name,
                        caption = context.getString(
                            R.string.home_track_count,
                            row.owner.ifBlank { you },
                            row.trackCount,
                        ),
                        covers = row.covers,
                        coverUrl = row.coverUrl,
                        onClick = {
                            val localId = row.localPlaylistId
                            val remote = row.ytPlaylist
                            when {
                                localId != null -> onOpenPlaylist(localId)
                                remote != null -> onOpenYouTubePlaylist(remote)
                            }
                        },
                    ),
                )
            }
        }
    }
    val rows = remember(tracks, tiles, labels) {
        buildLocalHomeRows(tracks, tiles, labels, onPlay)
    }

    val dockInset = LocalDockBottomInset.current
    val bottomPadding = if (style.glass) HomeDimens.ListBottomPadding + dockInset else HomeDimens.ListBottomPadding

    Box(modifier.fillMaxSize().background(HomeCardPalette.Canvas)) {
        LazyColumn(
            Modifier.fillMaxSize(),
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
            if (tracks.isEmpty() || rows.isEmpty()) {
                item(key = "empty") {
                    HomeEmptyLibrary(
                        noFolder = sources.isEmpty(),
                        scanning = scanning,
                        style = style,
                        onAddMusic = onAddMusic,
                        onScan = onScan,
                        onOpenSettings = onOpenSettings,
                    )
                }
            } else {
                rows.forEach { row ->
                    item(key = "row:${row.key}") {
                        HomeRowBlock(
                            row = row,
                            style = style,
                            favorites = favorites,
                            currentId = currentId,
                            playlists = playlists,
                            onPlay = onPlay,
                            onFavorite = onFavorite,
                            onPlayNext = onPlayNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onOpenCard = {},
                            onShowAll = onOpenExplore,
                            onLoadMore = {},
                        )
                    }
                }
            }
        }
    }
}