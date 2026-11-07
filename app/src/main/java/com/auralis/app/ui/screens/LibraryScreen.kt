package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassLibraryScreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage

private val LibraryTabs = listOf("Songs", "Albums", "Artists", "Genres", "Favorites", "Playlists")

private val AlbumTrackOrder = compareBy<Track> { it.discNumber ?: 0 }
    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
    .thenBy { it.title }

@Composable
fun LibraryScreen(
    tracks: List<Track>, favorites: Set<String>, sources: List<MusicSource>, playlists: List<PlaylistEntity>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit, onAddToPlaylist: (Long, Track) -> Unit, modifier: Modifier = Modifier,
    onQueue: (Track) -> Unit = {}, onPlayAlbum: (List<Track>) -> Unit = {}, onQueueAlbum: (List<Track>) -> Unit = {}, onQueuePlaylist: (Long) -> Unit = {}, scanning: Boolean = false, onPlayNext: (Track) -> Unit = onQueue,
    onOpenSettings: () -> Unit = {},
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassLibraryScreen(tracks, favorites, sources, playlists, currentId, onAddMusic, onScan, onPlay, onFavorite, onCreatePlaylist, onAddToPlaylist, modifier, onQueue, onPlayAlbum, onQueueAlbum, onQueuePlaylist, scanning, onPlayNext, onOpenSettings)
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryTabs.size) { i -> FilterChip(selected == i, { selected = i }, { Text(LibraryTabs[i]) }, shape = CircleShape) }
        }
        when (selected) {
            0 -> if (tracks.isEmpty()) EmptyLibraryHero(onAddMusic, onScan, Modifier.weight(1f), scanning, onOpenSettings, sources.isEmpty()) else TrackRows(tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
            1 -> {
                val albums = remember(tracks) {
                    tracks.groupBy { it.album.ifBlank { "Unknown album" } }
                        .mapValues { (_, albumTracks) -> albumTracks.sortedWith(AlbumTrackOrder) }
                        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    items(albums.entries.toList(), key = { it.key }) { entry ->
                        val album = entry.key
                        val ordered = entry.value
                        Row(Modifier.fillMaxWidth().clickable { onPlayAlbum(ordered) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(ordered.first(), Modifier.size(56.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                                Text(album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text("${ordered.first().albumArtist} · ${ordered.size} songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onQueueAlbum(ordered) }) { Icon(Icons.Rounded.QueueMusic, "Add album to queue", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
            2, 3 -> {
                val values = remember(tracks, selected) {
                    if (selected == 2) tracks.map { it.artist }.distinct().sorted() else tracks.flatMap { it.genres }.distinct().sorted()
                }
                LazyColumn(contentPadding = PaddingValues(16.dp)) { items(values) { Text(it, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) } }
            }
            4 -> {
                val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
                TrackRows(favoriteTracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(onClick = { creating = true }) { Text("Create playlist") }
                    playlists.forEach { playlist ->
                        Row(Modifier.fillMaxWidth().clickable { onQueuePlaylist(playlist.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(playlist.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { onQueuePlaylist(playlist.id) }) { Icon(Icons.Rounded.QueueMusic, "Add ${playlist.name} to queue", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New playlist") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { onCreatePlaylist(name); name = ""; creating = false }) { Text("Create") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
}
