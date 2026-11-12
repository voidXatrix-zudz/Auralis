package com.auralis.app.ui

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.AuralisTheme
import com.auralis.app.ui.theme.GlassOptics
import com.auralis.app.ui.theme.LyricsFontPreloader
import com.auralis.app.ui.theme.LocalGlassOptics
import com.auralis.app.ui.components.AuralisBackdrop
import com.auralis.app.ui.glass.GlassTopBar
import com.auralis.app.ui.glass.GlassAvatarButton
import com.auralis.app.ui.glass.LocalDockGlassStyle
import com.auralis.app.ui.glass.resolveDockGlass
import com.auralis.app.ui.components.AppDock
import com.auralis.app.ui.components.DockMetrics
import com.auralis.app.ui.components.DockScrollState
import com.auralis.app.ui.components.DockStyle
import com.auralis.app.ui.components.ArtistImageResolver
import com.auralis.app.ui.components.DownloadActions
import com.auralis.app.ui.components.LocalArtistImages
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.components.rememberDockScrollState
import com.auralis.app.ui.components.resolveAccountImageUrl

import android.app.Activity
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.app.R
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.screens.AboutScreen
import com.auralis.app.ui.settings.SettingsActions
import com.auralis.app.ui.settings.SettingsHubScreen
import com.auralis.app.ui.settings.SettingsSubScreen
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.screens.HomeScreen
import com.auralis.app.ui.screens.HistoryScreen
import com.auralis.app.ui.screens.LibraryScreen
import com.auralis.app.ui.screens.NowPlayingScreen
import com.auralis.app.ui.screens.PlaylistDetailScreen
import com.auralis.app.ui.screens.SpotifyImportScreen
import com.auralis.app.ui.screens.SearchScreen
import com.auralis.app.ui.screens.SettingsScreen
import com.auralis.app.ui.screens.EqualizerScreen
import com.auralis.app.ui.screens.WrappedScreen
import com.auralis.app.ui.screens.BackupScreen
import com.auralis.app.ui.screens.MetadataEditorScreen
import com.auralis.app.ui.screens.AudioAnalysisScreen
import com.auralis.app.ui.screens.LogsDiagnosticsScreen
import com.auralis.app.ui.update.UpdateDialog
import com.auralis.app.ui.theme.AuralisAppTheme
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.ui.performance.LocalPerformanceMode
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.components.glassSurface
import com.auralis.app.logging.AppLog
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

@Composable
fun AuralisRoot(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistEntries by viewModel.playlistEntries.collectAsStateWithLifecycle()
    val playlistYtEntries by viewModel.playlistYtEntries.collectAsStateWithLifecycle()
    val spotifyImports by viewModel.spotifyImports.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    // Collect the playback state once, then expose only the slices this screen needs as derived
    // state. The position tick (every 500 ms) no longer recomposes the whole app shell.
    val playbackState by viewModel.playback.collectAsStateWithLifecycle()
    val playbackTrackId by remember { derivedStateOf { playbackState.trackId } }
    val isPlaying by remember { derivedStateOf { playbackState.playing } }
    val playbackError by remember { derivedStateOf { playbackState.error } }
    val queueIds by remember { derivedStateOf { playbackState.queueIds } }
    val upNextIds by remember { derivedStateOf { playbackState.upNextIds } }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The one resolved workload for the whole app. Collected once here, provided downwards, so
    // changing the mode (or Battery Saver/thermal state) updates every screen without a restart.
    val profile by viewModel.performanceProfile.collectAsStateWithLifecycle()
    val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
    val performanceReason by viewModel.performanceReason.collectAsStateWithLifecycle()
    // GitHub release state; drives the global dialog, the Settings row and the dock badge.
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    // YouTube source state: remote search results + the song currently being resolved.
    val youtubeSearch by viewModel.youtubeSearch.collectAsStateWithLifecycle()
    val youtubeResolvingId by viewModel.youtubeResolvingId.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteTracks.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val youtubeAuth by viewModel.youtubeAuth.collectAsStateWithLifecycle()
    val youtubeLikedIds by viewModel.youtubeLikedIds.collectAsStateWithLifecycle()
    val youtubeLikedTracks by viewModel.youtubeLikedTracks.collectAsStateWithLifecycle()
    val youtubePlaylists by viewModel.youtubePlaylists.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val youtubeHistory by viewModel.youtubeHistory.collectAsStateWithLifecycle()
    // Sign-in gate + the personalised Home feed.
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    // One-time "Connect YouTube Music" prompt shown right after a Clerk sign-in.
    val youtubeConnectPrompt by viewModel.youtubeConnectPrompt.collectAsStateWithLifecycle()
    val youtubeConnectEmail by viewModel.youtubeConnectEmail.collectAsStateWithLifecycle()
    // Heart state reads the YouTube like table for remote tracks, local favorites otherwise.
    val mergedFavorites by remember(favorites, youtubeLikedIds) {
        derivedStateOf { favorites + youtubeLikedIds.map { YOUTUBE_TRACK_PREFIX + it } }
    }
    val likeTrack: (Track) -> Unit = remember(viewModel) {
        { track -> if (track.id.startsWith(YOUTUBE_TRACK_PREFIX)) viewModel.toggleYouTubeLike(track) else viewModel.toggleFavorite(track) }
    }
    var youtubeLoginOpen by rememberSaveable { mutableStateOf(false) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // Build the id lookup once per library change; track lookups are O(1) instead of a scan per frame.
    // Streamed tracks are merged in so Now Playing, lyrics, dock and the notification resolve them.
    val trackById = remember(tracks, remoteTracks) { tracks.associateBy { it.id } + remoteTracks }
    val currentTrack = remember(trackById, playbackTrackId) { playbackTrackId?.let(trackById::get) }
    val queuedTracks = remember(queueIds, trackById) { queueIds.mapNotNull(trackById::get) }
    val upNextTracks = remember(upNextIds, trackById) { upNextIds.mapNotNull(trackById::get) }
    // Glassmorphism is always dark; a saved Light preference only applies to the Normal theme.
    val dark = settings.themeStyle == ThemeStyle.GLASSMORPHIC || settings.theme == ThemeMode.DARK
    var folderPickerPending by rememberSaveable { mutableStateOf(false) }
    var permissionRequestPending by rememberSaveable { mutableStateOf(false) }

    // Shared dock state + the backdrop window the dock blurs (glass only, Android 12+).
    val dockScroll = rememberDockScrollState()
    val hazeState = rememberHazeState()
    val dockStyle = if (settings.themeStyle == ThemeStyle.GLASSMORPHIC) DockStyle.GLASS else DockStyle.SOLID
    // Glass dock tuning (Glassmorphism only; SOLID ignores it). Everything — tint, blur radius,
    // refraction, outlines, shadow scale — comes from ONE resolved style so the sliders, the
    // mini-player, cards and dialogs can never disagree.
    val clarity = settings.glassClarity.coerceIn(0, 100) / 100f
    val dockGlass = remember(settings, profile, performanceReason) {
        resolveDockGlass(settings, profile, performanceReason)
    }
    val glassOptics = remember(clarity, settings.glassOutlines, dockGlass) {
        GlassOptics(
            clarity = clarity,
            outlines = dockGlass.outlines,
            tintAlpha = dockGlass.tintAlpha,
            blurDp = dockGlass.blurDp,
            saturation = 1.12f + 0.25f * clarity,
        )
    }

    AuralisTheme(themeStyle = settings.themeStyle, darkTheme = dark, accentName = settings.accent, visualPerformance = profile.tier) {
        LyricsFontPreloader()
        SystemBarsAppearance(dark || settings.themeStyle == ThemeStyle.GLASSMORPHIC)

        // Sign-in gate. Only active when a Clerk publishable key was configured at build time, so a
        // keyless build behaves exactly like it did before authentication existed.
        if (auth.configured && !auth.signedIn) {
            com.auralis.app.ui.screens.LoginScreen(
                auth = auth,
                onGoogleSignIn = viewModel::signInWithGoogle,
                onRequestCode = viewModel::requestEmailCode,
                onVerifyCode = viewModel::verifyEmailCode,
                onEditEmail = viewModel::editEmail,
                onClearError = viewModel::clearAuthError,
            )
            return@AuralisTheme
        }

        val snackbarHost = remember { SnackbarHostState() }
        LaunchedEffect(viewModel) {
            viewModel.messages.collect { snackbarHost.showSnackbar(it) }
        }
        LaunchedEffect(playbackError) { playbackError?.let { snackbarHost.showSnackbar(it) } }
        LaunchedEffect(nowPlayingOpen, currentTrack?.id) { if (nowPlayingOpen && currentTrack == null) nowPlayingOpen = false }

        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            folderPickerPending = false
            if (uri != null) viewModel.addFolder(uri)
        }
        // Stable callbacks: kept across recompositions so screen content can skip unnecessary work.
        val addMusic = remember(folderPicker) {
            { if (!folderPickerPending) { folderPickerPending = true; folderPicker.launch(null) } }
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequestPending = false
            if (granted) { viewModel.onLibraryPermissionGranted(); viewModel.scanLibrary() }
            else AppLog.w("Permissions", "Audio library permission denied")
        }
        val scan = remember(viewModel, context, permission, scanPermission) {
            {
                if (viewModel.scanning.value) Unit
                else if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) viewModel.scanLibrary()
                else if (!permissionRequestPending) { permissionRequestPending = true; scanPermission.launch(permission) }
            }
        }
        val spotifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.importSpotify(uris)
        }
        val importSpotify = remember(spotifyPicker) { { spotifyPicker.launch(arrayOf("*/*")) } }

        // Shared settings callbacks for the hub and every sub-page (both themes).
        val settingsActions = SettingsActions(
            onUpdate = viewModel::updateSettings,
            onAddFolder = addMusic,
            onRemoveSource = viewModel::removeSource,
            onOpenEqualizer = { viewModel.push(Destination.Equalizer) },
            onOpenWrapped = { viewModel.push(Destination.Wrapped) },
            onOpenLogs = { viewModel.push(Destination.Logs) },
            onOpenPrivacyAbout = { viewModel.push(Destination.About) },
            onCheckForUpdates = viewModel::checkForUpdates,
            onYouTubeSignIn = { youtubeLoginOpen = true },
            onYouTubeSignOut = viewModel::signOutYouTube,
            onClearCache = viewModel::clearCache,
            onClearLyricsCache = viewModel::clearLyricsMetadataCache,
            onRemoveAllDownloads = viewModel::removeAllDownloads,
            onAutoFixTags = viewModel::autoFixTags,
            onMoveLyricsProvider = viewModel::moveLyricsProvider,
            onLyricsSettingsChanged = viewModel::onLyricsSettingsChanged,
            updateChecking = updateState.checking,
            updateStatus = updateState.status,
            updateAvailableVersion = updateState.available?.version,
            youtubeSignedIn = youtubeAuth?.signedIn == true,
            youtubeName = youtubeAuth?.name.orEmpty(),
        )

        BackHandler(enabled = nowPlayingOpen || nav.stack.isNotEmpty()) { if (nowPlayingOpen) nowPlayingOpen = false else viewModel.back() }

        val effectiveProfile = remember(profile, settings.reduceBlurAndEffects) {
            if (settings.reduceBlurAndEffects) {
                profile.copy(blurEnabled = false, ambientMotionEnabled = false)
            } else {
                profile
            }
        }
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalGlassOptics provides glassOptics,
            LocalDockGlassStyle provides dockGlass,
            LocalPerformanceProfile provides effectiveProfile,
            LocalPerformanceMode provides performanceMode,
            LocalPerformanceReason provides performanceReason,
            // Glass content draws edge-to-edge under the dock and reserves this inside its lists.
            LocalDockBottomInset provides if (dockStyle == DockStyle.GLASS) DockMetrics.GlassExpandedHeight + DockMetrics.GlassBottomMargin else 0.dp,
            LocalDownloadActions provides DownloadActions(downloadedIds, viewModel::downloadTrack, viewModel::deleteDownload),
            LocalArtistImages provides ArtistImageResolver { name, browseId, knownUrl -> viewModel.resolveArtistImage(name, browseId, knownUrl) },
        ) {
        AuralisBackdrop(themeStyle = settings.themeStyle, artColor = currentTrack?.artColor, tier = profile.tier) {
            if (nowPlayingOpen && currentTrack != null) {
                NowPlayingScreen(
                    track = currentTrack,
                    queue = queuedTracks,
                    upNext = upNextTracks,
                    availableTracks = tracks,
                    favorites = mergedFavorites,
                    playback = playbackState,
                    settings = settings,
                    lyrics = viewModel.lyrics.collectAsStateWithLifecycle().value,
                    lyricsLoading = viewModel.lyricsLoading.collectAsStateWithLifecycle().value,
                    lyricsAutoScroll = settings.lyricsAutoScroll,
                    lyricSizeSp = settings.lyricSizeSp,
                    lyricsOffsetMs = settings.lyricsOffsetMs + viewModel.lyricsOffsetMs.collectAsStateWithLifecycle().value,
                    onLoadLyrics = viewModel::loadLyrics,
                    onImportLyrics = viewModel::importLyrics,
                    onSaveLyrics = viewModel::saveLyrics,
                    onTryAnotherLyricsSource = viewModel::tryAnotherLyricsSource,
                    onRetryLyrics = viewModel::retryLyrics,
                    onAdjustLyricsOffset = { delta -> currentTrack?.let { viewModel.adjustTrackLyricsOffset(it, delta) } },
                    onResetLyricsOffset = { currentTrack?.let { viewModel.resetTrackLyricsOffset(it) } },
                    onBack = { nowPlayingOpen = false },
                    onPlayPause = viewModel::togglePlayback,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onSeek = viewModel::seekTo,
                    onSeekBy = viewModel::seekBy,
                    doubleTapSeekSeconds = settings.doubleTapSeekSeconds,
                    gesturesEnabled = settings.gestureSeekEnabled,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::cycleRepeat,
                    onFavorite = likeTrack,
                    onAddToQueue = viewModel::enqueue,
                    onRemoveFromQueue = { viewModel.removeQueueItem(it.id) },
                    onMoveQueueItem = { track, index -> viewModel.moveQueueItem(track.id, index) },
                    onPlayQueueItem = { viewModel.playQueueItem(it.id) },
                    onClearQueue = viewModel::clearQueue,
                    onStartSleepTimer = viewModel::startSleepTimer,
                    onCancelSleepTimer = viewModel::cancelSleepTimer,
                    onOpenEqualizer = { nowPlayingOpen = false; viewModel.push(Destination.Equalizer) },
                    onOpenWrapped = { nowPlayingOpen = false; viewModel.push(Destination.Wrapped) },
                    onOpenBackup = { nowPlayingOpen = false; viewModel.push(Destination.Backup) },
                    onEditMetadata = { nowPlayingOpen = false; viewModel.push(Destination.MetadataEditor(currentTrack.id)) },
                    onOpenAudioAnalysis = { id -> nowPlayingOpen = false; viewModel.push(Destination.AudioAnalysis(id)) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().nestedScroll(dockScroll)) {
                    // Everything the dock may blur sits in this pane. Normal (SOLID) never consumes
                    // the haze, so skip the capture layer there.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (dockStyle == DockStyle.GLASS && dockGlass.blurEnabled) Modifier.hazeSource(hazeState) else Modifier),
                    ) {
                        // App-wide chosen player background (Glass theme only): drawn once behind
                        // every pushed screen and tab, fixed while content scrolls, and included in
                        // the Haze capture so the dock/cards blur it.
                        if (settings.themeStyle == ThemeStyle.GLASSMORPHIC &&
                            settings.playerBackground != PlayerBackground.NONE
                        ) {
                            Crossfade(
                                targetState = settings.playerBackground,
                                animationSpec = tween(360),
                                modifier = Modifier.fillMaxSize(),
                                label = "appBackground",
                            ) { background -> PlayerBackgroundImage(background) }
                        }
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            // Glass Settings owns its own collapsing header + back chevron.
                            val glassSettings = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC &&
                                nav.top == Destination.Settings
                            if (!glassSettings) {
                                val glassYouTubeHome = dockStyle == DockStyle.GLASS &&
                                    nav.top == null && nav.tab == Tab.HOME && settings.youtubeIntegration
                                val headerTrailing: (@Composable () -> Unit)? = if (glassYouTubeHome) {
                                    {
                                        GlassAvatarButton(
                                            imageUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl),
                                            name = auth.account?.displayName?.ifBlank { null } ?: youtubeAuth?.name,
                                            onClick = { viewModel.push(Destination.Settings) },
                                        )
                                    }
                                } else null
                                TopBar(
                                    showBack = nav.top != null,
                                    onBack = viewModel::back,
                                    collapseProgress = { dockScroll.progress },
                                    trailing = headerTrailing,
                                )
                            }
                            Crossfade(
                                targetState = nav,
                                // Glass motion is spring-based; Normal keeps its original tween.
                                animationSpec = if (dockStyle == DockStyle.GLASS) {
                                    spring(dampingRatio = 0.86f, stiffness = 420f)
                                } else {
                                    androidx.compose.animation.core.tween(240)
                                },
                                // Glass draws edge-to-edge under the dock (lists reserve the inset);
                                // Normal keeps its opaque, padded-below-dock layout.
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (dockStyle == DockStyle.GLASS) Modifier else Modifier.dockBottomSpace(dockScroll, currentTrack != null)),
                                label = "screen",
                            ) { state ->
                                when (state.top) {
                                    Destination.Settings -> SettingsHubScreen(
                                        settings = settings,
                                        sources = sources,
                                        actions = settingsActions,
                                        onNavigate = viewModel::push,
                                        onBack = viewModel::back,
                                        onAddFolder = addMusic,
                                        onRemoveSource = viewModel::removeSource,
                                        clerkSignedIn = auth.signedIn,
                                        clerkName = auth.account?.displayName.orEmpty(),
                                        clerkEmail = auth.account?.email.orEmpty(),
                                        accountImageUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl),
                                        onClerkSignOut = viewModel::signOutClerk,
                                    )
                                    Destination.SettingsPlayback,
                                    Destination.SettingsMetadataLyrics,
                                    Destination.SettingsDownloads,
                                    Destination.SettingsYouTubeMusic,
                                    Destination.SettingsAppearance,
                                    Destination.SettingsPerformance,
                                    Destination.SettingsStorage,
                                    Destination.SettingsAbout,
                                    -> SettingsSubScreen(
                                        destination = state.top!!,
                                        settings = settings,
                                        sources = sources,
                                        actions = settingsActions,
                                        onBack = viewModel::back,
                                    )
                                    Destination.About -> AboutScreen()
                                    Destination.Equalizer -> EqualizerScreen(settings, viewModel::updateSettings)
                                    Destination.Wrapped -> {
                                        val events by viewModel.playEvents.collectAsStateWithLifecycle()
                                        WrappedScreen(events, viewModel::wrappedStats)
                                    }
                                    Destination.Backup -> BackupScreen(viewModel::exportBackup, viewModel::restoreBackup)
                                    Destination.History -> HistoryScreen(
                                        entries = youtubeHistory,
                                        currentId = playbackTrackId,
                                        onPlay = viewModel::playHistoryEntry,
                                        onClear = viewModel::clearHistory,
                                    )
                                    Destination.Logs -> LogsDiagnosticsScreen(settings, sources, currentTrack)
                                    is Destination.MetadataEditor -> {
                                        val destination = state.top as Destination.MetadataEditor
                                        val track = trackById[destination.trackId]
                                        if (track == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("This song is not in the library anymore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            val candidates = viewModel.metadataCandidates.collectAsStateWithLifecycle().value
                                            val metadataLoading = viewModel.metadataLoading.collectAsStateWithLifecycle().value
                                            MetadataEditorScreen(
                                                track = track,
                                                readTags = viewModel::readTags,
                                                writeTags = viewModel::writeTags,
                                                onSaved = { viewModel.clearMetadataCandidates(); viewModel.back() },
                                                remote = track.isRemote,
                                                metadataCandidates = candidates,
                                                metadataLoading = metadataLoading,
                                                onFetchMetadata = viewModel::fetchMetadata,
                                                onClearCandidates = viewModel::clearMetadataCandidates,
                                                onSaveRemoteMetadata = viewModel::saveRemoteMetadataOverride,
                                            )
                                        }
                                    }
                                    is Destination.AudioAnalysis -> {
                                        val destination = state.top as Destination.AudioAnalysis
                                        val track = tracks.firstOrNull { it.id == destination.trackId }
                                        if (track == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("This song is not in the library anymore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else AudioAnalysisScreen(track)
                                    }
                                    is Destination.PlaylistDetail -> {
                                        val destination = state.top as Destination.PlaylistDetail
                                        PlaylistDetailScreen(
                                            playlistName = playlists.firstOrNull { it.id == destination.playlistId }?.name ?: "Playlist",
                                            items = viewModel.playlistItems(destination.playlistId),
                                            currentId = playbackTrackId,
                                            onPlayIndex = { index -> viewModel.playPlaylist(destination.playlistId, index) },
                                            onRemove = { item -> viewModel.removePlaylistItem(destination.playlistId, item) },
                                            onMove = { item, delta -> viewModel.movePlaylistItem(destination.playlistId, item, delta) },
                                            onRename = { newName -> viewModel.renamePlaylist(destination.playlistId, newName) },
                                            onDelete = { viewModel.deletePlaylist(destination.playlistId); viewModel.back() },
                                        )
                                    }
                                    is Destination.SpotifyImport -> {
                                        val destination = state.top as Destination.SpotifyImport
                                        val review by viewModel.observeImportReview(destination.importId)
                                            .collectAsStateWithLifecycle(initialValue = emptyList())
                                        SpotifyImportScreen(
                                            importRow = spotifyImports.firstOrNull { it.id == destination.importId },
                                            reviewItems = review,
                                            candidatesOf = viewModel::decodeCandidates,
                                            onPick = { item, candidate -> viewModel.resolveImportItem(destination.importId, item.id, candidate) },
                                            onSkip = { item -> viewModel.resolveImportItem(destination.importId, item.id, null) },
                                            onRetry = { item -> viewModel.retryImportItem(destination.importId, item) },
                                            onOpenPlaylist = { id -> viewModel.push(Destination.PlaylistDetail(id)) },
                                        )
                                    }
                                    null -> when (state.tab) {
                                        Tab.HOME -> HomeScreen(sources = sources, tracks = tracks, favorites = mergedFavorites, currentId = playbackTrackId, onAddMusic = addMusic, onScan = scan, onPlay = { viewModel.play(it, tracks) }, onFavorite = likeTrack, playlists = playlists, onAddToPlaylist = viewModel::addToPlaylist, onQueue = viewModel::enqueue, scanning = scanning, onPlayNext = viewModel::playNext, onOpenSettings = { viewModel.push(Destination.Settings) }, settingsBadge = updateState.available != null, youtubeEnabled = settings.youtubeIntegration, home = home, youtubeAvatarUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl), youtubeAccountName = youtubeAuth?.name?.ifBlank { null } ?: auth.account?.displayName, youtubeSignedIn = youtubeAuth?.signedIn == true, onOpenHome = { viewModel.loadHome() }, onRefreshHome = viewModel::refreshYouTubeHome, onSelectHomeCategory = viewModel::selectHomeCategory, onPlayYtTrack = viewModel::playYouTubeTrack, onOpenYtCard = viewModel::openHomeCard, onLikeYtTrack = viewModel::toggleYouTubeLike, onLoadMoreHomeRow = viewModel::loadMoreHomeRow, onCloseHomeCollection = viewModel::closeHomeCollection, onYouTubeSignIn = { youtubeLoginOpen = true }, onYouTubeSignOut = viewModel::signOutYouTube)
                                        Tab.SEARCH -> SearchScreen(
                                            tracks, mergedFavorites, playbackTrackId, { viewModel.play(it, tracks) }, likeTrack, playlists, viewModel::addToPlaylist,
                                            onQueue = viewModel::enqueue, sources = sources, onPlayNext = viewModel::playNext,
                                            youtubeResults = youtubeSearch.results,
                                            youtubeLoading = youtubeSearch.loading,
                                            youtubeError = youtubeSearch.error,
                                            youtubeResolvingId = youtubeResolvingId,
                                            onSearchYouTube = viewModel::searchYouTube,
                                            onPlayYouTube = viewModel::playYouTube,
                                        )
                                        Tab.LIBRARY -> LibraryScreen(
                                            tracks, mergedFavorites, sources, playlists, playbackTrackId, addMusic, scan,
                                            { viewModel.play(it, tracks) }, likeTrack, viewModel::createPlaylist, viewModel::addToPlaylist,
                                            onQueue = viewModel::enqueue,
                                            onPlayNext = viewModel::playNext,
                                            onPlayAlbum = { album -> album.firstOrNull()?.let { viewModel.play(it, album) } },
                                            onQueueAlbum = viewModel::enqueueAll,
                                            onQueuePlaylist = viewModel::enqueuePlaylist,
                                            scanning = scanning,
                                            onOpenSettings = { viewModel.push(Destination.Settings) },
                                            youtubeEnabled = settings.youtubeIntegration,
                                            youtubeLiked = youtubeLikedTracks,
                                            youtubePlaylists = youtubePlaylists,
                                            onPlayYouTubeTrack = viewModel::playYouTubeTrack,
                                            onOpenYouTubePlaylist = viewModel::openYouTubePlaylist,
                                            onRefreshYouTubeLibrary = viewModel::refreshYouTubeLibrary,
                                            downloads = downloads,
                                            onOpenHistory = { viewModel.push(Destination.History) },
                                            onOpenPlaylist = { id -> viewModel.push(Destination.PlaylistDetail(id)) },
                                            onImportSpotify = importSpotify,
                                            spotifyImports = spotifyImports,
                                            onOpenImport = { id -> viewModel.push(Destination.SpotifyImport(id)) },
                                            onAutoFixTags = viewModel::autoFixTags,
                                        )
                                        Tab.EQUALIZER -> EqualizerScreen(settings, viewModel::updateSettings)
                                        Tab.SETTINGS -> SettingsHubScreen(
                                            settings = settings,
                                            sources = sources,
                                            actions = settingsActions,
                                            onNavigate = viewModel::push,
                                            onBack = viewModel::back,
                                            onAddFolder = addMusic,
                                            onRemoveSource = viewModel::removeSource,
                                            clerkSignedIn = auth.signedIn,
                                            clerkName = auth.account?.displayName.orEmpty(),
                                            clerkEmail = auth.account?.email.orEmpty(),
                                            accountImageUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl),
                                            onClerkSignOut = viewModel::signOutClerk,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHost,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            // Read the dock spring in the layout phase: no recomposition per frame.
                            .offset {
                                val dockSpace = if (dockStyle == DockStyle.GLASS) {
                                    DockMetrics.glassHeight(currentTrack != null, dockScroll.progress)
                                } else {
                                    DockMetrics.height(currentTrack != null, dockScroll.progress)
                                }
                                val margin = if (dockStyle == DockStyle.GLASS) DockMetrics.GlassBottomMargin else DockMetrics.BottomMargin
                                val space = dockSpace + margin + 8.dp
                                IntOffset(0, -space.roundToPx())
                            },
                    )

                    // THE dock: one shared component for both themes.
                    AppDock(
                        style = dockStyle,
                        progress = { dockScroll.progress },
                        hasTrack = currentTrack != null,
                        selected = if (nav.top == null) nav.tab else null,
                        track = currentTrack,
                        playing = isPlaying,
                        onSelectTab = viewModel::selectTab,
                        onTogglePlay = viewModel::togglePlayback,
                        onNext = viewModel::next,
                        onOpenNowPlaying = { nowPlayingOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = if (dockStyle == DockStyle.GLASS) DockMetrics.GlassBottomMargin else DockMetrics.BottomMargin),
                        hazeState = hazeState,
                        onExpand = { if (dockStyle == DockStyle.GLASS) dockScroll.expand() },
                        showSettingsBadge = updateState.available != null,
                        artworkUri = playbackState.artworkUri,
                    )
                }
            }

            if (nowPlayingOpen) {
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
                )
            }

            // Global update prompt: hosted at the root so it appears over every screen and in
            // both theme styles, independent of which tab or pushed destination is showing.
            if (updateState.showDialog) {
                UpdateDialog(
                    state = updateState,
                    glass = settings.themeStyle == ThemeStyle.GLASSMORPHIC,
                    onUpdate = viewModel::downloadUpdate,
                    onInstall = viewModel::installUpdate,
                    onCancelDownload = viewModel::cancelUpdateDownload,
                    onLater = viewModel::dismissUpdate,
                    onSkip = viewModel::skipUpdateVersion,
                    onOpenPage = viewModel::openUpdatePage,
                )
            }

            if (youtubeConnectPrompt) {
                ConnectYouTubePrompt(
                    email = youtubeConnectEmail,
                    onConnect = {
                        viewModel.dismissYouTubeConnectPrompt(dontAskAgain = false)
                        youtubeLoginOpen = true
                    },
                    onNotNow = { viewModel.dismissYouTubeConnectPrompt(dontAskAgain = false) },
                    onDontAskAgain = { viewModel.dismissYouTubeConnectPrompt(dontAskAgain = true) },
                )
            }

            if (youtubeLoginOpen) {
                com.auralis.app.ui.screens.YouTubeLoginDialog(
                    onSession = viewModel::saveYouTubeSession,
                    onDismiss = { youtubeLoginOpen = false },
                    loginHint = youtubeConnectEmail ?: auth.account?.email,
                )
            }
        }
        }
    }
}

/** One-time post-sign-in prompt offering the YouTube Music hand-off (Part D). */
@Composable
private fun ConnectYouTubePrompt(
    email: String?,
    onConnect: () -> Unit,
    onNotNow: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Connect YouTube Music") },
        text = {
            Text(
                buildString {
                    append("Sign in to YouTube Music to see your liked songs, playlists and history in AURALIS.")
                    if (!email.isNullOrBlank()) {
                        append(" We'll prefill ")
                        append(email)
                        append(" on the Google sign-in page.")
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = onConnect) { Text("Connect") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNotNow) { Text("Not now") }
                TextButton(onClick = onDontAskAgain) { Text("Don't ask again") }
            }
        },
    )
}

/**
 * App top bar. The settings entry lives in the dock now, so the top-right icon/button is gone.
 * The large "AURALIS" wordmark collapses into a small top-bar title in sync with the dock.
 */
@Composable
private fun TopBar(
    showBack: Boolean,
    onBack: () -> Unit,
    collapseProgress: () -> Float,
    trailing: (@Composable () -> Unit)? = null,
) {
    val progress = collapseProgress()
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) { GlassTopBar(showBack, onBack, progress, trailing); return }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(lerp(64.dp, 52.dp, progress))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.ic_logo_header),
                contentDescription = "AURALIS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(lerp(38.dp, 30.dp, progress))
                    .graphicsLayer {
                        val scale = 1f - 0.10f * progress
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        Box(modifier = Modifier.weight(1f))
    }
}

/**
 * Reserves the animated dock height below Normal-theme content without reading the dock spring in
 * composition. The height is resolved during measurement, so scrolling stays on one frame budget.
 */
private fun Modifier.dockBottomSpace(scroll: DockScrollState, hasTrack: Boolean): Modifier = layout { measurable, constraints ->
    val space = (DockMetrics.height(hasTrack, scroll.progress) + DockMetrics.BottomMargin).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = 0,
            maxHeight = (constraints.maxHeight - space).coerceAtLeast(0),
        ),
    )
    layout(placeable.width, placeable.height + space) {
        placeable.placeRelative(0, 0)
    }
}

/** Keeps status/navigation bar icons readable against the in-app theme, not the system theme. */
@Composable
private fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
