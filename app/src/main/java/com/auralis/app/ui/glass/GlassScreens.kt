package com.auralis.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.theme.GlassTokens

private val GlassLibraryTabs = listOf("Songs", "Albums", "Artists", "Genres", "Favorites", "Playlists")

private val GlassAlbumTrackOrder = compareBy<Track> { it.discNumber ?: 0 }
    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
    .thenBy { it.title }

@Composable
fun GlassHomeScreen(
    sources: List<MusicSource>, tracks: List<Track>, favorites: Set<String>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    playlists: List<PlaylistEntity>, onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier, onQueue: (Track) -> Unit, scanning: Boolean, onPlayNext: (Track) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (tracks.isEmpty()) "Welcome" else "Good evening",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.Silver,
                )
                Text(
                    if (tracks.isEmpty()) "Your offline library" else "${tracks.size} songs · ${sources.size} folders",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTokens.SilverMuted,
                )
            }
            GlassIconButton(
                Icons.Rounded.Refresh,
                if (scanning) "Scanning" else "Scan library",
                onScan,
                enabled = !scanning,
            )
            GlassIconButton(
                Icons.Rounded.Settings,
                "Settings",
                onOpenSettings,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (tracks.isEmpty()) {
            GlassEmptyState("Your music, offline", "Scan songs on this device or add a folder.", Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassPrimaryButton(if (scanning) "Scanning..." else "Scan device", onScan, enabled = !scanning)
                    GlassGhostButton("Add folder", onAddMusic, icon = Icons.Rounded.FolderOpen, enabled = !scanning)
                }
            }
        } else {
            val recent = remember(tracks) { tracks.sortedByDescending { it.addedAt }.take(12) }
            val liked = remember(tracks, favorites) { tracks.filter { it.id in favorites }.take(12) }
            val albums = remember(tracks) {
                tracks.groupBy { it.album.ifBlank { "Unknown album" } }
                    .entries
                    .sortedBy { it.key.lowercase() }
                    .take(12)
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp + LocalDockBottomInset.current),
            ) {
                item { GlassHeroCarousel(recent, currentId, onPlay, Modifier.padding(top = 12.dp)) }
                if (sources.isNotEmpty()) item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sources, key = { it.id }) { source -> GlassFolderChip(source.name) }
                    }
                }
                item { HomeSectionHeader("Recently added") }
                item { GlassArtworkRow(recent, currentId, onPlay) }
                if (liked.isNotEmpty()) {
                    item { HomeSectionHeader("Favorites") }
                    item { GlassArtworkRow(liked, currentId, onPlay) }
                }
                if (albums.isNotEmpty()) {
                    item { HomeSectionHeader("Albums") }
                    item { GlassAlbumRow(albums, onPlay) }
                }
                item { HomeSectionHeader("All songs · ${tracks.size}") }
                items(tracks, key = { it.id }) { track ->
                    GlassTrackRow(
                        track, track.id == currentId, track.id in favorites, onPlay, onFavorite, onQueue, onPlayNext, playlists, onAddToPlaylist,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Large rounded hero cards with artwork, gradient scrim and a floating play affordance. */
@Composable
private fun HomeSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = GlassTokens.Silver,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
    )
}

@Composable
private fun GlassHeroCarousel(tracks: List<Track>, currentId: String?, onPlay: (Track) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            Box(
                Modifier
                    .width(276.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onPlay(track) },
            ) {
                GlassArtwork(track, Modifier.matchParentSize(), 24.dp)
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.82f)),
                            ),
                        ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        if (track.id == currentId) "NOW PLAYING" else "FEATURED",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = GlassTokens.Violet,
                    )
                    Text(
                        track.title.ifBlank { track.displayName },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        track.artist.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTokens.Silver.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GlassTokens.Obsidian.copy(alpha = 0.42f))
                        .border(1.dp, GlassTokens.BorderBrush, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${track.title}", tint = GlassTokens.Silver, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Rounded artwork cards with a title and a grey subtitle. */
@Composable
private fun GlassArtworkRow(tracks: List<Track>, currentId: String?, onPlay: (Track) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            Column(Modifier.width(132.dp).clickable { onPlay(track) }) {
                GlassArtwork(track, Modifier.size(132.dp), 20.dp)
                Text(
                    track.title.ifBlank { track.displayName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (track.id == currentId) GlassTokens.Violet else GlassTokens.Silver,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    track.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTokens.SilverMuted,
                )
            }
        }
    }
}

@Composable
private fun GlassAlbumRow(albums: List<Map.Entry<String, List<Track>>>, onPlay: (Track) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.key }) { entry ->
            val first = entry.value.firstOrNull() ?: return@items
            Column(Modifier.width(132.dp).clickable { onPlay(first) }) {
                GlassArtwork(first, Modifier.size(132.dp), 20.dp)
                Text(
                    entry.key,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassTokens.Silver,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "${entry.value.size} songs",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTokens.SilverMuted,
                )
            }
        }
    }
}

@Composable
fun GlassSearchScreen(
    tracks: List<Track>, favorites: Set<String>, currentId: String?,
    onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    playlists: List<PlaylistEntity>, onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier, onQueue: (Track) -> Unit, sources: List<MusicSource>, onPlayNext: (Track) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val sourceNames = remember(sources) { sources.associate { it.id to it.name } }
    // Same matching rules as the Normal search screen; recomputed only for a new query/library.
    val found = remember(tracks, q, sourceNames) {
        if (q.isBlank()) emptyList() else tracks.filter { track ->
            track.title.contains(q, ignoreCase = true) ||
                track.displayName.contains(q, ignoreCase = true) ||
                track.artist.contains(q, ignoreCase = true) ||
                track.albumArtist.contains(q, ignoreCase = true) ||
                track.album.contains(q, ignoreCase = true) ||
                track.genres.any { it.contains(q, ignoreCase = true) } ||
                track.relativePath.contains(q, ignoreCase = true) ||
                sourceNames[track.sourceId]?.contains(q, ignoreCase = true) == true
        }
    }
    Column(modifier.fillMaxSize()) {
        GlassHeader("Search", Modifier.padding(horizontal = 16.dp))
        GlassSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Songs, artists, albums, folders",
            leading = { Icon(Icons.Rounded.Search, contentDescription = "Search library") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        when {
            q.isBlank() -> GlassEmptyState("Find anything", "Search by title, artist, album, genre or folder.", Modifier.weight(1f))
            found.isEmpty() -> GlassEmptyState("No results", "Nothing matches “$q”.", Modifier.weight(1f))
            else -> {
                Text("${found.size} results", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassTrackRows(found, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
            }
        }
    }
}

@Composable
fun GlassLibraryScreen(
    tracks: List<Track>, favorites: Set<String>, sources: List<MusicSource>, playlists: List<PlaylistEntity>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit, onAddToPlaylist: (Long, Track) -> Unit, modifier: Modifier,
    onQueue: (Track) -> Unit, onPlayAlbum: (List<Track>) -> Unit, onQueueAlbum: (List<Track>) -> Unit,
    onQueuePlaylist: (Long) -> Unit, scanning: Boolean, onPlayNext: (Track) -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        GlassHeader("Library", Modifier.padding(horizontal = 16.dp), "${tracks.size} songs")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(GlassLibraryTabs.size) { i -> GlassChip(GlassLibraryTabs[i], selected == i, { selected = i }) }
        }
        Spacer(Modifier.height(8.dp))
        when (selected) {
            0 -> if (tracks.isEmpty()) {
                GlassEmptyState("Your music, offline", "Scan songs on this device or add a folder.", Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassPrimaryButton(if (scanning) "Scanning..." else "Scan device", onScan, enabled = !scanning)
                        GlassGhostButton("Add folder", onAddMusic, icon = Icons.Rounded.FolderOpen, enabled = !scanning)
                    }
                }
            } else GlassTrackRows(tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
            1 -> {
                val albums = remember(tracks) {
                    tracks.groupBy { it.album.ifBlank { "Unknown album" } }
                        .mapValues { (_, albumTracks) -> albumTracks.sortedWith(GlassAlbumTrackOrder) }
                        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(albums.entries.toList(), key = { it.key }) { entry ->
                        val album = entry.key
                        val ordered = entry.value
                        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = { onPlayAlbum(ordered) }) {
                            Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassArtwork(ordered.first(), Modifier.size(64.dp), 16.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                                    Text(album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${ordered.first().albumArtist} · ${ordered.size} songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                GlassIconAction(Icons.Rounded.QueueMusic, "Add album to queue", { onQueueAlbum(ordered) }, tint = GlassTokens.Violet)
                            }
                        }
                    }
                }
            }
            2, 3 -> {
                val values = remember(tracks, selected) {
                    if (selected == 2) tracks.map { it.artist }.distinct().sorted() else tracks.flatMap { it.genres }.distinct().sorted()
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(values) { value ->
                        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd)) {
                            Text(value.ifBlank { "Unknown" }, Modifier.padding(horizontal = 18.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            4 -> {
                val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
                GlassTrackRows(favoriteTracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPrimaryButton("Create playlist", { creating = true }, icon = Icons.Rounded.Add)
                    playlists.forEach { playlist ->
                        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = { onQueuePlaylist(playlist.id) }) {
                            Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(playlist.name, Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                GlassIconAction(Icons.Rounded.QueueMusic, "Add ${playlist.name} to queue", { onQueuePlaylist(playlist.id) }, tint = GlassTokens.Violet)
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { GlassSearchField(name, { name = it }, "Name") },
        confirmButton = { TextButton(onClick = { onCreatePlaylist(name); name = ""; creating = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
}
