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
import com.auralis.app.ui.components.SectionTitle

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
    youtubeEnabled: Boolean = false,
    youtubeLiked: List<Track> = emptyList(),
    youtubePlaylists: List<com.auralis.app.data.youtube.YtPlaylist> = emptyList(),
    onPlayYouTubeTrack: (Track) -> Unit = onPlay,
    onOpenYouTubePlaylist: (com.auralis.app.data.youtube.YtPlaylist) -> Unit = {},
    onRefreshYouTubeLibrary: () -> Unit = {},
    downloads: List<Track> = emptyList(),
    onOpenHistory: () -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onImportSpotify: () -> Unit = {},
    spotifyImports: List<com.auralis.app.data.db.SpotifyImportEntity> = emptyList(),
    onOpenImport: (Long) -> Unit = {},
    onAutoFixTags: () -> Unit = {},
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassLibraryScreen(tracks, favorites, sources, playlists, currentId, onAddMusic, onScan, onPlay, onFavorite, onCreatePlaylist, onAddToPlaylist, modifier, onQueue, onPlayAlbum, onQueueAlbum, onQueuePlaylist, scanning, onPlayNext, onOpenSettings, youtubeEnabled, youtubeLiked, youtubePlaylists, onPlayYouTubeTrack, onOpenYouTubePlaylist, onRefreshYouTubeLibrary, downloads, onOpenHistory, onOpenPlaylist, onImportSpotify, spotifyImports, onOpenImport, onAutoFixTags)
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val tabs = remember(youtubeEnabled) { if (youtubeEnabled) LibraryTabs + listOf("Liked Songs", "Downloads") else LibraryTabs }
    LaunchedEffect(youtubeEnabled) { if (youtubeEnabled) onRefreshYouTubeLibrary() }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAutoFixTags) { Text("Auto-fix tags") }
            TextButton(onClick = onOpenHistory) { Text("History") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs.size) { i -> FilterChip(selected == i, { selected = i }, { Text(tabs[i]) }, shape = CircleShape) }
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
            5 -> {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(onClick = { creating = true }) { Text("Create playlist") }
                    TextButton(onClick = onImportSpotify) { Text("Import Spotify") }
                    playlists.forEach { playlist ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenPlaylist(playlist.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(playlist.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { onQueuePlaylist(playlist.id) }) { Icon(Icons.Rounded.QueueMusic, "Add ${playlist.name} to queue", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    if (youtubeEnabled && youtubePlaylists.isNotEmpty()) {
                        SectionTitle("YouTube Music playlists", Modifier.padding(top = 12.dp))
                        youtubePlaylists.forEach { playlist ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenYouTubePlaylist(playlist) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${playlist.title} · ${playlist.trackCount}",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (spotifyImports.isNotEmpty()) {
                        SectionTitle("Spotify imports", Modifier.padding(top = 12.dp))
                        spotifyImports.forEach { row ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenImport(row.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.fileName.removeSuffix(".csv"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (row.state == "RUNNING") "Matching ${row.processed}/${row.total}"
                                        else "${row.matched} matched · ${row.review} to review · ${row.failed} failed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            6 -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Liked Songs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onRefreshYouTubeLibrary) { Text("Refresh") }
                    }
                    if (youtubeLiked.isEmpty()) {
                        Text(
                            "No liked YouTube songs yet.",
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TrackRows(youtubeLiked, favorites, currentId, onPlayYouTubeTrack, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    }
                }
            }
            7 -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Text("Downloaded", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    if (downloads.isEmpty()) {
                        Text(
                            "No downloads yet. Use “Download” on a YouTube song.",
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TrackRows(downloads, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New playlist") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { onCreatePlaylist(name); name = ""; creating = false }) { Text("Create") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
}
