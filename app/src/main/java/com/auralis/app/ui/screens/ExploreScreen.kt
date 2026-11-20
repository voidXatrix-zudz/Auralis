package com.auralis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.ExploreUiState
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.explore.ExploreMood
import com.auralis.app.ui.explore.ExploreMoods
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * The Explore tab: a large title over a two-column grid of rounded mood tiles. Tapping a tile opens
 * the mood's streaming song page. Local library and downloads are never consulted here.
 */
@Composable
fun ExploreScreen(
    onOpenMood: (ExploreMood) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Text(
            "Explore",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
        Text(
            "Moods and moments, streamed from YouTube Music",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalDockBottomInset.current + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(ExploreMoods.all, key = { it.id }) { mood ->
                MoodTile(mood = mood, onClick = { onOpenMood(mood) })
            }
        }
    }
}

/** Coloured mood card: solid strip on the left, darker tinted body, name at the top-left. */
@Composable
private fun MoodTile(mood: ExploreMood, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    Box(
        modifier
            .height(104.dp)
            .clip(shape)
            .background(mood.shade)
            .border(1.dp, if (glass) GlassTokens.Cream14 else Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick),
    ) {
        // Darker tinted right side; the accent bleeds in softly from the strip.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(mood.accent.copy(alpha = 0.30f), mood.shade.copy(alpha = 0f)),
                    ),
                ),
        )
        mood.artUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.45f },
            )
            Box(Modifier.fillMaxSize().background(mood.shade.copy(alpha = 0.45f)))
        }
        // Solid colour strip on the left edge.
        Box(
            Modifier
                .fillMaxHeight()
                .width(8.dp)
                .background(mood.accent),
        )
        Text(
            text = mood.title,
            color = if (glass) GlassTokens.Cream else Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 14.dp, end = 12.dp),
        )
    }
}

/**
 * One mood's streaming songs. Reuses the shared song list (play, like, queue, add-to-playlist),
 * shows skeleton rows while loading and an inline retry when the network is unavailable.
 */
@Composable
fun ExploreMoodScreen(
    mood: ExploreMood,
    state: ExploreUiState,
    favorites: Set<String>,
    currentId: String?,
    playlists: List<PlaylistEntity>,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onAddToPlaylist: (Long, Track) -> Unit,
    onQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(mood.accent),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    mood.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        state.loading -> "Loading songs…"
                        state.error != null -> "Couldn't load"
                        else -> "${state.tracks.size} songs"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.loading -> SkeletonRows(Modifier.weight(1f))
            state.error != null -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.error,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) { Text("Try again") }
            }
            state.tracks.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No songs for this mood right now.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> TrackRows(
                tracks = state.tracks,
                favorites = favorites,
                currentId = currentId,
                onPlay = onPlay,
                onFavorite = onFavorite,
                playlists = playlists,
                onAddToPlaylist = onAddToPlaylist,
                // GlassTrackRows already reserves the floating dock inset inside its list.
                modifier = Modifier.weight(1f),
                onQueue = onQueue,
                onPlayNext = onPlayNext,
            )
        }
    }
}

/** Static skeleton rows shown while a mood's songs are still streaming in. */
@Composable
private fun SkeletonRows(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val shape = RoundedCornerShape(10.dp)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = LocalDockBottomInset.current + 24.dp),
    ) {
        items(8) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(shape)
                        .background(base),
                )
                Column(
                    Modifier.weight(1f).padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(base),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(base),
                    )
                }
            }
        }
    }
}