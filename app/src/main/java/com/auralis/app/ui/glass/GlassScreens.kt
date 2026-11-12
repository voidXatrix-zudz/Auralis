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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.PlaylistYtEntryEntity
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.ArtistAvatar
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.screens.LibraryFilter
import com.auralis.app.ui.screens.LibraryPlaylistKind
import com.auralis.app.ui.screens.LibraryPlaylistRow
import com.auralis.app.ui.screens.LibrarySortOption
import com.auralis.app.ui.screens.buildLibraryPlaylists
import com.auralis.app.ui.theme.GlassTokens
import java.time.LocalTime
import java.time.ZoneId

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
    onOpenSettings: () -> Unit = {}, settingsBadge: Boolean = false,
) {
    // Recomputed from the device's local clock on every resume so it never goes stale.
    var greeting by remember { mutableStateOf(greetingFor(LocalTime.now(ZoneId.systemDefault()))) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        greeting = greetingFor(LocalTime.now(ZoneId.systemDefault()))
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (tracks.isEmpty()) "Welcome" else greeting,
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
                badge = settingsBadge,
            )
        }
        if (tracks.isEmpty()) {
            if (sources.isEmpty()) {
                GlassEmptyState("Choose a music folder to start", "AURALIS only scans the folders you choose.", Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassPrimaryButton("Choose a music folder", onOpenSettings, icon = Icons.Rounded.FolderOpen)
                        GlassGhostButton("Add folder", onAddMusic, icon = Icons.Rounded.FolderOpen, enabled = !scanning)
                    }
                }
            } else GlassEmptyState("Your music, offline", "Scan songs on this device or add a folder.", Modifier.weight(1f)) {
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
    youtubeResults: List<Track> = emptyList(),
    youtubeLoading: Boolean = false,
    youtubeError: String? = null,
    youtubeResolvingId: String? = null,
    onSearchYouTube: (String) -> Unit = {},
    onPlayYouTube: (Track) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var youtube by rememberSaveable { mutableStateOf(false) }
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
    // Search debounce/cancellation lives in the ViewModel; forward every keystroke.
    LaunchedEffect(youtube, q) {
        if (youtube && q.isNotBlank()) {
            onSearchYouTube(q)
        }
    }
    Column(modifier.fillMaxSize()) {
        GlassHeader("Search", Modifier.padding(horizontal = 16.dp))
        GlassSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = if (youtube) "Search YouTube Music" else "Songs, artists, albums, folders",
            leading = { Icon(Icons.Rounded.Search, contentDescription = if (youtube) "Search YouTube" else "Search library") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("Local", !youtube, { youtube = false })
            GlassChip("YouTube", youtube, { youtube = true })
        }
        Spacer(Modifier.height(12.dp))
        if (youtube) {
            when {
                q.isBlank() -> GlassEmptyState(
                    "Search YouTube Music",
                    "Find songs by title or artist. Results stream over the internet.",
                    Modifier.weight(1f),
                )
                youtubeLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                youtubeError != null -> GlassEmptyState("YouTube unavailable", youtubeError, Modifier.weight(1f)) {
                    TextButton(onClick = { onSearchYouTube(q) }) { Text("Try again") }
                }
                youtubeResults.isEmpty() -> GlassEmptyState(
                    "No results",
                    "Nothing matches “$q”. Try a different spelling.",
                    Modifier.weight(1f),
                )
                else -> {
                    Text(
                        "${youtubeResults.size} results",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GlassTrackRows(
                        youtubeResults, favorites, currentId, onPlayYouTube, {}, emptyList(), { _, _ -> },
                        Modifier.weight(1f), {}, {}, youtubeResolvingId,
                    )
                }
            }
        } else {
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
}

@Composable
fun GlassLibraryScreen(
    tracks: List<Track>, favorites: Set<String>, sources: List<MusicSource>, playlists: List<PlaylistEntity>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit, onAddToPlaylist: (Long, Track) -> Unit, modifier: Modifier,
    onQueue: (Track) -> Unit, onPlayAlbum: (List<Track>) -> Unit, onQueueAlbum: (List<Track>) -> Unit,
    onQueuePlaylist: (Long) -> Unit, scanning: Boolean, onPlayNext: (Track) -> Unit,
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
    val sort = LibrarySortOption.fromId(librarySort)
    var filter by remember { mutableStateOf<LibraryFilter?>(null) }
    var section by remember { mutableStateOf<GlassLibrarySection?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<LibraryPlaylistRow?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryPlaylistRow?>(null) }

    LaunchedEffect(youtubeEnabled) { if (youtubeEnabled) onRefreshYouTubeLibrary() }

    val playlistRows = remember(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, accountName, sort, query, filter) {
        val all = buildLibraryPlaylists(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, accountName, sort)
        val scoped = when (filter) {
            LibraryFilter.BY_ME -> all.filter { it.kind == LibraryPlaylistKind.LOCAL }
            else -> all
        }
        if (query.isBlank()) scoped else scoped.filter { it.name.contains(query, ignoreCase = true) }
    }
    val localSongs = remember(tracks, query) {
        if (query.isBlank()) tracks else tracks.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || it.album.contains(query, ignoreCase = true)
        }
    }
    val likedMerged = remember(tracks, favorites, youtubeLiked) { (youtubeLiked + tracks.filter { it.id in favorites }).distinctBy { it.id } }
    val libraryEmpty = tracks.isEmpty() && playlists.isEmpty() && youtubePlaylists.isEmpty() &&
        downloads.isEmpty() && youtubeLiked.isEmpty() && spotifyImports.isEmpty()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Your Library",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                Box(
                    Modifier.heightIn(min = 40.dp).clip(CircleShape)
                        .background(GlassTokens.Violet.copy(alpha = 0.18f))
                        .border(1.dp, GlassTokens.BorderBrush, CircleShape)
                        .clickable { plusOpen = true }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Add", tint = GlassTokens.Violet, modifier = Modifier.size(20.dp)) }
                DropdownMenu(plusOpen, { plusOpen = false }) {
                    DropdownMenuItem(text = { Text("New playlist") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { plusOpen = false; name = ""; creating = true })
                    DropdownMenuItem(text = { Text("Import Spotify CSV") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { plusOpen = false; onImportSpotify() })
                    DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = { plusOpen = false; onOpenHistory() })
                    DropdownMenuItem(text = { Text("Auto-fix tags") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { plusOpen = false; onAutoFixTags() })
                }
            }
            GlassIconAction(Icons.Rounded.Search, "Search library", { searchOpen = !searchOpen; if (!searchOpen) query = "" })
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) { AccountAvatar(imageUrl = profileImageUrl, contentDescription = "Open settings", modifier = Modifier.fillMaxSize()) }
        }
        if (searchOpen) {
            GlassSearchField(query, { query = it }, "Search your library", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier.clip(CircleShape).clickable { sortOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sort.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    LibrarySortOption.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { sortOpen = false; onLibrarySortChange(option.id) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GlassIconAction(
                if (libraryGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                if (libraryGridView) "Switch to list" else "Switch to grid",
                { onLibraryGridChange(!libraryGridView) },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filters = LibraryFilter.entries
            items(filters.size) { i ->
                val option = filters[i]
                GlassChip(option.label, filter == option, { filter = if (filter == option) null else option; section = null })
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            filter == null && section == null && libraryEmpty ->
                if (sources.isEmpty()) {
                    GlassEmptyState("Choose a music folder to start", "AURALIS only scans the folders you choose.", Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassPrimaryButton("Choose a music folder", onOpenSettings, icon = Icons.Rounded.FolderOpen)
                            GlassGhostButton("Add folder", onAddMusic, icon = Icons.Rounded.FolderOpen, enabled = !scanning)
                        }
                    }
                } else GlassEmptyState("Your music, offline", "Scan songs on this device or add a folder.", Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassPrimaryButton(if (scanning) "Scanning..." else "Scan device", onScan, enabled = !scanning)
                        GlassGhostButton("Add folder", onAddMusic, icon = Icons.Rounded.FolderOpen, enabled = !scanning)
                    }
                }
            section != null -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconAction(Icons.Rounded.ArrowBack, "Back to library", { section = null })
                    Text(glassSectionTitle(section!!), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    if (section == GlassLibrarySection.LIKED && youtubeEnabled) GlassGhostButton("Refresh", onRefreshYouTubeLibrary)
                }
                when (section) {
                    GlassLibrarySection.LIKED ->
                        if (likedMerged.isEmpty()) GlassEmptyState("No liked songs yet", "Heart a song to see it here.", Modifier.weight(1f))
                        else GlassTrackRows(likedMerged, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    GlassLibrarySection.LOCAL ->
                        if (localSongs.isEmpty()) GlassEmptyState("No local songs", "Add a folder or scan this device.", Modifier.weight(1f))
                        else GlassTrackRows(localSongs, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    GlassLibrarySection.DOWNLOADED ->
                        if (downloads.isEmpty()) GlassEmptyState("No downloads yet", "Use \"Download\" on a YouTube song.", Modifier.weight(1f))
                        else GlassTrackRows(downloads, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    else -> Unit
                }
            }
            filter == LibraryFilter.ARTISTS -> {
                val values = remember(tracks) { tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted() }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(values) { value ->
                        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtistAvatar(name = value, size = 40.dp)
                                Text(value, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            filter == LibraryFilter.ALBUMS -> {
                val albums = remember(tracks) {
                    tracks.groupBy { it.album.ifBlank { "Unknown album" } }
                        .mapValues { (_, albumTracks) -> albumTracks.sortedWith(GlassAlbumTrackOrder) }
                        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(albums.entries.toList(), key = { it.key }) { entry ->
                        val ordered = entry.value
                        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = { onPlayAlbum(ordered) }) {
                            Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassArtwork(ordered.first(), Modifier.size(64.dp), 16.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                                    Text(entry.key, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${ordered.first().albumArtist} · ${ordered.size} songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                GlassIconAction(Icons.Rounded.QueueMusic, "Add album to queue", { onQueueAlbum(ordered) }, tint = GlassTokens.Violet)
                            }
                        }
                    }
                }
            }
            else -> {
                val showTiles = filter == null
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showTiles) {
                        val tiles = listOf(
                            GlassLibraryTile(GlassTileKind.LIKED, "Liked Songs", "${likedMerged.size} songs"),
                            GlassLibraryTile(GlassTileKind.LOCAL, "Local Files", "${localSongs.size} songs"),
                            GlassLibraryTile(GlassTileKind.DOWNLOADED, "Downloaded", "${downloads.size} songs"),
                        )
                        if (libraryGridView) {
                            items(tiles.chunked(2)) { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { tile -> GlassTileCard(tile, Modifier.weight(1f)) { section = tile.kind.toSection() } }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            items(tiles) { tile -> GlassTileRow(tile) { section = tile.kind.toSection() } }
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
                                    GlassPlaylistCard(
                                        row = row,
                                        modifier = Modifier.weight(1f),
                                        onOpen = { glassOpenPlaylist(row, onOpenPlaylist, onOpenYouTubePlaylist) },
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
                            GlassPlaylistRowItem(
                                row = row,
                                onOpen = { glassOpenPlaylist(row, onOpenPlaylist, onOpenYouTubePlaylist) },
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
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { GlassSearchField(name, { name = it }, "Name") },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreatePlaylist(name); name = ""; creating = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    renameTarget?.let { row ->
        var updated by remember(row.key) { mutableStateOf(row.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename playlist") },
            text = { GlassSearchField(updated, { updated = it }, "Name") },
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
            confirmButton = { TextButton(onClick = { row.localPlaylistId?.let(onDeletePlaylist); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

private enum class GlassLibrarySection { LIKED, LOCAL, DOWNLOADED }

private enum class GlassTileKind { LIKED, LOCAL, DOWNLOADED }

private data class GlassLibraryTile(val kind: GlassTileKind, val title: String, val subtitle: String)

private fun GlassTileKind.toSection(): GlassLibrarySection = when (this) {
    GlassTileKind.LIKED -> GlassLibrarySection.LIKED
    GlassTileKind.LOCAL -> GlassLibrarySection.LOCAL
    GlassTileKind.DOWNLOADED -> GlassLibrarySection.DOWNLOADED
}

private fun glassSectionTitle(section: GlassLibrarySection): String = when (section) {
    GlassLibrarySection.LIKED -> "Liked Songs"
    GlassLibrarySection.LOCAL -> "Local Files"
    GlassLibrarySection.DOWNLOADED -> "Downloaded"
}

private fun glassOpenPlaylist(row: LibraryPlaylistRow, onOpenPlaylist: (Long) -> Unit, onOpenYouTubePlaylist: (com.auralis.app.data.youtube.YtPlaylist) -> Unit) {
    row.localPlaylistId?.let(onOpenPlaylist) ?: row.ytPlaylist?.let(onOpenYouTubePlaylist)
}

@Composable
private fun GlassTileRow(tile: GlassLibraryTile, onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = onClick) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTileArt(tile.kind, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GlassTileCard(tile: GlassLibraryTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPanel(modifier, RoundedCornerShape(GlassTokens.RadiusMd), onClick = onClick) {
        Column(Modifier.padding(10.dp)) {
            GlassTileArt(tile.kind, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GlassTileArt(kind: GlassTileKind, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val brush = when (kind) {
        GlassTileKind.LIKED -> Brush.linearGradient(listOf(Color(0xFFE23E7A), Color(0xFF9B4DFF)))
        GlassTileKind.LOCAL -> Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.35f), GlassTokens.Ice.copy(alpha = 0.20f)))
        GlassTileKind.DOWNLOADED -> Brush.linearGradient(listOf(Color(0xFF3E7BE2), Color(0xFF42C6D6)))
    }
    val icon = when (kind) {
        GlassTileKind.LIKED -> Icons.Rounded.Favorite
        GlassTileKind.LOCAL -> Icons.Rounded.Folder
        GlassTileKind.DOWNLOADED -> Icons.Rounded.Download
    }
    Box(modifier.clip(shape).background(brush).border(1.dp, GlassTokens.BorderBrush, shape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
private fun GlassPlaylistRowItem(
    row: LibraryPlaylistRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = onOpen) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassPlaylistMosaic(row, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Playlist • ${row.owner}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlassPlaylistMenu(row, onPlay, onShuffle, onRename, onDelete)
        }
    }
}

@Composable
private fun GlassPlaylistCard(
    row: LibraryPlaylistRow,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(modifier, RoundedCornerShape(GlassTokens.RadiusMd), onClick = onOpen) {
        Column(Modifier.padding(10.dp)) {
            GlassPlaylistMosaic(row, Modifier.fillMaxWidth().aspectRatio(1f))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Playlist • ${row.owner}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GlassPlaylistMenu(row, onPlay, onShuffle, onRename, onDelete)
            }
        }
    }
}

@Composable
private fun GlassPlaylistMenu(
    row: LibraryPlaylistRow,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    Box {
        GlassIconAction(Icons.Rounded.MoreVert, "More actions for ${row.name}", { expanded = true })
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
private fun GlassPlaylistMosaic(row: LibraryPlaylistRow, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.clip(shape)) {
        when {
            row.covers.isEmpty() && !row.coverUrl.isNullOrBlank() ->
                GlassArtwork(track = null, modifier = Modifier.fillMaxSize(), radius = 14.dp, artUri = row.coverUrl)
            row.covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    GlassArtwork(row.covers[0], Modifier.weight(1f).fillMaxHeight(), 0.dp)
                    GlassArtwork(row.covers[1], Modifier.weight(1f).fillMaxHeight(), 0.dp)
                }
                Row(Modifier.weight(1f)) {
                    GlassArtwork(row.covers[2], Modifier.weight(1f).fillMaxHeight(), 0.dp)
                    GlassArtwork(row.covers[3], Modifier.weight(1f).fillMaxHeight(), 0.dp)
                }
            }
            row.covers.isNotEmpty() -> GlassArtwork(row.covers[0], Modifier.fillMaxSize(), 14.dp)
            else -> Box(Modifier.fillMaxSize().background(GlassTokens.Violet.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = GlassTokens.Violet, modifier = Modifier.fillMaxSize(0.4f))
            }
        }
    }
}

private fun greetingFor(time: LocalTime): String = when (time.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Good night"
}
