package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassSearchScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track

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
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassSearchScreen(tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, modifier, onQueue, sources, onPlayNext)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
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

    Column(modifier.fillMaxSize().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums, folders") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search library") },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (normalizedQuery.isNotBlank()) {
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
