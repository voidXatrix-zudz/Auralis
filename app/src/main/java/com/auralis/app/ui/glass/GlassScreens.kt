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
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.PlaylistYtEntryEntity
import com.auralis.app.data.youtube.YtPlaylist
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.ArtistAvatar
import com.auralis.app.ui.components.LiveDot
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.home.LocalHomeFeed
import com.auralis.app.data.youtube.LiveSyncState
import com.auralis.app.ui.screens.LibraryFilter
import com.auralis.app.ui.screens.LibraryPlaylistKind
import com.auralis.app.ui.screens.LibraryPlaylistRow
import com.auralis.app.ui.screens.LibrarySortOption
import com.auralis.app.ui.screens.buildLibraryPlaylists
import com.auralis.app.ui.screens.isLibraryEmpty
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
    modifier: Modifier, scanning: Boolean, onPlayNext: (Track) -> Unit,
    onOpenSettings: () -> Unit = {},
    avatarUrl: String? = null, accountName: String? = null,
    libraryPlaylists: List<LibraryPlaylistRow> = emptyList(),
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenYouTubePlaylist: (YtPlaylist) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
) {
    LocalHomeFeed(
        sources = sources,
        tracks = tracks,
        favorites = favorites,
        currentId = currentId,
        libraryPlaylists = libraryPlaylists,
        scanning = scanning,
        avatarUrl = avatarUrl,
        accountName = accountName,
        playlists = playlists,
        onAddMusic = onAddMusic,
        onScan = onScan,
        onPlay = onPlay,
        onFavorite = onFavorite,
        onPlayNext = onPlayNext,
        onAddToPlaylist = onAddToPlaylist,
        onOpenSettings = onOpenSettings,
        onOpenPlaylist = onOpenPlaylist,
        onOpenYouTubePlaylist = onOpenYouTubePlaylist,
        onOpenLibrary = onOpenLibrary,
        onOpenExplore = onOpenExplore,
        modifier = modifier,
    )
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
    youtubeEnabled: Boolean = false,
    recentSearches: List<String> = emptyList(),
    onSubmitSearch: (String) -> Unit = onSearchYouTube,
    onDeleteRecent: (String) -> Unit = {},
    onClearRecent: () -> Unit = {},
    youtubeSignedIn: Boolean = false,
    onConnectYouTube: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var youtube by remember { mutableStateOf(false) }
    // YouTube is the default source every time Search opens, when the integration is on.
    LaunchedEffect(youtubeEnabled) { youtube = youtubeEnabled }
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
            onSearch = { if (youtube && q.isNotBlank()) onSubmitSearch(q) },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (youtubeEnabled) {
                GlassChip("YouTube", youtube, { youtube = true })
                GlassChip("Local", !youtube, { youtube = false; onSearchYouTube("") })
            } else {
                GlassChip("Local", true, {})
            }
        }
        if (youtube && !youtubeSignedIn) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sign in to sync recent searches with YouTube Music",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onConnectYouTube) { Text("Connect") }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (youtube) {
            when {
                q.isBlank() -> if (recentSearches.isNotEmpty()) {
                    GlassRecentSearches(
                        recentSearches,
                        Modifier.weight(1f),
                        onPick = { picked ->
                            query = picked
                            if (youtube) onSubmitSearch(picked)
                        },
                        onDelete = onDeleteRecent,
                        onClear = onClearRecent,
                    )
                } else {
                    GlassEmptyState(
                        "Search YouTube Music",
                        "Find songs by title or artist. Results stream over the internet.",
                        Modifier.weight(1f),
                    )
                }
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
                        youtubeResults, favorites, currentId, onPlayYouTube, onFavorite, playlists, onAddToPlaylist,
                        Modifier.weight(1f), onQueue, onPlayNext, youtubeResolvingId,
                    )
                }
            }
        } else {
            when {
                q.isBlank() -> if (recentSearches.isNotEmpty()) {
                    GlassRecentSearches(
                        recentSearches,
                        Modifier.weight(1f),
                        onPick = { picked ->
                            query = picked
                            if (youtube) onSubmitSearch(picked)
                        },
                        onDelete = onDeleteRecent,
                        onClear = onClearRecent,
                    )
                } else {
                    GlassEmptyState("Find anything", "Search by title, artist, album, genre or folder.", Modifier.weight(1f))
                }
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
private fun GlassRecentSearches(
    queries: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent searches",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GlassGhostButton("Clear all", onClear)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(queries, key = { it.lowercase() }) { item ->
                GlassPanel(
                    Modifier.fillMaxWidth(),
                    RoundedCornerShape(GlassTokens.RadiusMd),
                    onClick = { onPick(item) },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.History,
                            null,
                            tint = GlassTokens.SilverMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            item,
                            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        GlassIconAction(
                            Icons.Rounded.Close,
                            "Remove “$item” from recent searches",
                            { onDelete(item) },
                            tint = GlassTokens.SilverMuted,
                        )
                    }
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
    // library redesign inputs and callbacks.
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
    /** Non-null when the account playlist fetch failed; the list shows a retry row instead of "none". */
    youtubeLibraryError: String? = null,
    onRetryYouTubeLibrary: () -> Unit = {},
    /** True when YouTube Music is connected *and* a session is usable. Suppresses the empty state. */
    youtubeSignedIn: Boolean = false,
    /** First-load / background refresh indicator; keeps the empty state from flashing. */
    youtubeLibraryLoading: Boolean = false,
    /** False until persisted settings have been read once. */
    libraryReady: Boolean = true,
    /** False when local audio access was denied: the Local Files tile offers the permission instead. */
    hasAudioPermission: Boolean = true,
    onRequestAudioPermission: () -> Unit = {},
    /** Starts a YouTube song *and* queues the rest of the list it was tapped in. */
    onPlayYouTubeTrackFrom: (Track, List<Track>) -> Unit = { track, _ -> onPlayYouTubeTrack(track) },
    /** The card's small play button: streams the playlist without opening the detail page. */
    onPlayYouTubePlaylist: (com.auralis.app.data.youtube.YtPlaylist) -> Unit = onOpenYouTubePlaylist,
    /** The YouTube song currently resolving a stream; its row shows a spinner instead of Play. */
    youtubeResolvingId: String? = null,
    /** Live-sync state shown as a tiny marker on the Liked Songs tile (both themes). */
    liveSyncState: LiveSyncState = LiveSyncState.Disabled,
    liveSyncPulse: Long = 0L,
    /** Whole-list tap handlers: each section queues its own list, never the whole library. */
    onPlayLiked: (Track) -> Unit = onPlay,
    onPlayLocal: (Track) -> Unit = onPlay,
    onPlayDownloaded: (Track) -> Unit = onPlay,
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
    // Connected + signed in means the Library always has content; never replace it with the
    // "choose a folder / scan" state, even if local audio access was denied.
    val youtubeActive = youtubeEnabled && youtubeSignedIn
    val libraryEmpty = isLibraryEmpty(
        youtubeEnabled = youtubeEnabled,
        youtubeSignedIn = youtubeSignedIn,
        localTracks = tracks.size,
        playlists = playlists.size,
        youtubePlaylists = youtubePlaylists.size,
        downloads = downloads.size,
        youtubeLiked = youtubeLiked.size,
        spotifyImports = spotifyImports.size,
        youtubeError = youtubeLibraryError,
    )
    // Liked Songs mixes YouTube streams and local favourites: playLiked queues the exact merged list.
    val likedOnPlay: (Track) -> Unit = onPlayLiked

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Your Library",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 16.sp,
                    maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                ),
            )
            Box {
                Box(
                    Modifier.heightIn(min = 40.dp).clip(CircleShape)
                        .glassTint(CircleShape)
                        .clickable { plusOpen = true }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Add", tint = GlassTokens.Cream, modifier = Modifier.size(20.dp)) }
                GlassMenu(plusOpen, { plusOpen = false }) {
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
                GlassMenu(sortOpen, { sortOpen = false }) {
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
            filter == null && section == null && libraryEmpty && libraryReady ->
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
                    if (section == GlassLibrarySection.LIKED) {
                        Spacer(Modifier.size(8.dp))
                        LiveDot(liveSyncState, liveSyncPulse)
                    }
                    Spacer(Modifier.weight(1f))
                    if (section == GlassLibrarySection.LIKED && youtubeEnabled) GlassGhostButton("Refresh", onRefreshYouTubeLibrary)
                }
                when (section) {
                    GlassLibrarySection.LIKED ->
                        if (likedMerged.isEmpty()) GlassEmptyState("No liked songs yet", "Heart a song to see it here.", Modifier.weight(1f))
                        else GlassTrackRows(likedMerged, favorites, currentId, likedOnPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext, youtubeResolvingId)
                    GlassLibrarySection.LOCAL ->
                        if (localSongs.isEmpty()) GlassEmptyState(
                            if (hasAudioPermission) "No local songs" else "Local files need access",
                            if (hasAudioPermission) "Add a folder or scan this device." else "Allow access to show songs stored on this device.",
                            Modifier.weight(1f),
                        ) {
                            if (!hasAudioPermission) GlassPrimaryButton("Allow access", onRequestAudioPermission, icon = Icons.Rounded.FolderOpen)
                        }
                        else GlassTrackRows(localSongs, favorites, currentId, onPlayLocal, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
                    GlassLibrarySection.DOWNLOADED ->
                        if (downloads.isEmpty()) GlassEmptyState("No downloads yet", "Use \"Download\" on a YouTube song.", Modifier.weight(1f))
                        else GlassTrackRows(downloads, favorites, currentId, onPlayDownloaded, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
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
                    // Until the persisted settings and the cached playlists have been read, show a
                    // thin bar instead of an empty list (or letting the hero flash).
                    val glassFirstLoad = !libraryReady ||
                        (youtubeActive && youtubeLibraryLoading && youtubePlaylists.isEmpty())
                    if (glassFirstLoad) {
                        item {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (showTiles) {
                        val tiles = listOf(
                            GlassLibraryTile(GlassTileKind.LIKED, "Liked Songs", "${likedMerged.size} songs"),
                            GlassLibraryTile(
                                GlassTileKind.LOCAL,
                                "Local Files",
                                if (hasAudioPermission) "${localSongs.size} songs" else "Allow access",
                            ),
                            GlassLibraryTile(GlassTileKind.DOWNLOADED, "Downloaded", "${downloads.size} songs"),
                        )
                        // A denied audio permission only affects this tile; the rest of the Library
                        // stays usable.
                        val openTile: (GlassLibraryTile) -> Unit = { tile ->
                            if (tile.kind == GlassTileKind.LOCAL && !hasAudioPermission) onRequestAudioPermission()
                            else section = tile.kind.toSection()
                        }
                        if (libraryGridView) {
                            items(tiles.chunked(2)) { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { tile -> GlassTileCard(tile, Modifier.weight(1f), liveSyncState, liveSyncPulse) { openTile(tile) } }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            items(tiles) { tile -> GlassTileRow(tile, liveSyncState, liveSyncPulse) { openTile(tile) } }
                        }
                    }
                    if (youtubeLibraryError != null) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    youtubeLibraryError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onRetryYouTubeLibrary) { Text("Retry") }
                            }
                        }
                    }
                    if (playlistRows.isEmpty() && !showTiles && youtubeLibraryError == null) {
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
                                        onPlay = { row.localPlaylistId?.let(onPlayPlaylist) ?: row.ytPlaylist?.let(onPlayYouTubePlaylist) },
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
                                onPlay = { row.localPlaylistId?.let(onPlayPlaylist) ?: row.ytPlaylist?.let(onPlayYouTubePlaylist) },
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
    if (creating) GlassMaterialAlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { GlassSearchField(name, { name = it }, "Name") },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreatePlaylist(name); name = ""; creating = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    renameTarget?.let { row ->
        var updated by remember(row.key) { mutableStateOf(row.name) }
        GlassMaterialAlertDialog(
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
        GlassMaterialAlertDialog(
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
private fun GlassTileRow(
    tile: GlassLibraryTile,
    liveState: LiveSyncState = LiveSyncState.Disabled,
    livePulse: Long = 0L,
    onClick: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd), onClick = onClick) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTileArt(tile.kind, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tile.kind == GlassTileKind.LIKED) {
                        Spacer(Modifier.size(6.dp))
                        LiveDot(liveState, livePulse)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassTileCard(
    tile: GlassLibraryTile,
    modifier: Modifier = Modifier,
    liveState: LiveSyncState = LiveSyncState.Disabled,
    livePulse: Long = 0L,
    onClick: () -> Unit,
) {
    GlassPanel(modifier, RoundedCornerShape(GlassTokens.RadiusMd), onClick = onClick) {
        Column(Modifier.padding(10.dp)) {
            GlassTileArt(tile.kind, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tile.kind == GlassTileKind.LIKED) {
                    Spacer(Modifier.size(6.dp))
                    LiveDot(liveState, livePulse)
                }
            }
        }
    }
}

@Composable
private fun GlassTileArt(kind: GlassTileKind, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    // Library tiles are chrome, not artwork: cream/indigo only. LIKED inverts like a selected chip.
    val brush = when (kind) {
        GlassTileKind.LIKED -> Brush.linearGradient(listOf(GlassTokens.Cream, GlassTokens.Cream))
        GlassTileKind.LOCAL -> Brush.linearGradient(listOf(GlassTokens.Cream20, GlassTokens.Cream6))
        GlassTileKind.DOWNLOADED -> Brush.linearGradient(listOf(GlassTokens.Cream14, GlassTokens.Cream6))
    }
    val iconTint = if (kind == GlassTileKind.LIKED) GlassTokens.Indigo else GlassTokens.Cream
    val icon = when (kind) {
        GlassTileKind.LIKED -> Icons.Rounded.Favorite
        GlassTileKind.LOCAL -> Icons.Rounded.Folder
        GlassTileKind.DOWNLOADED -> Icons.Rounded.Download
    }
    Box(modifier.clip(shape).background(brush).border(1.dp, GlassTokens.Cream14, shape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.fillMaxSize(0.42f))
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
        GlassMenu(expanded, { expanded = false }) {
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
            else -> Box(Modifier.fillMaxSize().background(GlassTokens.Cream10), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = GlassTokens.Cream, modifier = Modifier.fillMaxSize(0.4f))
            }
        }
    }
}
