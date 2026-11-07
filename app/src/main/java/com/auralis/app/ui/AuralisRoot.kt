package com.auralis.app.ui

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.AuralisTheme
import com.auralis.app.ui.theme.GlassOptics
import com.auralis.app.ui.theme.LyricsFontPreloader
import com.auralis.app.ui.theme.LocalGlassOptics
import com.auralis.app.ui.components.AuralisBackdrop
import com.auralis.app.ui.glass.GlassTopBar
import com.auralis.app.ui.glass.LocalDockGlassStyle
import com.auralis.app.ui.glass.resolveDockGlass
import com.auralis.app.ui.components.AppDock
import com.auralis.app.ui.components.DockMetrics
import com.auralis.app.ui.components.DockScrollState
import com.auralis.app.ui.components.DockStyle
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.components.rememberDockScrollState

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.screens.AboutScreen
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.screens.HomeScreen
import com.auralis.app.ui.screens.LibraryScreen
import com.auralis.app.ui.screens.NowPlayingScreen
import com.auralis.app.ui.screens.SearchScreen
import com.auralis.app.ui.screens.SettingsScreen
import com.auralis.app.ui.screens.EqualizerScreen
import com.auralis.app.ui.screens.VisualizerScreen
import com.auralis.app.ui.screens.WrappedScreen
import com.auralis.app.ui.screens.BackupScreen
import com.auralis.app.ui.screens.MetadataEditorScreen
import com.auralis.app.ui.theme.AuralisAppTheme
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.ui.performance.LocalPerformanceMode
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.components.glassSurface
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
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // Build the id lookup once per library change; track lookups are O(1) instead of a scan per frame.
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val currentTrack = remember(trackById, playbackTrackId) { playbackTrackId?.let(trackById::get) }
    val queuedTracks = remember(queueIds, trackById) { queueIds.mapNotNull(trackById::get) }
    val upNextTracks = remember(upNextIds, trackById) { upNextIds.mapNotNull(trackById::get) }
    val dark = settings.theme == ThemeMode.DARK
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

        val snackbarHost = remember { SnackbarHostState() }
        LaunchedEffect(viewModel) {
            viewModel.messages.collect { snackbarHost.showSnackbar(it) }
        }
        LaunchedEffect(playbackError) { playbackError?.let { snackbarHost.showSnackbar(it) } }
        LaunchedEffect(nowPlayingOpen, currentTrack?.id) { if (nowPlayingOpen && currentTrack == null) nowPlayingOpen = false }
        // Auto-fetch: whenever the playing track changes, resolve cached/embedded/sidecar lyrics
        // even if the Now Playing screen has never been opened. loadLyrics() is idempotent per track.
        LaunchedEffect(currentTrack?.id) { currentTrack?.let(viewModel::loadLyrics) }

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
        }
        val scan = remember(viewModel, context, permission, scanPermission) {
            {
                if (viewModel.scanning.value) Unit
                else if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) viewModel.scanLibrary()
                else if (!permissionRequestPending) { permissionRequestPending = true; scanPermission.launch(permission) }
            }
        }

        BackHandler(enabled = nowPlayingOpen || nav.stack.isNotEmpty()) { if (nowPlayingOpen) nowPlayingOpen = false else viewModel.back() }

        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalGlassOptics provides glassOptics,
            LocalDockGlassStyle provides dockGlass,
            LocalPerformanceProfile provides profile,
            LocalPerformanceMode provides performanceMode,
            LocalPerformanceReason provides performanceReason,
            // Glass content draws edge-to-edge under the dock and reserves this inside its lists.
            LocalDockBottomInset provides if (dockStyle == DockStyle.GLASS) DockMetrics.ExpandedHeight + DockMetrics.GlassBottomMargin else 0.dp,
        ) {
        AuralisBackdrop(themeStyle = settings.themeStyle, artColor = currentTrack?.artColor, tier = profile.tier) {
            if (nowPlayingOpen && currentTrack != null) {
                NowPlayingScreen(
                    track = currentTrack,
                    queue = queuedTracks,
                    upNext = upNextTracks,
                    availableTracks = tracks,
                    favorites = favorites,
                    playback = playbackState,
                    lyrics = viewModel.lyrics.collectAsStateWithLifecycle().value,
                    lyricsLoading = viewModel.lyricsLoading.collectAsStateWithLifecycle().value,
                    lyricsAutoScroll = settings.lyricsAutoScroll,
                    lyricSizeSp = settings.lyricSizeSp,
                    lyricsOffsetMs = settings.lyricsOffsetMs,
                    onLoadLyrics = viewModel::loadLyrics,
                    onImportLyrics = viewModel::importLyrics,
                    onSaveLyrics = viewModel::saveLyrics,
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
                    onFavorite = viewModel::toggleFavorite,
                    onAddToQueue = viewModel::enqueue,
                    onRemoveFromQueue = { viewModel.removeQueueItem(it.id) },
                    onMoveQueueItem = { track, index -> viewModel.moveQueueItem(track.id, index) },
                    onPlayQueueItem = { viewModel.playQueueItem(it.id) },
                    onClearQueue = viewModel::clearQueue,
                    onStartSleepTimer = viewModel::startSleepTimer,
                    onCancelSleepTimer = viewModel::cancelSleepTimer,
                    onOpenEqualizer = { nowPlayingOpen = false; viewModel.push(Destination.Equalizer) },
                    onOpenVisualizer = { nowPlayingOpen = false; viewModel.push(Destination.Visualizer) },
                    onOpenWrapped = { nowPlayingOpen = false; viewModel.push(Destination.Wrapped) },
                    onOpenBackup = { nowPlayingOpen = false; viewModel.push(Destination.Backup) },
                    onEditMetadata = { nowPlayingOpen = false; viewModel.push(Destination.MetadataEditor(currentTrack.id)) },
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
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            // Glass Settings owns its own collapsing header + back chevron.
                            val glassSettings = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC &&
                                nav.top == Destination.Settings
                            if (!glassSettings) {
                                TopBar(
                                    showBack = nav.top != null,
                                    onBack = viewModel::back,
                                    collapseProgress = { dockScroll.progress },
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
                                    Destination.Settings -> SettingsScreen(
                                        settings = settings,
                                        sources = sources,
                                        onUpdate = viewModel::updateSettings,
                                        onAddFolder = addMusic,
                                        onRemoveSource = viewModel::removeSource,
                                        onOpenAbout = { viewModel.push(Destination.About) },
                                        onOpenEqualizer = { viewModel.push(Destination.Equalizer) },
                                        onOpenVisualizer = { viewModel.push(Destination.Visualizer) },
                                        onOpenWrapped = { viewModel.push(Destination.Wrapped) },
                                        onOpenBackup = { viewModel.push(Destination.Backup) },
                                        onBack = viewModel::back,
                                    )
                                    Destination.About -> AboutScreen()
                                    Destination.Equalizer -> EqualizerScreen(settings, viewModel::updateSettings)
                                    Destination.Visualizer -> VisualizerScreen()
                                    Destination.Wrapped -> {
                                        val events by viewModel.playEvents.collectAsStateWithLifecycle()
                                        WrappedScreen(events, viewModel::wrappedStats)
                                    }
                                    Destination.Backup -> BackupScreen(viewModel::exportBackup, viewModel::restoreBackup)
                                    is Destination.MetadataEditor -> {
                                        val destination = state.top as Destination.MetadataEditor
                                        val track = tracks.firstOrNull { it.id == destination.trackId }
                                        if (track == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("This song is not in the library anymore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else MetadataEditorScreen(
                                            track = track,
                                            readTags = viewModel::readTags,
                                            writeTags = viewModel::writeTags,
                                            onSaved = { viewModel.back() },
                                        )
                                    }
                                    null -> when (state.tab) {
                                        Tab.HOME -> HomeScreen(sources = sources, tracks = tracks, favorites = favorites, currentId = playbackTrackId, onAddMusic = addMusic, onScan = scan, onPlay = { viewModel.play(it, tracks) }, onFavorite = viewModel::toggleFavorite, playlists = playlists, onAddToPlaylist = viewModel::addToPlaylist, onQueue = viewModel::enqueue, scanning = scanning, onPlayNext = viewModel::playNext, onOpenSettings = { viewModel.push(Destination.Settings) })
                                        Tab.SEARCH -> SearchScreen(tracks, favorites, playbackTrackId, { viewModel.play(it, tracks) }, viewModel::toggleFavorite, playlists, viewModel::addToPlaylist, onQueue = viewModel::enqueue, sources = sources, onPlayNext = viewModel::playNext)
                                        Tab.LIBRARY -> LibraryScreen(
                                            tracks, favorites, sources, playlists, playbackTrackId, addMusic, scan,
                                            { viewModel.play(it, tracks) }, viewModel::toggleFavorite, viewModel::createPlaylist, viewModel::addToPlaylist,
                                            onQueue = viewModel::enqueue,
                                            onPlayNext = viewModel::playNext,
                                            onPlayAlbum = { album -> album.firstOrNull()?.let { viewModel.play(it, album) } },
                                            onQueueAlbum = viewModel::enqueueAll,
                                            onQueuePlaylist = viewModel::enqueuePlaylist,
                                            scanning = scanning,
                                        )
                                        Tab.EQUALIZER -> EqualizerScreen(settings, viewModel::updateSettings)
                                        Tab.SETTINGS -> SettingsScreen(
                                            settings = settings,
                                            sources = sources,
                                            onUpdate = viewModel::updateSettings,
                                            onAddFolder = addMusic,
                                            onRemoveSource = viewModel::removeSource,
                                            onOpenAbout = { viewModel.push(Destination.About) },
                                            onOpenEqualizer = { viewModel.push(Destination.Equalizer) },
                                            onOpenVisualizer = { viewModel.push(Destination.Visualizer) },
                                            onOpenWrapped = { viewModel.push(Destination.Wrapped) },
                                            onOpenBackup = { viewModel.push(Destination.Backup) },
                                            onBack = viewModel::back,
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
                                val space = DockMetrics.height(currentTrack != null, dockScroll.progress) + DockMetrics.BottomMargin + 8.dp
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
        }
        }
    }
}

/**
 * App top bar. The settings entry lives in the dock now, so the top-right icon/button is gone.
 * The large "AURALIS" wordmark collapses into a small top-bar title in sync with the dock.
 */
@Composable
private fun TopBar(showBack: Boolean, onBack: () -> Unit, collapseProgress: () -> Float) {
    val progress = collapseProgress()
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) { GlassTopBar(showBack, onBack, progress); return }
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
            Text(
                text = "AURALIS",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = lerp(24.sp, 16.sp, progress),
                    letterSpacing = lerp(4.sp, 2.sp, progress),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
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
