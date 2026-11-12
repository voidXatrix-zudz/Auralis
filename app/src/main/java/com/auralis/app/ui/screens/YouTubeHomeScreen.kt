package com.auralis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.HomeCategory
import com.auralis.app.ui.HomeRow
import com.auralis.app.ui.HomeUiState
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * AURALIS Home: a fully functional, YouTube-powered music discovery feed.
 *
 * Sections (For You, in order): header · category chips · Quick Picks · Trending in India · Made For
 * You · Global Top Hits · Continue Listening · New Releases · Popular Artists · Daily Mix · Because
 * You Listened · Discover Weekly. Other categories show a curated subset.
 *
 * Every row loads independently through the same keyless InnerTube repository the rest of the app
 * uses ([com.auralis.app.ui.AppViewModel.loadHome]); recommendations come from the user's own
 * AURALIS activity (play history, likes, library, searches), never a private YouTube endpoint.
 *
 * Shown for both themes before either theme branch, so Normal and Glassmorphic render the same feed
 * and only the container treatment differs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeHomeScreen(
    state: HomeUiState,
    accountName: String?,
    avatarUrl: String?,
    signedIn: Boolean,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onSelectCategory: (HomeCategory) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onOpenCard: (YtCard) -> Unit,
    onLike: (Track) -> Unit,
    onLoadMore: (String) -> Unit,
    onCloseCollection: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-load on first render; loadHome() is a no-op when content for this category is cached.
    LaunchedEffect(Unit) { onOpen() }

    // Hide rows that finished with nothing (e.g. "Because You Listened" before any history) but keep
    // loading placeholders and failed rows so the page never looks broken.
    val rows = state.rows.values.filter {
        it.loading || it.error != null || it.tracks.isNotEmpty() || it.cards.isNotEmpty()
    }
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (glass) 16.dp else 0.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!glass) {
                item(key = "header") {
                    HomeHeader(
                        signedIn = signedIn,
                        accountName = accountName,
                        avatarUrl = avatarUrl,
                        refreshing = state.refreshing,
                        onRefresh = onRefresh,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut,
                        onSwitchAccount = onSwitchAccount,
                    )
                }
            }
            item(key = "categories") {
                CategoryChips(state.category, onSelectCategory)
            }
            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    item(key = "row:${row.id}") {
                        HomeSection(
                            row = row,
                            glass = glass,
                            onPlayTrack = onPlayTrack,
                            onOpenCard = onOpenCard,
                            onLike = onLike,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            } else if (state.refreshing || state.rows.values.any { it.loading }) {
                item(key = "skeleton") { HomeSkeleton() }
            } else {
                item(key = "empty") { HomeEmpty(onRefresh) }
            }
        }
    }

    val collectionTitle = state.collectionTitle
    if (collectionTitle != null) {
        CollectionSheet(
            title = collectionTitle,
            tracks = state.collectionTracks,
            loading = state.collectionLoading,
            onPlayTrack = onPlayTrack,
            onLike = onLike,
            onDismiss = onCloseCollection,
        )
    }
}

@Composable
private fun HomeHeader(
    signedIn: Boolean,
    accountName: String?,
    avatarUrl: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "AURALIS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (signedIn) "Good to see you${accountName?.let { ", $it" } ?: ""}"
                    else "Discover YouTube music",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                Icon(Icons.Rounded.Refresh, "Refresh")
            }
            if (signedIn) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        AccountAvatar(accountName, avatarUrl)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Text(
                            accountName ?: "Signed in",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Switch account") },
                            onClick = { menu = false; onSwitchAccount() },
                        )
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menu = false; onSignOut() },
                        )
                    }
                }
            } else {
                TextButton(onClick = onSignIn) { Text("Sign in") }
            }
        }
    }
}

@Composable
private fun AccountAvatar(accountName: String?, avatarUrl: String?) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = accountName ?: "Account",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Person, "Account") }
    }
}

@Composable
private fun CategoryChips(selected: HomeCategory, onSelect: (HomeCategory) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeCategory.entries) { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun HomeSection(
    row: HomeRow,
    glass: Boolean,
    onPlayTrack: (Track) -> Unit,
    onOpenCard: (YtCard) -> Unit,
    onLike: (Track) -> Unit,
    onLoadMore: (String) -> Unit,
) {
    Column {
        SectionHeader(row.title, row.subtitle)
        val empty = row.tracks.isEmpty() && row.cards.isEmpty()
        when {
            row.loading && empty -> RowPlaceholder(glass)
            row.error != null && empty -> RowMessage("Couldn't load this row")
            empty -> RowMessage("Nothing here yet")
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (row.cards.isNotEmpty()) {
                    items(row.cards, key = { it.browseId ?: it.videoId ?: it.title }) { card ->
                        ArtistCard(card, onOpenCard)
                    }
                } else {
                    items(row.tracks, key = { it.id }) { track ->
                        TrackCard(track, glass, onPlayTrack, onLike)
                    }
                }
                if (row.hasMore) {
                    item(key = "more") { LoadMoreCard(onClick = { onLoadMore(row.id) }) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 22.dp, bottom = if (subtitle == null) 12.dp else 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/** Big discovery card: large rounded artwork, title and artist, tap-to-play, heart to like. */
@Composable
private fun TrackCard(
    track: Track,
    glass: Boolean,
    onPlay: (Track) -> Unit,
    onLike: (Track) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(156.dp)
            .clip(shape)
            .clickable { onPlay(track) },
    ) {
        Box(Modifier.fillMaxWidth().height(156.dp)) {
            ArtworkImage(
                track = track,
                shape = shape,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (glass) 0.55f else 0.75f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                IconButton(onClick = { onLike(track) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like ${track.title}",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Text(
            track.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
        )
    }
}

/** Circular artist card; tapping opens the artist's songs in a sheet. */
@Composable
private fun ArtistCard(card: YtCard, onOpen: (YtCard) -> Unit) {
    Column(
        Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen(card) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            artUri = card.thumbnailUrl,
            shape = CircleShape,
            modifier = Modifier.size(112.dp),
        )
        Text(
            card.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            card.subtitle.ifBlank { "Artist" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadMoreCard(onClick: () -> Unit) {
    Column(
        Modifier
            .width(156.dp)
            .height(156.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("More", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowMessage(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Responsive loading placeholder shown while a row (or the whole feed) is fetching. */
@Composable
private fun RowPlaceholder(glass: Boolean) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(4) {
            Column(Modifier.width(156.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(156.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (glass) 0.4f else 1f)),
                )
                Spacer(Modifier.height(8.dp))
                PlaceholderBar(0.8f)
                Spacer(Modifier.height(6.dp))
                PlaceholderBar(0.5f)
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column {
        repeat(3) {
            SectionHeader("Loading", null)
            RowPlaceholder(glass = false)
        }
    }
}

@Composable
private fun HomeEmpty(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Nothing to play yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "We couldn't find music for this filter. Check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun PlaceholderBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        when {
            loading && tracks.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            tracks.isEmpty() -> Text(
                "No songs found.",
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
            Icon(Icons.Rounded.FavoriteBorder, contentDescription = "Like ${track.title}")
        }
        IconButton(onClick = { onPlay(track) }) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title}")
        }
    }
}
