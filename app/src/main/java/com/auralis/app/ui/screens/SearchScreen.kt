package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassSearchScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track

/**
 * One Search screen, two sources. [Local] keeps the library matching rules; [YouTube] forwards
 * the typed query to the ViewModel (debounced) and renders streamed results in the same row.
 */
@Composable
fun SearchScreen(
    tracks: List<Track>,
    favorites: Set<String>,
    currentId: String?,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    playlists: List<PlaylistEntity>,
    onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier = Modifier,
    onQueue: (Track) -> Unit = {},
    sources: List<MusicSource> = emptyList(),
    onPlayNext: (Track) -> Unit = onQueue,
    youtubeResults: List<Track> = emptyList(),
    youtubeLoading: Boolean = false,
    youtubeError: String? = null,
    youtubeResolvingId: String? = null,
    onSearchYouTube: (String) -> Unit = {},
    onPlayYouTube: (Track) -> Unit = {},
    youtubeEnabled: Boolean = false,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassSearchScreen(
            tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, modifier,
            onQueue, sources, onPlayNext, youtubeResults, youtubeLoading, youtubeError,
            youtubeResolvingId, onSearchYouTube, onPlayYouTube, youtubeEnabled,
        )
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var youtube by remember { mutableStateOf(false) }
    // Part S: YouTube is the default source every time Search opens, when the integration is on.
    LaunchedEffect(youtubeEnabled) { youtube = youtubeEnabled }
    val normalizedQuery = query.trim()
    val sourceNames = remember(sources) { sources.associate { it.id to it.name } }
    // Filter only when the query or library actually changes.
    val found = remember(tracks, normalizedQuery, sourceNames) {
        if (normalizedQuery.isBlank()) emptyList() else tracks.filter { track ->
            track.title.contains(normalizedQuery, ignoreCase = true) ||
                track.displayName.contains(normalizedQuery, ignoreCase = true) ||
                track.artist.contains(normalizedQuery, ignoreCase = true) ||
                track.albumArtist.contains(normalizedQuery, ignoreCase = true) ||
                track.album.contains(normalizedQuery, ignoreCase = true) ||
                track.genres.any { it.contains(normalizedQuery, ignoreCase = true) } ||
                track.relativePath.contains(normalizedQuery, ignoreCase = true) ||
                sourceNames[track.sourceId]?.contains(normalizedQuery, ignoreCase = true) == true
        }
    }

    // The ViewModel owns the 300 ms debounce + flatMapLatest cancellation; forward each keystroke.
    LaunchedEffect(youtube, normalizedQuery) {
        if (youtube && normalizedQuery.isNotBlank()) {
            onSearchYouTube(normalizedQuery)
        }
    }

    Column(modifier.fillMaxSize().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (youtube) "Search YouTube Music" else "Songs, artists, albums, folders") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = if (youtube) "Search YouTube" else "Search library")
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (youtubeEnabled) {
                FilterChip(selected = youtube, onClick = { youtube = true }, label = { Text("YouTube") })
                FilterChip(selected = !youtube, onClick = { youtube = false }, label = { Text("Local") })
            } else {
                FilterChip(selected = true, onClick = {}, label = { Text("Local") })
            }
        }

        if (youtube) {
            when {
                normalizedQuery.isBlank() -> SearchHint(
                    title = "Search YouTube Music",
                    body = "Find songs by title or artist. Results stream over the internet.",
                )
                youtubeLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                youtubeError != null -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(youtubeError, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onSearchYouTube(normalizedQuery) }) { Text("Try again") }
                }
                youtubeResults.isEmpty() -> SearchHint(
                    title = "No YouTube results",
                    body = "Nothing matches “$normalizedQuery”. Try a different spelling.",
                )
                else -> TrackRows(
                    tracks = youtubeResults,
                    favorites = favorites,
                    currentId = currentId,
                    onPlay = onPlayYouTube,
                    onFavorite = {},
                    playlists = emptyList(),
                    onAddToPlaylist = { _, _ -> },
                    modifier = Modifier.weight(1f),
                    onQueue = {},
                    onPlayNext = {},
                    resolvingId = youtubeResolvingId,
                )
            }
        } else if (normalizedQuery.isNotBlank()) {
            TrackRows(
                tracks = found,
                favorites = favorites,
                currentId = currentId,
                onPlay = onPlay,
                onFavorite = onFavorite,
                playlists = playlists,
                onAddToPlaylist = onAddToPlaylist,
                modifier = Modifier.weight(1f),
                onQueue = onQueue,
                onPlayNext = onPlayNext,
            )
        }
    }
}

@Composable
private fun SearchHint(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
