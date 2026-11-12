package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassLibraryScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.PlaylistYtEntryEntity
import com.auralis.app.data.db.SpotifyImportEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.youtube.YtPlaylist
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.ArtistAvatar

/** The three persisted library orderings (Part R). */
enum class LibrarySortOption(val id: String, val label: String) {
    CUSTOM("CUSTOM", "Custom order"),
    RECENTS("RECENTS", "Recents"),
    AZ("AZ", "A-Z");

    companion object {
        fun fromId(id: String?): LibrarySortOption = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

/** Library filter chips; tapping the active chip again clears it (Part R). */
enum class LibraryFilter(val label: String) {
    PLAYLISTS("Playlists"),
    BY_ME("By me"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
}

/** Where a library playlist came from, used for the secondary label and the row menu. */
enum class LibraryPlaylistKind { LOCAL, SPOTIFY, YOUTUBE }

/** One unified library playlist row (local, imported Spotify, or a YouTube account playlist). */
data class LibraryPlaylistRow(
    val key: String,
    val name: String,
    val owner: String,
    val kind: LibraryPlaylistKind,
    /** Up to four representative songs for the mosaic; a single song when there are not four distinct covers. */
    val covers: List<Track>,
    /** A direct cover URL (YouTube account playlists), when there are no local songs to mosaic. */
    val coverUrl: String?,
    val localPlaylistId: Long?,
    val ytPlaylist: YtPlaylist?,
    val trackCount: Int,
    val updatedAt: Long,
)

/**
 * Merges local playlists (including the ones a Spotify CSV import filled), their on-device and
 * streamed entries, and the signed-in YouTube account's playlists into one ordered list. Playlists
 * without songs are hidden. [sort] reorders the result.
 */
fun buildLibraryPlaylists(
    playlists: List<PlaylistEntity>,
    tracks: List<Track>,
    playlistEntries: List<PlaylistEntryEntity>,
    playlistYtEntries: List<PlaylistYtEntryEntity>,
    youtubePlaylists: List<YtPlaylist>,
    spotifyImports: List<SpotifyImportEntity>,
    accountName: String?,
    sort: LibrarySortOption,
): List<LibraryPlaylistRow> {
    val trackById = tracks.associateBy { it.id }
    val spotifyPlaylistIds = spotifyImports.mapNotNull { it.playlistId }.toSet()
    val ownerName = accountName?.takeIf { it.isNotBlank() } ?: "You"

    val locals = playlists.mapNotNull { playlist ->
        val ordered = ArrayList<Pair<Int, Track>>()
        playlistEntries.asSequence().filter { it.playlistId == playlist.id }.forEach { entry ->
            trackById[entry.trackId]?.let { ordered += entry.position to it }
        }
        playlistYtEntries.asSequence().filter { it.playlistId == playlist.id }.forEach { entry ->
            ordered += entry.position to entry.toDomain()
        }
        val songs = ordered.sortedBy { it.first }.map { it.second }
        if (songs.isEmpty()) return@mapNotNull null
        val kind = if (playlist.id in spotifyPlaylistIds) LibraryPlaylistKind.SPOTIFY else LibraryPlaylistKind.LOCAL
        LibraryPlaylistRow(
            key = "local-${playlist.id}",
            name = playlist.name,
            owner = if (kind == LibraryPlaylistKind.SPOTIFY) "Spotify import" else ownerName,
            kind = kind,
            covers = mosaicCovers(songs),
            coverUrl = null,
            localPlaylistId = playlist.id,
            ytPlaylist = null,
            trackCount = songs.size,
            updatedAt = playlist.updatedAt,
        )
    }

    val remote = youtubePlaylists.map { yt ->
        LibraryPlaylistRow(
            key = "yt-${yt.id}",
            name = yt.title,
            owner = accountName?.takeIf { it.isNotBlank() } ?: "YouTube Music",
            kind = LibraryPlaylistKind.YOUTUBE,
            covers = emptyList(),
            coverUrl = yt.thumbnailUrl,
            localPlaylistId = null,
            ytPlaylist = yt,
            trackCount = yt.trackCount,
            updatedAt = 0L,
        )
    }

    val merged = locals + remote
    return when (sort) {
        LibrarySortOption.CUSTOM -> merged
        LibrarySortOption.RECENTS -> merged.sortedByDescending { it.updatedAt }
        LibrarySortOption.AZ -> merged.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}

/** Up to four distinct covers, or a single cover when fewer than four distinct arts exist. */
private fun mosaicCovers(songs: List<Track>): List<Track> {
    val distinct = LinkedHashMap<String, Track>()
    for (song in songs) {
        val key = song.artKey ?: song.artworkUrl ?: song.id
        if (!distinct.containsKey(key)) distinct[key] = song
        if (distinct.size == 4) break
    }
    return if (distinct.size >= 4) distinct.values.toList() else listOfNotNull(songs.firstOrNull())
}

private enum class LibraryTileType { LIKED, LOCAL, DOWNLOADED }

private data class LibraryTile(val type: LibraryTileType, val title: String, val subtitle: String)

private enum class LibrarySection { LIKED, LOCAL, DOWNLOADED }

@Composable
fun LibraryScreen(
    tracks: List<Track>, favorites: Set<String>, sources: List<MusicSource>, playlists: List<PlaylistEntity>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit, onAddToPlaylist: (Long, Track) -> Unit, modifier: Modifier = Modifier,
    onQueue: (Track) -> Unit = {}, onPlayAlbum: (List<Track>) -> Unit = {}, onQueueAlbum: (List<Track>) -> Unit = {}, onQueuePlaylist: (Long) -> Unit = {}, scanning: Boolean = false, onPlayNext: (Track) -> Unit = onQueue,
    onOpenSettings: () -> Unit = {},
    youtubeEnabled: Boolean = false,
    youtubeLiked: List<Track> = emptyList(),
    youtubePlaylists: List<YtPlaylist> = emptyList(),
    onPlayYouTubeTrack: (Track) -> Unit = onPlay,
    onOpenYouTubePlaylist: (YtPlaylist) -> Unit = {},
    onRefreshYouTubeLibrary: () -> Unit = {},
    downloads: List<Track> = emptyList(),
    onOpenHistory: () -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onImportSpotify: () -> Unit = {},
    spotifyImports: List<SpotifyImportEntity> = emptyList(),
    onOpenImport: (Long) -> Unit = {},
    onAutoFixTags: () -> Unit = {},
    // Part R: library redesign inputs and callbacks.
    playlistEntries: List<PlaylistEntryEntity> = emptyList(),
    playlistYtEntries: List<PlaylistYtEntryEntity> = emptyList(),
    profileImageUrl: String? = null,
    accountName: String? = null,
    librarySort: String = LibrarySortOption.CUSTOM.id,
    libraryGridView: Boolean = false,
    onLibrarySortChange: (String) -> Unit = {},
    onLibraryGridChange: (Boolean) -> Unit = {},
    onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Long) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onShufflePlaylist: (Long) -> Unit = {},
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassLibraryScreen(
            tracks, favorites, sources, playlists, currentId, onAddMusic, onScan, onPlay, onFavorite,
            onCreatePlaylist, onAddToPlaylist, modifier, onQueue, onPlayAlbum, onQueueAlbum, onQueuePlaylist,
            scanning, onPlayNext, onOpenSettings, youtubeEnabled, youtubeLiked, youtubePlaylists,
            onPlayYouTubeTrack, onOpenYouTubePlaylist, onRefreshYouTubeLibrary, downloads, onOpenHistory,
            onOpenPlaylist, onImportSpotify, spotifyImports, onOpenImport, onAutoFixTags,
            playlistEntries, playlistYtEntries, profileImageUrl, accountName, librarySort, libraryGridView,
            onLibrarySortChange, onLibraryGridChange, onRenamePlaylist, onDeletePlaylist, onPlayPlaylist, onShufflePlaylist,
        )
        return
    }

    val sort = LibrarySortOption.fromId(librarySort)
    var filter by remember { mutableStateOf<LibraryFilter?>(null) }
    var section by remember { mutableStateOf<LibrarySection?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<LibraryPlaylistRow?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryPlaylistRow?>(null) }

    LaunchedEffect(youtubeEnabled) { if (youtubeEnabled) onRefreshYouTubeLibrary() }

    val playlistRows = remember(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, accountName, sort, query, filter) {
        val all = buildLibraryPlaylists(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, accountName, sort)
        val scoped = when (filter) {
            LibraryFilter.BY_ME -> all.filter { it.kind == LibraryPlaylistKind.LOCAL }
            else -> all
        }
        if (query.isBlank()) scoped
        else scoped.filter { it.name.contains(query, ignoreCase = true) }
    }

    val localSongs = remember(tracks, query) {
        if (query.isBlank()) tracks else tracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }

    val likedMerged = remember(tracks, favorites, youtubeLiked) {
        (youtubeLiked + tracks.filter { it.id in favorites }).distinctBy { it.id }
    }

    val libraryEmpty = tracks.isEmpty() && playlists.isEmpty() && youtubePlaylists.isEmpty() &&
        downloads.isEmpty() && youtubeLiked.isEmpty() && spotifyImports.isEmpty()

    Column(modifier.fillMaxSize()) {
        // Large title + "+" pill, library search, and the profile avatar (opens Settings).
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your Library",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
                        .clickable { plusOpen = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, "Add", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(plusOpen, { plusOpen = false }) {
                    DropdownMenuItem(text = { Text("New playlist") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { plusOpen = false; newName = ""; creating = true })
                    DropdownMenuItem(text = { Text("Import Spotify CSV") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { plusOpen = false; onImportSpotify() })
                    DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = { plusOpen = false; onOpenHistory() })
                    DropdownMenuItem(text = { Text("Auto-fix tags") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { plusOpen = false; onAutoFixTags() })
                }
            }
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Rounded.Search, "Search library") }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                AccountAvatar(imageUrl = profileImageUrl, contentDescription = "Open settings", modifier = Modifier.fillMaxSize())
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search your library") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
            )
        }

        // Sort selector (left) and list/grid toggle (right); both persisted.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Text(sort.label)
                    Icon(Icons.Rounded.ArrowDropDown, null, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    LibrarySortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { sortOpen = false; onLibrarySortChange(option.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onLibraryGridChange(!libraryGridView) }) {
                Icon(
                    if (libraryGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    if (libraryGridView) "Switch to list" else "Switch to grid",
                )
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries.toList()) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = if (filter == option) null else option; section = null },
                    label = { Text(option.label, maxLines = 1) },
                    shape = CircleShape,
                )
            }
        }

        when {
            filter == null && section == null && libraryEmpty ->
                EmptyLibraryHero(onAddMusic, onScan, Modifier.weight(1f), scanning, onOpenSettings, sources.isEmpty())
            section != null -> {
                SectionHeader(section!!, onBack = { section = null })
                when (section) {
                    LibrarySection.LIKED -> TrackRows(likedMerged, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    LibrarySection.LOCAL -> TrackRows(localSongs, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    LibrarySection.DOWNLOADED -> TrackRows(downloads, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    else -> Unit
                }
            }
            filter == LibraryFilter.ARTISTS -> ArtistsList(tracks, Modifier.weight(1f))
            filter == LibraryFilter.ALBUMS -> AlbumsList(tracks, onPlayAlbum, onQueueAlbum, Modifier.weight(1f))
            else -> {
                val showTiles = filter == null
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showTiles) {
                        val tiles = listOf(
                            LibraryTile(LibraryTileType.LIKED, "Liked Songs", "${likedMerged.size} songs"),
                            LibraryTile(LibraryTileType.LOCAL, "Local Files", "${localSongs.size} songs"),
                            LibraryTile(LibraryTileType.DOWNLOADED, "Downloaded", "${downloads.size} songs"),
                        )
                        if (libraryGridView) {
                            items(tiles.chunked(2)) { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { tile -> LibraryTileCard(tile, Modifier.weight(1f)) { section = tile.type.toSection() } }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            items(tiles) { tile -> LibraryTileRow(tile) { section = tile.type.toSection() } }
                        }
                    }

                    if (playlistRows.isEmpty() && !showTiles) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                                Text("No playlists yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (libraryGridView) {
                        items(playlistRows.chunked(2), key = { it.first().key }) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { row ->
                                    LibraryPlaylistCard(
                                        row = row,
                                        modifier = Modifier.weight(1f),
                                        onOpen = { openPlaylist(row, onOpenPlaylist, onOpenYouTubePlaylist) },
                                        onPlay = { row.localPlaylistId?.let(onPlayPlaylist) ?: row.ytPlaylist?.let(onOpenYouTubePlaylist) },
                                        onShuffle = { row.localPlaylistId?.let(onShufflePlaylist) },
                                        onRename = { renameTarget = row },
                                        onDelete = { deleteTarget = row },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        items(playlistRows, key = { it.key }) { row ->
                            LibraryPlaylistRowItem(
                                row = row,
                                onOpen = { openPlaylist(row, onOpenPlaylist, onOpenYouTubePlaylist) },
                                onPlay = { row.localPlaylistId?.let(onPlayPlaylist) ?: row.ytPlaylist?.let(onOpenYouTubePlaylist) },
                                onShuffle = { row.localPlaylistId?.let(onShufflePlaylist) },
                                onRename = { renameTarget = row },
                                onDelete = { deleteTarget = row },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) onCreatePlaylist(newName); newName = ""; creating = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { row ->
        var updated by remember(row.key) { mutableStateOf(row.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(updated, { updated = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    row.localPlaylistId?.let { if (updated.isNotBlank()) onRenamePlaylist(it, updated) }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist") },
            text = { Text("Delete “${row.name}”? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { row.localPlaylistId?.let(onDeletePlaylist); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun openPlaylist(row: LibraryPlaylistRow, onOpenPlaylist: (Long) -> Unit, onOpenYouTubePlaylist: (YtPlaylist) -> Unit) {
    row.localPlaylistId?.let(onOpenPlaylist) ?: row.ytPlaylist?.let(onOpenYouTubePlaylist)
}

private fun LibraryTileType.toSection(): LibrarySection = when (this) {
    LibraryTileType.LIKED -> LibrarySection.LIKED
    LibraryTileType.LOCAL -> LibrarySection.LOCAL
    LibraryTileType.DOWNLOADED -> LibrarySection.DOWNLOADED
}

@Composable
private fun SectionHeader(section: LibrarySection, onBack: () -> Unit) {
    val title = when (section) {
        LibrarySection.LIKED -> "Liked Songs"
        LibrarySection.LOCAL -> "Local Files"
        LibrarySection.DOWNLOADED -> "Downloaded"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library") }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryTileRow(tile: LibraryTile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryTileArt(tile.type, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryTileCard(tile: LibraryTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(8.dp)) {
        LibraryTileArt(tile.type, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryTileArt(type: LibraryTileType, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val brush = when (type) {
        LibraryTileType.LIKED -> Brush.linearGradient(listOf(Color(0xFFE23E7A), Color(0xFF9B4DFF)))
        LibraryTileType.LOCAL -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))
        LibraryTileType.DOWNLOADED -> Brush.linearGradient(listOf(Color(0xFF3E7BE2), Color(0xFF42C6D6)))
    }
    val icon = when (type) {
        LibraryTileType.LIKED -> Icons.Rounded.Favorite
        LibraryTileType.LOCAL -> Icons.Rounded.Folder
        LibraryTileType.DOWNLOADED -> Icons.Rounded.Download
    }
    val tint = if (type == LibraryTileType.LOCAL) MaterialTheme.colorScheme.primary else Color.White
    Box(modifier.clip(shape).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
private fun LibraryPlaylistRowItem(
    row: LibraryPlaylistRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistMosaic(row, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Playlist • ${row.owner}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlaylistMenu(row, onPlay, onShuffle, onRename, onDelete)
    }
}

@Composable
private fun LibraryPlaylistCard(
    row: LibraryPlaylistRow,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen).padding(8.dp)) {
        PlaylistMosaic(row, Modifier.fillMaxWidth().aspectRatio(1f))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Playlist • ${row.owner}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlaylistMenu(row, onPlay, onShuffle, onRename, onDelete)
        }
    }
}

@Composable
private fun PlaylistMenu(
    row: LibraryPlaylistRow,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "More actions for ${row.name}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) }, onClick = { expanded = false; onPlay() })
            if (row.localPlaylistId != null) {
                DropdownMenuItem(text = { Text("Shuffle") }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null) }, onClick = { expanded = false; onShuffle() })
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { expanded = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
private fun PlaylistMosaic(row: LibraryPlaylistRow, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape)) {
        when {
            row.covers.isEmpty() && !row.coverUrl.isNullOrBlank() ->
                ArtworkImage(track = null, modifier = Modifier.fillMaxSize(), shape = shape, artUri = row.coverUrl)
            row.covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    ArtworkImage(row.covers[0], Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(0.dp))
                    ArtworkImage(row.covers[1], Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(0.dp))
                }
                Row(Modifier.weight(1f)) {
                    ArtworkImage(row.covers[2], Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(0.dp))
                    ArtworkImage(row.covers[3], Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(0.dp))
                }
            }
            row.covers.isNotEmpty() -> ArtworkImage(row.covers[0], Modifier.fillMaxSize(), shape = shape)
            else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(0.4f))
            }
        }
    }
}

@Composable
private fun ArtistsList(tracks: List<Track>, modifier: Modifier = Modifier) {
    val values = remember(tracks) { tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted() }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
        items(values) { artist ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistAvatar(name = artist, size = 44.dp)
                Text(artist, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AlbumsList(tracks: List<Track>, onPlayAlbum: (List<Track>) -> Unit, onQueueAlbum: (List<Track>) -> Unit, modifier: Modifier = Modifier) {
    val albumOrder = remember { compareBy<Track> { it.discNumber ?: 0 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title } }
    val albums = remember(tracks, albumOrder) {
        tracks.groupBy { it.album.ifBlank { "Unknown album" } }
            .mapValues { (_, albumTracks) -> albumTracks.sortedWith(albumOrder) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        items(albums.entries.toList(), key = { it.key }) { entry ->
            val ordered = entry.value
            Row(Modifier.fillMaxWidth().clickable { onPlayAlbum(ordered) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(ordered.first(), Modifier.size(56.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(entry.key, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${ordered.first().albumArtist} · ${ordered.size} songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onQueueAlbum(ordered) }) { Icon(Icons.Rounded.QueueMusic, "Add album to queue", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
