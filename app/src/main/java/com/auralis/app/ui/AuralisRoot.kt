package com.auralis.app.ui

import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.AuralisTheme
import com.auralis.app.ui.theme.LyricsFontPreloader
import com.auralis.app.ui.lyrics.LocalLyricsFontFamily
import com.auralis.app.ui.lyrics.LocalLyricsFontResolver
import com.auralis.app.ui.lyrics.createAndroidLyricsFontResolver
import com.auralis.app.ui.components.AuralisBackdrop
import com.auralis.app.ui.glass.GlassTopBar
import com.auralis.app.ui.glass.GlassAlertDialog
import com.auralis.app.ui.glass.GlassAvatarButton
import com.auralis.app.ui.glass.GlassBlurPolicy
import com.auralis.app.ui.glass.LocalDockGlassStyle
import com.auralis.app.ui.glass.LocalGlassBlurAllowed
import com.auralis.app.ui.glass.GlassOverlayScrim
import com.auralis.app.ui.glass.GlassOverlayState
import com.auralis.app.ui.glass.LocalGlassOverlay
import com.auralis.app.ui.glass.GlassPortalHost
import com.auralis.app.ui.glass.GlassPortalState
import com.auralis.app.ui.glass.LocalGlassPortal
import com.auralis.app.ui.glass.GlassConfig
import com.auralis.app.ui.glass.GlassConfigPreview
import com.auralis.app.ui.glass.GlassDragState
import com.auralis.app.ui.glass.GlassSettings
import com.auralis.app.ui.glass.LocalAppBackdrop
import com.auralis.app.ui.glass.LocalGlassConfig
import com.auralis.app.ui.glass.LocalGlassDrag
import com.auralis.app.ui.glass.LocalGlassSettings
import com.auralis.app.ui.glass.OpaqueLayerOnDraw
import com.auralis.app.glass.backdrop.backdrops.layerBackdrop
import com.auralis.app.glass.backdrop.backdrops.rememberLayerBackdrop
import com.auralis.app.ui.glass.resolveDockGlass
import com.auralis.app.ui.glass.rememberReduceMotion
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
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.auralis.app.ui.theme.AuralisMotion
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.auralis.app.ui.components.AuralisLogo
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.nav.isSettingsDestination
import com.auralis.app.ui.screens.AboutScreen
import com.auralis.app.ui.settings.SettingsActions
import com.auralis.app.ui.settings.SettingsHubScreen
import com.auralis.app.ui.settings.SettingsSubScreen
import com.auralis.app.ui.settings.historySyncSummary
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.screens.HomeScreen
import com.auralis.app.ui.screens.ExploreScreen
import com.auralis.app.ui.screens.ExploreMoodScreen
import com.auralis.app.ui.explore.ExploreMoods
import com.auralis.app.ui.screens.HistoryScreen
import com.auralis.app.ui.screens.LibraryScreen
import com.auralis.app.ui.metadata.MetadataFillEffect
import com.auralis.app.data.youtube.needsMetadataFill
import com.auralis.app.ui.screens.LibrarySortOption
import com.auralis.app.ui.screens.buildLibraryPlaylists
import com.auralis.app.ui.screens.NowPlayingScreen
import com.auralis.app.ui.screens.PlaylistDetailScreen
import com.auralis.app.ui.screens.YouTubePlaylistScreen
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
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.ui.performance.LocalPerformanceMode
import com.auralis.app.ui.performance.LocalDeviceConditions
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.performance.PerformanceProfile
import com.auralis.app.ui.components.glassSurface
import com.auralis.app.logging.AppLog
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

@Composable
fun AuralisRoot(viewModel: AppViewModel) {
    // One-time "AURALIS crashed last time" notice. Shown before the themed tree (and even while
    // the startup settings read is still pending) so a launch crash is never silently forgotten.
    val crashContext = androidx.compose.ui.platform.LocalContext.current
    var pendingCrash by remember { androidx.compose.runtime.mutableStateOf(com.auralis.app.logging.CrashHandler.pendingCrashReport(crashContext)) }
    if (pendingCrash != null) {
        AlertDialog(
            onDismissRequest = {
                pendingCrash?.let { com.auralis.app.logging.CrashHandler.acknowledge(crashContext, it) }
                pendingCrash = null
            },
            title = { Text("AURALIS crashed last time") },
            text = { Text("A crash report was saved on this device. Open it to copy or share the details before closing.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrash?.let { file ->
                        com.auralis.app.logging.CrashHandler.acknowledge(crashContext, file)
                        runCatching {
                            crashContext.startActivity(
                                Intent(crashContext, com.auralis.app.CrashReportActivity::class.java).apply {
                                    putExtra(com.auralis.app.CrashReportActivity.EXTRA_CRASH_FILE, file.absolutePath)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }
                    pendingCrash = null
                }) { Text("View report") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingCrash?.let { com.auralis.app.logging.CrashHandler.acknowledge(crashContext, it) }
                    pendingCrash = null
                }) { Text("Dismiss") }
            },
        )
    }
    // One authoritative startup read. The themed tree (and therefore Home, whose YouTube/local
    // branch depends on youtubeIntegration) is not composed until DataStore has answered once, so
    // the saved appearance applies to the very first frame instead of being applied on top of a
    // visible default Normal theme.
    val loadedSettings by viewModel.loadedSettings.collectAsStateWithLifecycle()
    val settings = loadedSettings
    if (settings == null) {
        StartupSurface()
        return
    }
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
    // the ONE glass config. The repository combines the stored transparency/clarity/
    // outlines with the resolved performance tier; provided below via LocalGlassConfig.
    val glassConfig by viewModel.glassConfig.collectAsStateWithLifecycle()
    val deviceConditions by viewModel.performanceConditions.collectAsStateWithLifecycle()
    // GitHub release state; drives the global dialog, the Settings row and the dock badge.
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    // YouTube source state: remote search results + the song currently being resolved.
    val youtubeSearch by viewModel.youtubeSearch.collectAsStateWithLifecycle()
    val youtubeResolvingId by viewModel.youtubeResolvingId.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteTracks.collectAsStateWithLifecycle()
    // The paused last-played song shown on launch, before the real player has a track.
    val restoredPlayback by viewModel.restoredPlayback.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val youtubeAuth by viewModel.youtubeAuth.collectAsStateWithLifecycle()
    val youtubeSessionReadError by viewModel.youtubeSessionReadError.collectAsStateWithLifecycle()
    val youtubeSessionSnapshot by viewModel.youtubeSessionSnapshot.collectAsStateWithLifecycle()
    val youtubeLikedIds by viewModel.youtubeLikedIds.collectAsStateWithLifecycle()
    val youtubeLikedTracks by viewModel.youtubeLikedTracks.collectAsStateWithLifecycle()
    val liveSyncUi by viewModel.liveSyncUi.collectAsStateWithLifecycle()
    val playbackReporting by viewModel.playbackReporting.collectAsStateWithLifecycle()
    val youtubePlaylists by viewModel.youtubePlaylists.collectAsStateWithLifecycle()
    val youtubeLibraryError by viewModel.youtubeLibraryError.collectAsStateWithLifecycle()
    val youtubeLibraryLoading by viewModel.youtubeLibraryLoading.collectAsStateWithLifecycle()
    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val youTubeTestLines by viewModel.youTubeTestLines.collectAsStateWithLifecycle()
    val youTubeTestRunning by viewModel.youTubeTestRunning.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val youtubeHistory by viewModel.youtubeHistory.collectAsStateWithLifecycle()
    val metadataSettled by viewModel.metadataSettled.collectAsStateWithLifecycle()
                val mergedHistory by viewModel.mergedHistory.collectAsStateWithLifecycle()
                val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
                val ytPlaylist by viewModel.ytPlaylist.collectAsStateWithLifecycle()
    // Sign-in gate + the personalised Home feed.
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    // Explore tab: the opened mood's streaming songs.
    val explore by viewModel.explore.collectAsStateWithLifecycle()
    // One-time "Connect YouTube Music" prompt shown right after a Clerk sign-in.
    val youtubeConnectPrompt by viewModel.youtubeConnectPrompt.collectAsStateWithLifecycle()
    val youtubeConnectExpired by viewModel.youtubeConnectExpired.collectAsStateWithLifecycle()
    val youtubeConnectEmail by viewModel.youtubeConnectEmail.collectAsStateWithLifecycle()
    // Foreground is the second refresh trigger: only a six-hour-old session does any work and the
    // repository's backoff/lock prevents repeats.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshYouTubeSessionIfStale() }
    // Heart state reads the YouTube like table for remote tracks, local favorites otherwise.
    val mergedFavorites by remember(favorites, youtubeLikedIds) {
        derivedStateOf { favorites + youtubeLikedIds.map { YOUTUBE_TRACK_PREFIX + it } }
    }
    val likeTrack: (Track) -> Unit = remember(viewModel) {
        { track -> if (track.id.startsWith(YOUTUBE_TRACK_PREFIX)) viewModel.toggleYouTubeLike(track) else viewModel.toggleFavorite(track) }
    }
    var youtubeLoginOpen by rememberSaveable { mutableStateOf(false) }
    var youtubeForceSignIn by rememberSaveable { mutableStateOf(false) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // Build the id lookup once per library change; track lookups are O(1) instead of a scan per frame.
    // Streamed tracks are merged in so Now Playing, lyrics, dock and the notification resolve them.
    val trackById = remember(tracks, remoteTracks) { tracks.associateBy { it.id } + remoteTracks }
    // Prefer the real player; while it has no track, fall back to the paused launch placeholder.
    val currentTrack = remember(trackById, playbackTrackId, restoredPlayback) {
        playbackTrackId?.let(trackById::get) ?: restoredPlayback?.track
    }
    val queuedTracks = remember(queueIds, trackById, restoredPlayback) {
        if (queueIds.isNotEmpty()) queueIds.mapNotNull(trackById::get) else restoredPlayback?.queue.orEmpty()
    }
    val upNextTracks = remember(upNextIds, trackById, restoredPlayback) {
        if (upNextIds.isNotEmpty()) upNextIds.mapNotNull(trackById::get)
        else restoredPlayback?.let { it.queue.drop(it.index + 1) }.orEmpty()
    }
    // True while the restored placeholder owns the mini-player (the real player has no track yet).
    val showingRestored = playbackTrackId == null && restoredPlayback != null
    // What Now Playing reads: the real player once it owns a track, else the restored placeholder.
    val displayPlayback = remember(playbackState, restoredPlayback) {
        val restored = restoredPlayback
        if (playbackState.trackId != null || restored == null) playbackState
        else playbackState.copy(
            trackId = restored.track?.id,
            positionMs = restored.positionMs,
            durationMs = restored.durationMs,
            queueIds = restored.queue.map { it.id },
            upNextIds = restored.queue.drop(restored.index + 1).map { it.id },
            contextIds = restored.queue.map { it.id },
            autoplayIds = emptyList(),
            context = restored.context,
            currentIndex = restored.index,
            shuffle = restored.shuffle,
            repeat = restored.repeatMode,
            playing = false,
        )
    }
    // Glassmorphism is always dark; a saved Light preference only applies to the Normal theme.
    val dark = settings.themeStyle == ThemeStyle.GLASSMORPHIC || settings.theme == ThemeMode.DARK
    var folderPickerPending by rememberSaveable { mutableStateOf(false) }
    var permissionRequestPending by rememberSaveable { mutableStateOf(false) }

    // Shared dock state + the backdrop window every glass surface blurs (glass only, Android 12+).
    val dockScroll = rememberDockScrollState()
    // the ONE recorded layer the dock / mini-player / Search circle sample. It records an
    // opaque canvas first, then the wallpaper + every page (). Only Glassmorphic on API 31+
    // attaches it (`glassLayerEnabled`); Normal records nothing and pays nothing.
    val appBackdrop = rememberLayerBackdrop(onDraw = OpaqueLayerOnDraw)
    val glassLayerEnabled = settings.themeStyle == ThemeStyle.GLASSMORPHIC && glassConfig.isGlassCapable
    // log once at launch whether the backdrop layer is recording (and that it records an
    // opaque canvas first), so the Logs screen can explain a sharp-text-through-glass report.
    LaunchedEffect(glassLayerEnabled) {
        AppLog.i(
            "Glass",
            "backdrop recording=$glassLayerEnabled layer opaque=$glassLayerEnabled " +
                "theme=${settings.themeStyle} sdk=${Build.VERSION.SDK_INT}",
        )
    }
    // One shared counter for open glass menus; the root draws their dim layer.
    val glassOverlay = remember { GlassOverlayState() }
    val glassPortal = remember { GlassPortalState() }
    val dockStyle = if (settings.themeStyle == ThemeStyle.GLASSMORPHIC) DockStyle.GLASS else DockStyle.SOLID

    // Destination transitions keep the outgoing screen composed until the incoming screen has
    // produced its first frame; see the AnimatedContent transitionSpec below. The previous
    // "fade out, swap, fade in" sequence drove the content alpha to zero *before* the destination
    // was composed, so a slow first composition (Home feed, large Library, Glass blur capture)
    // left the window showing only the bare backdrop for that whole time.

    // The three Appearance controls, in ONE place. The committed values come from the
    // GlassConfigRepository (DataStore) via [glassConfig]; while a slider is dragged the Appearance
    // screen writes a transient GlassDragState so the whole app updates live, and only the drag end
    // is persisted through the repository.
    val glassDrag = remember { GlassDragState() }
    // Once DataStore catches up with the committed drag, drop the transient copy. The effect reads
    // the drag state only when it runs, so AuralisRoot does not subscribe to every slider tick.
    LaunchedEffect(glassConfig) {
        if (glassDrag.value == glassConfig) glassDrag.value = null
    }

    AuralisTheme(themeStyle = settings.themeStyle, darkTheme = dark, accentName = settings.accent, visualPerformance = profile.tier) {
        LyricsFontPreloader()
        SystemBarsAppearance(dark || settings.themeStyle == ThemeStyle.GLASSMORPHIC)

        // First run only: a short welcome screen ahead of the sign-in gate. Gated on the persisted
        // flag so it appears exactly once per install, and on "not already signed in" so an existing
        // user who updates the app can never be shown it again. Completing it records the visit and
        // the login screen takes over on the next frame.
        if (!settings.welcomeSeen && !auth.signedIn) {
            com.auralis.app.ui.screens.WelcomeScreen(onGetStarted = viewModel::completeWelcome)
            return@AuralisTheme
        }

        // Sign-in gate. When a Clerk publishable key was configured at build time, this shows the
        // login screen until a session exists. A keyless build shows a clear "sign-in isn't
        // configured" notice with a continue-without-an-account escape hatch, so local playback is
        // never blocked and auth is never silently skipped.
        if (!auth.signedIn && !auth.authBypassed) {
            if (auth.configured) {
                com.auralis.app.ui.screens.LoginScreen(
                    auth = auth,
                    onGoogleSignIn = viewModel::signInWithGoogle,
                    onRequestCode = viewModel::requestEmailCode,
                    onVerifyCode = viewModel::verifyEmailCode,
                    onEditEmail = viewModel::editEmail,
                    onClearError = viewModel::clearAuthError,
                    onRetryInit = viewModel::retryClerkInit,
                )
            } else {
                com.auralis.app.ui.screens.SignInUnavailable(
                    onContinue = viewModel::continueWithoutAccount,
                )
            }
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
        // Re-read on each recomposition: the launcher callback flips permissionRequestPending, so a
        // grant immediately updates the Library's inline "Allow access" card.
        val audioPermissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val requestAudioPermission = {
            if (!permissionRequestPending) { permissionRequestPending = true; scanPermission.launch(permission) }
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
            onYouTubeSignIn = { youtubeForceSignIn = false; youtubeLoginOpen = true },
            onYouTubeSignOut = viewModel::signOutYouTube,
            onYouTubeDifferentAccount = {
                viewModel.switchYouTubeAccount()
                youtubeForceSignIn = true
                youtubeLoginOpen = true
            },
            onClearCache = viewModel::clearCache,
            onClearLyricsCache = viewModel::clearLyricsMetadataCache,
            onRemoveAllDownloads = viewModel::removeAllDownloads,
            onAutoFixTags = viewModel::autoFixTags,
            onMoveLyricsProvider = viewModel::moveLyricsProvider,
            onResetLyricsProviders = viewModel::resetLyricsProviders,
            onLyricsSettingsChanged = viewModel::onLyricsSettingsChanged,
            updateChecking = updateState.checking,
            updateStatus = updateState.status,
            updateAvailableVersion = updateState.available?.version,
            youtubeSignedIn = youtubeAuth?.signedIn == true,
            youtubeName = youtubeAuth?.name.orEmpty(),
            youtubeExpired = youtubeAuth?.expired == true,
            youtubeReadError = youtubeSessionReadError,
            youtubeLastRefreshedAt = youtubeAuth?.lastRefreshedAt ?: 0L,
            onYouTubeHeal = viewModel::healYouTubeSession,
            onYouTubeTest = viewModel::testYouTubeConnection,
            youTubeTestRunning = youTubeTestRunning,
            youTubeTestLines = youTubeTestLines,
            liveSyncEnabled = settings.liveSyncEnabled,
            liveSyncState = liveSyncUi.state,
            liveSyncLastSyncedAt = liveSyncUi.lastSyncedAt,
            onToggleLiveSync = viewModel::setLiveSyncEnabled,
            onSyncLiveNow = viewModel::syncLiveNow,
            playbackReporting = playbackReporting,
            onDockTransparencyChange = viewModel::setDockTransparency,
            onGlassClarityChange = viewModel::setGlassClarity,
            onPreviewDockTransparency = viewModel::previewDockTransparency,
            onPreviewGlassClarity = viewModel::previewGlassClarity,
            onClearGlassPreview = viewModel::clearGlassPreview,
            onGlassOutlinesChange = viewModel::setGlassOutlines,
        )

        BackHandler(enabled = nowPlayingOpen || nav.stack.isNotEmpty()) { if (nowPlayingOpen) nowPlayingOpen = false else viewModel.back() }

        val effectiveProfile = remember(profile, settings.reduceBlurAndEffects) {
            if (settings.reduceBlurAndEffects) {
                profile.copy(blurEnabled = false, ambientMotionEnabled = false)
            } else {
                profile
            }
        }
        // Navigation transition multiplier from the performance mode (and "Remove animations"):
        // full speed normally, a touch quicker on the Low profile, and a hard cut when the system
        // animator scale is 0. The destination fade itself is a fixed fade-through shape.
        val transitionScale = when {
            rememberReduceMotion() -> 0f
            effectiveProfile.isLow -> 0.6f
            else -> 1f
        }
        // the recorded layer is attached in exactly two mutually exclusive places (the
        // page container and the full player). It is recorded whenever the theme is Glassmorphic
        // and the device can blur (Android 12+); the Normal theme records nothing and pays nothing.
        GlassSettingsProvider(
            drag = glassDrag,
            persisted = glassConfig,
            preview = viewModel.glassConfigPreview,
            onPreviewPersisted = viewModel::clearGlassPreviewIfPersisted,
            settings = settings,
            profile = profile,
            performanceReason = performanceReason,
        ) {
        val lyricsFontResolver = remember { createAndroidLyricsFontResolver() }
        val activeLyricsFont = lyricsFontResolver(settings.lyricsSettings.fontChoice)
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalLyricsFontResolver provides lyricsFontResolver,
            LocalLyricsFontFamily provides activeLyricsFont,
            LocalGlassDrag provides glassDrag,
            LocalGlassOverlay provides glassOverlay,
            LocalGlassPortal provides if (glassLayerEnabled) glassPortal else null,
            LocalAppBackdrop provides if (glassLayerEnabled) appBackdrop else null,
            LocalPerformanceProfile provides effectiveProfile,
            LocalPerformanceMode provides performanceMode,
            LocalPerformanceReason provides performanceReason,
            LocalDeviceConditions provides deviceConditions,
            // Glass content draws edge-to-edge under the dock and reserves this inside its lists.
            LocalDockBottomInset provides if (dockStyle == DockStyle.GLASS) DockMetrics.GlassExpandedHeight + DockMetrics.GlassBottomMargin else 0.dp,
            LocalDownloadActions provides DownloadActions(downloadedIds, viewModel::downloadTrack, viewModel::deleteDownload),
            LocalArtistImages provides ArtistImageResolver { name, browseId, knownUrl -> viewModel.resolveArtistImage(name, browseId, knownUrl) },
        ) {
        AuralisBackdrop(themeStyle = settings.themeStyle, artColor = currentTrack?.artColor, tier = profile.tier) {
            // The playing song is on screen in the dock on every page, so its retries follow this
            // composition and stop when the app leaves the foreground.
            MetadataFillEffect(
                targets = listOfNotNull(currentTrack).filter { it.needsMetadataFill },
                onVisible = viewModel::requestMetadata,
                onHidden = viewModel::releaseMetadata,
            )
            if (nowPlayingOpen && currentTrack != null) {
                // The player is recorded into this backdrop. Its own glass surfaces must not sample
                // the layer they are recording; the dock remains outside and can sample it safely.
                CompositionLocalProvider(LocalAppBackdrop provides null) {
                NowPlayingScreen(
                    track = currentTrack,
                    queue = queuedTracks,
                    upNext = upNextTracks,
                    availableTracks = tracks,
                    favorites = mergedFavorites,
                    playback = displayPlayback,
                    settings = settings,
                    lyrics = viewModel.lyrics.collectAsStateWithLifecycle().value,
                    lyricsLoading = viewModel.lyricsLoading.collectAsStateWithLifecycle().value,
                    lyricsProviderRowStates = viewModel.lyricsProviderRowStates.collectAsStateWithLifecycle().value,
                    lyricsAutoScroll = settings.lyricsAutoScroll,
                    lyricSizeSp = settings.lyricSizeSp,
                    lyricsOffsetMs = settings.lyricsOffsetMs + viewModel.lyricsOffsetMs.collectAsStateWithLifecycle().value,
                    onLoadLyrics = viewModel::loadLyrics,
                    onImportLyrics = viewModel::importLyrics,
                    onSaveLyrics = viewModel::saveLyrics,
                    onTryAnotherLyricsSource = viewModel::tryAnotherLyricsSource,
                    onFetchLyricsFromProvider = viewModel::fetchLyricsFromProvider,
                    onCancelLyricsProviderFetch = viewModel::cancelLyricsProviderFetch,
                    onRetryLyrics = viewModel::retryLyrics,
                    onAdjustLyricsOffset = { delta -> currentTrack?.let { viewModel.adjustTrackLyricsOffset(it, delta) } },
                    onResetLyricsOffset = { currentTrack?.let { viewModel.resetTrackLyricsOffset(it) } },
                    onUpdateLyricsSettings = { updated -> viewModel.updateLyricsSettings { updated } },
                    onBack = { nowPlayingOpen = false },
                    onPlayPause = { if (showingRestored) viewModel.resumeRestored() else viewModel.togglePlayback() },
                    onPrevious = { if (showingRestored) viewModel.resumeRestored(-1) else viewModel.previous() },
                    onNext = { if (showingRestored) viewModel.resumeRestored(1) else viewModel.next() },
                    onSeek = { if (showingRestored) viewModel.seekRestored(it) else viewModel.seekTo(it) },
                    onSeekBy = { if (showingRestored) viewModel.seekRestoredBy(it) else viewModel.seekBy(it) },
                    doubleTapSeekSeconds = settings.doubleTapSeekSeconds,
                    gesturesEnabled = settings.gestureSeekEnabled,
                    onShuffle = { if (showingRestored) viewModel.toggleRestoredShuffle() else viewModel.toggleShuffle() },
                    onRepeat = { if (showingRestored) viewModel.cycleRestoredRepeat() else viewModel.cycleRepeat() },
                    onFavorite = likeTrack,
                    onAddToQueue = viewModel::enqueue,
                    onRemoveFromQueue = { viewModel.removeQueueItem(it.id) },
                    onMoveQueueItem = { track, index -> viewModel.moveQueueItem(track.id, index) },
                    onPlayQueueItem = { viewModel.playQueueItem(it.id) },
                    onClearQueue = viewModel::clearQueue,
                    onStartSleepTimer = viewModel::startSleepTimer,
                    onCancelSleepTimer = viewModel::cancelSleepTimer,
                    playlists = playlists,
                    youtubePlaylists = youtubePlaylists,
                    onAddToPlaylist = viewModel::addToPlaylist,
                    onAddToAccountPlaylist = viewModel::addToAccountPlaylist,
                    onRefreshPlaylists = viewModel::refreshYouTubeLibrary,
                    onOpenEqualizer = { nowPlayingOpen = false; viewModel.push(Destination.Equalizer) },
                    onOpenWrapped = { nowPlayingOpen = false; viewModel.push(Destination.Wrapped) },
                    onOpenBackup = { nowPlayingOpen = false; viewModel.push(Destination.Backup) },
                    onEditMetadata = { nowPlayingOpen = false; viewModel.push(Destination.MetadataEditor(currentTrack.id)) },
                    onOpenAudioAnalysis = { id -> nowPlayingOpen = false; viewModel.push(Destination.AudioAnalysis(id)) },
                    // the full player records its own content into the SAME layer as the
                    // pages (only one of the two composes), so its own glass samples the player
                    // background instead of falling back to a flat tint.
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (glassLayerEnabled) Modifier.layerBackdrop(appBackdrop) else Modifier,
                        ),
                )
                } // end CompositionLocalProvider(LocalAppBackdrop = null)
            } else {
                Box(Modifier.fillMaxSize().nestedScroll(dockScroll)) {
                    // the wallpaper and every page are recorded into ONE layer carrying an
                    // opaque canvas . It is attached to the page container only; the dock,
                    // mini-player and Search circle are drawn above it as siblings , so
                    // they sample the finished frame instead of themselves.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (glassLayerEnabled) Modifier.layerBackdrop(appBackdrop) else Modifier,
                            ),
                    ) {
                        // Page surfaces are part of the layer being recorded. Letting them sample
                        // that same layer can re-enter its RenderNode and crash the renderer.
                        // Dock siblings remain outside this provider and keep the live backdrop.
                        CompositionLocalProvider(LocalAppBackdrop provides null) {
                        // App-wide chosen player background (Glass theme only): drawn once behind
                        // every pushed screen and tab, fixed while content scrolls, INSIDE the
                        // recorded layer so glass samples the wallpaper too .
                        if (settings.themeStyle == ThemeStyle.GLASSMORPHIC &&
                            settings.playerBackground != PlayerBackground.NONE
                        ) {
                            Crossfade(
                                targetState = settings.playerBackground,
                                animationSpec = AuralisMotion.tweenCreamy(AuralisMotion.Slow),
                                modifier = Modifier.fillMaxSize(),
                                label = "appBackground",
                            ) { background -> PlayerBackgroundImage(background) }
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                // Home owns its own non-pinned top bar and draws edge-to-edge behind
                                // the status bar; every other tab keeps the shared inset.
                                .then(
                                    if (nav.top == null && nav.tab == Tab.HOME) Modifier
                                    else Modifier.statusBarsPadding(),
                                )
                                .navigationBarsPadding(),
                        ) {
                            // The Settings hub and every Settings sub-page own their own title row
                            // with a back chevron, so the shared TopBar must not draw a second one.
                            val ownsHeader = nav.top?.isSettingsDestination == true
                            if (!ownsHeader && !(nav.top == null && nav.tab == Tab.HOME)) {
                                TopBar(
                                    // The back affordance follows the requested destination; the
                                    // old screen is only a short crossfade behind the incoming one.
                                    showBack = nav.top != null,
                                    onBack = viewModel::back,
                                    collapseProgress = { dockScroll.progress },
                                )
                            }
                            AnimatedContent(
                                targetState = nav,
                                transitionSpec = {
                                    // Fade-through: the outgoing screen fades out (and shrinks a
                                    // hair) first, then the incoming screen fades in. Running the
                                    // two fades in sequence means the old page is fully gone before
                                    // the new one paints, so translucent glass never ghosts it and
                                    // the black canvas carries the hand-off with no white flash.
                                    if (transitionScale == 0f) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else if (targetState.top == Destination.About) {
                                        // The About page is a lightweight reading screen. Start it
                                        // immediately instead of waiting for the outgoing Settings
                                        // page's fade-through, which felt like the tap had stalled.
                                        fadeIn(
                                            tween((180 * transitionScale).toInt().coerceAtLeast(1), easing = AuralisMotion.Creamy),
                                        ) togetherWith fadeOut(
                                            tween((120 * transitionScale).toInt().coerceAtLeast(1), easing = AuralisMotion.Creamy),
                                        )
                                    } else {
                                        // Creamy fade-through, now with a gentle rise: the outgoing
                                        // screen sinks and dissolves first (so translucent glass never
                                        // ghosts it), then the incoming screen floats up into place. The
                                        // long ease-out curve is what makes the hand-off feel soft.
                                        val outMs = (140 * transitionScale).toInt()
                                        val inMs = (320 * transitionScale).toInt()
                                        (fadeIn(
                                            tween(inMs, delayMillis = outMs, easing = AuralisMotion.Creamy),
                                        ) + slideInVertically(
                                            animationSpec = tween(inMs, delayMillis = outMs, easing = AuralisMotion.Creamy),
                                            initialOffsetY = { (it * 0.045f).toInt() },
                                        ) + scaleIn(
                                            tween(inMs, delayMillis = outMs, easing = AuralisMotion.Creamy),
                                            initialScale = 0.972f,
                                        )) togetherWith
                                            (fadeOut(tween(outMs, easing = AuralisMotion.Creamy)) +
                                                slideOutVertically(
                                                    animationSpec = tween(outMs, easing = AuralisMotion.Creamy),
                                                    targetOffsetY = { (-it * 0.025f).toInt() },
                                                ) +
                                                scaleOut(
                                                    tween(outMs, easing = AuralisMotion.Creamy),
                                                    targetScale = 0.985f,
                                                ))
                                    }.using(SizeTransform(clip = false))
                                },
                                contentKey = { it },
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
                                            entries = mergedHistory,
                                            currentId = playbackTrackId,
                                            onPlay = viewModel::playHistoryEntry,
                                            onClear = viewModel::clearHistory,
                                            settledMetadata = metadataSettled,
                                            onMetadataVisible = viewModel::requestMetadata,
                                            onMetadataHidden = viewModel::releaseMetadata,
                                        )
                                        Destination.Logs -> LogsDiagnosticsScreen(
                                settings,
                                sources,
                                currentTrack,
                                youtubeAuth = youtubeAuth,
                                youtubeReadError = youtubeSessionReadError,
                                youtubeHasSnapshot = youtubeSessionSnapshot,
                                historySync = historySyncSummary(playbackReporting, System.currentTimeMillis()),
                            )
                                        is Destination.ExploreMood -> {
                                            val moodId = (state.top as Destination.ExploreMood).moodId
                                            ExploreMoodScreen(
                                                mood = ExploreMoods.byId(moodId) ?: ExploreMoods.all.first(),
                                                state = explore,
                                                favorites = mergedFavorites,
                                                currentId = playbackTrackId,
                                                playlists = playlists,
                                                onPlay = viewModel::playExploreTrack,
                                                onFavorite = likeTrack,
                                                onAddToPlaylist = viewModel::addToPlaylist,
                                                onQueue = viewModel::enqueue,
                                                onPlayNext = viewModel::playNext,
                                                onRetry = { viewModel.loadExploreMood(moodId, force = true) },
                                            )
                                        }
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
                                        is Destination.YouTubePlaylist -> {
                                            val destination = state.top as Destination.YouTubePlaylist
                                            YouTubePlaylistScreen(
                                                title = destination.title,
                                                owner = destination.owner,
                                                coverUrl = ytPlaylist.coverUrl ?: destination.coverUrl,
                                                tracks = ytPlaylist.tracks,
                                                loading = ytPlaylist.loading,
                                                error = ytPlaylist.error,
                                                loaded = ytPlaylist.loaded,
                                                currentId = playbackTrackId,
                                                favorites = mergedFavorites,
                                                onPlay = viewModel::playYouTubePlaylistTrack,
                                                onShuffle = { viewModel.playYouTubePlaylist(shuffle = true) },
                                                onPlayNext = viewModel::playNext,
                                                onQueue = viewModel::enqueue,
                                                onFavorite = likeTrack,
                                                onRetry = viewModel::retryYouTubePlaylist,
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
                                            Tab.HOME -> HomeScreen(sources = sources, tracks = tracks, favorites = mergedFavorites, currentId = playbackTrackId, onAddMusic = addMusic, onScan = scan, onPlay = { viewModel.play(it, tracks) }, onFavorite = likeTrack, playlists = playlists, onAddToPlaylist = viewModel::addToPlaylist, onQueue = viewModel::enqueue, scanning = scanning, onPlayNext = viewModel::playNext, onOpenSettings = { viewModel.push(Destination.Settings) }, youtubeEnabled = settings.youtubeIntegration, personalizedHome = settings.personalizedHome, home = home, youtubeAvatarUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl), youtubeAccountName = youtubeAuth?.name?.ifBlank { null } ?: auth.account?.displayName, youtubeSignedIn = youtubeAuth?.signedIn == true, youtubeExpired = youtubeAuth?.expired == true, onOpenHome = { viewModel.loadHome() }, onRefreshHome = viewModel::refreshYouTubeHome, onPlayYtTrack = viewModel::playYouTubeTrack, onOpenYtCard = viewModel::openHomeCard, onLikeYtTrack = viewModel::toggleYouTubeLike, onLoadMoreHomeRow = viewModel::loadMoreHomeRow, onCloseHomeCollection = viewModel::closeHomeCollection, onYouTubeSignIn = { youtubeForceSignIn = false; youtubeLoginOpen = true }, onOpenExplore = { viewModel.selectTab(Tab.EXPLORE) }, libraryPlaylists = remember(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, auth.account?.displayName, youtubeAuth?.name, settings.librarySort) { buildLibraryPlaylists(playlists, tracks, playlistEntries, playlistYtEntries, youtubePlaylists, spotifyImports, youtubeAuth?.name?.ifBlank { null } ?: auth.account?.displayName, LibrarySortOption.fromId(settings.librarySort)) }, onOpenPlaylist = { id -> viewModel.push(Destination.PlaylistDetail(id)) }, onOpenYouTubePlaylist = viewModel::openYouTubePlaylist, onOpenLibrary = { viewModel.selectTab(Tab.LIBRARY) })
                                            Tab.EXPLORE -> ExploreScreen(
                                                onOpenMood = { mood ->
                                                    viewModel.loadExploreMood(mood.id)
                                                    viewModel.push(Destination.ExploreMood(mood.id))
                                                },
                                            )
                                            Tab.SEARCH -> SearchScreen(
                                                tracks, mergedFavorites, playbackTrackId, { viewModel.play(it, tracks) }, likeTrack, playlists, viewModel::addToPlaylist,
                                                onQueue = viewModel::enqueue, sources = sources, onPlayNext = viewModel::playNext,
                                                youtubeResults = youtubeSearch.results,
                                                youtubeLoading = youtubeSearch.loading,
                                                youtubeError = youtubeSearch.error,
                                                youtubeResolvingId = youtubeResolvingId,
                                                onSearchYouTube = viewModel::searchYouTube,
                                                onPlayYouTube = viewModel::playYouTube,
                                                youtubeEnabled = settings.youtubeIntegration,
                                                recentSearches = searchHistory,
                                                onSubmitSearch = viewModel::submitSearch,
                                                onDeleteRecent = viewModel::deleteSearch,
                                                onClearRecent = viewModel::clearSearchHistory,
                                                youtubeSignedIn = youtubeAuth?.signedIn == true && youtubeAuth?.expired != true,
                                                onConnectYouTube = {
                                                    youtubeForceSignIn = youtubeAuth?.expired == true
                                                    youtubeLoginOpen = true
                                                },
                                            )
                                            Tab.LIBRARY -> LibraryScreen(
                                                tracks, mergedFavorites, sources, playlists, playbackTrackId, addMusic, scan,
                                                { viewModel.playLibraryTrack(it, tracks) }, likeTrack, viewModel::createPlaylist, viewModel::addToPlaylist,
                                                onQueue = viewModel::enqueue,
                                                onPlayNext = viewModel::playNext,
                                                onPlayAlbum = viewModel::playAlbum,
                                                onQueueAlbum = viewModel::enqueueAll,
                                                onQueuePlaylist = viewModel::enqueuePlaylist,
                                                scanning = scanning,
                                                onOpenSettings = { viewModel.push(Destination.Settings) },
                                                youtubeEnabled = settings.youtubeIntegration,
                                                youtubeLiked = youtubeLikedTracks,
                                                youtubePlaylists = youtubePlaylists,
                                                onPlayYouTubeTrack = viewModel::playYouTubeTrack,
                                                onOpenYouTubePlaylist = viewModel::openYouTubePlaylist,
                                                onPlayYouTubePlaylist = viewModel::playYouTubePlaylistCard,
                                                youtubeResolvingId = youtubeResolvingId,
                                                onRefreshYouTubeLibrary = viewModel::refreshYouTubeLibrary,
                                                downloads = downloads,
                                                onOpenHistory = { viewModel.push(Destination.History) },
                                                onOpenPlaylist = { id -> viewModel.push(Destination.PlaylistDetail(id)) },
                                                onImportSpotify = importSpotify,
                                                spotifyImports = spotifyImports,
                                                onOpenImport = { id -> viewModel.push(Destination.SpotifyImport(id)) },
                                                onAutoFixTags = viewModel::autoFixTags,
                                                playlistEntries = playlistEntries,
                                                playlistYtEntries = playlistYtEntries,
                                                profileImageUrl = resolveAccountImageUrl(auth.account?.imageUrl, youtubeAuth?.avatarUrl),
                                                accountName = youtubeAuth?.name?.ifBlank { null } ?: auth.account?.displayName,
                                                librarySort = settings.librarySort,
                                                libraryGridView = settings.libraryGridView,
                                                onLibrarySortChange = { value -> viewModel.updateSettings { s -> s.copy(librarySort = value) } },
                                                onLibraryGridChange = { value -> viewModel.updateSettings { s -> s.copy(libraryGridView = value) } },
                                                onRenamePlaylist = { id, name -> viewModel.renamePlaylist(id, name) },
                                                onDeletePlaylist = viewModel::deletePlaylist,
                                                onPlayPlaylist = { id -> viewModel.playPlaylist(id) },
                                                onShufflePlaylist = viewModel::shufflePlaylist,
                                                youtubeLibraryError = youtubeLibraryError,
                                                onRetryYouTubeLibrary = viewModel::retryYouTubeLibrary,
                                                youtubeSignedIn = youtubeAuth?.signedIn == true,
                                                youtubeLibraryLoading = youtubeLibraryLoading,
                                                libraryReady = settingsLoaded,
                                                hasAudioPermission = audioPermissionGranted,
                                                onRequestAudioPermission = requestAudioPermission,
                                                onPlayYouTubeTrackFrom = { track, list -> viewModel.playYouTubeTrackFrom(track, list) },
                                                liveSyncState = liveSyncUi.state,
                                                liveSyncPulse = liveSyncUi.newItemsPulse,
                                                onPlayLiked = viewModel::playLiked,
                                                onPlayLocal = { track -> viewModel.playLibraryTrack(track, tracks) },
                                                onPlayDownloaded = viewModel::playDownloaded,
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
                        } // end CompositionLocalProvider(LocalAppBackdrop = null)
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
                        // The indicator follows the requested destination. Navigation composes the
                        // incoming screen in the same transition, so the selected tab always has
                        // visible content behind it.
                        selected = if (nav.top == null) nav.tab else null,
                        track = currentTrack,
                        playing = isPlaying,
                        onSelectTab = viewModel::selectTab,
                        onTogglePlay = { if (showingRestored) viewModel.resumeRestored() else viewModel.togglePlayback() },
                        onNext = { if (showingRestored) viewModel.resumeRestored(1) else viewModel.next() },
                        onOpenNowPlaying = { nowPlayingOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = if (dockStyle == DockStyle.GLASS) DockMetrics.GlassBottomMargin else DockMetrics.BottomMargin),
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
                    expired = youtubeConnectExpired,
                    onConnect = {
                        viewModel.dismissYouTubeConnectPrompt(dontAskAgain = false)
                        youtubeForceSignIn = false
                        youtubeLoginOpen = true
                    },
                    onNotNow = { viewModel.dismissYouTubeConnectPrompt(dontAskAgain = false) },
                    onDontAskAgain = { viewModel.dismissYouTubeConnectPrompt(dontAskAgain = true) },
                )
            }

            if (youtubeLoginOpen) {
                com.auralis.app.ui.screens.YouTubeLoginDialog(
                    onSession = { session ->
                        viewModel.beginYouTubeSignIn()
                        viewModel.saveYouTubeSession(session)
                    },
                    onDismiss = {
                        youtubeLoginOpen = false
                        youtubeForceSignIn = false
                    },
                    loginHint = youtubeConnectEmail ?: auth.account?.email,
                    savedSession = youtubeAuth,
                    forceSignIn = youtubeForceSignIn,
                )
            }

            // A menu popup is its own window, so the dim behind it has to be drawn here, above
            // every screen. It never consumes touches: the popup owns outside-tap dismissal.
            GlassOverlayScrim()
            GlassPortalHost(glassPortal)
        }
        }
        }
    }
}

/**
 * Provides the live glass locals from the persisted settings plus the transient drag copy.
 *
 * This deliberately lives in its own composable: [GlassDragState.value] is read HERE, so a slider
 * tick recomposes this small provider (and the composables that read the locals), not AuralisRoot
 * with its navigation state, playback state and dock. [LocalGlassSettings], [LocalGlassConfig] and
 * [LocalDockGlassStyle] are `compositionLocalOf`, so only their readers are invalidated.
 */
@Composable
private fun GlassSettingsProvider(
    drag: GlassDragState,
    persisted: GlassConfig,
    preview: kotlinx.coroutines.flow.StateFlow<GlassConfigPreview?>,
    onPreviewPersisted: (GlassConfig) -> Unit,
    settings: AppSettings,
    profile: PerformanceProfile,
    performanceReason: String?,
    content: @Composable () -> Unit,
) {
    val transient by preview.collectAsStateWithLifecycle()
    val config = drag.value ?: remember(persisted, transient) {
        transient?.applyTo(persisted) ?: persisted
    }
    LaunchedEffect(persisted, transient) {
        if (transient?.matches(persisted) == true) onPreviewPersisted(persisted)
    }
    val glass = remember(config) { GlassSettings.from(config) }
    // Dock fill/blur for the dock + Appearance preview. Surface fills are derived separately by
    // GlassKind inside glassSurface, so Glass clarity can never move the dock.
    val dockGlass = remember(settings, glass, profile, performanceReason) {
        resolveDockGlass(settings, glass, profile, performanceReason)
    }
    // The ONE blur decision for every glass surface (blur is a fixed constant, so the
    // removed "Blur strength" slider no longer participates).
    val blurAllowed = GlassBlurPolicy.allowed(settings)
    CompositionLocalProvider(
        LocalGlassConfig provides config,
        LocalGlassSettings provides glass,
        LocalDockGlassStyle provides dockGlass,
        LocalGlassBlurAllowed provides blurAllowed,
        content = content,
    )
}

/** One-time post-sign-in prompt offering the YouTube Music hand-off. */
@Composable
private fun ConnectYouTubePrompt(
    email: String?,
    expired: Boolean = false,
    onConnect: () -> Unit,
    onNotNow: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    GlassAlertDialog(
        onDismissRequest = onNotNow,
        title = if (expired) "Session expired" else "Connect YouTube Music",
        message = if (expired) {
            buildString {
                append("Your YouTube Music session expired, so live sync is paused. Sign in again to keep liked songs and playlists up to date.")
                if (!email.isNullOrBlank()) {
                    append(" We'll prefill ")
                    append(email)
                    append(" on the Google sign-in page.")
                }
            }
        } else {
            buildString {
                append("Sign in to YouTube Music to see your liked songs, playlists and history in AURALIS.")
                if (!email.isNullOrBlank()) {
                    append(" We'll prefill ")
                    append(email)
                    append(" on the Google sign-in page.")
                }
            }
        },
        confirmLabel = if (expired) "Sign in" else "Connect",
        onConfirm = onConnect,
        dismissLabel = "Not now",
        onDismiss = onNotNow,
        extraLabel = "Don't ask again",
        onExtra = onDontAskAgain,
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
            AuralisLogo(
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

/**
 * First drawn frame while the single settings read is in flight. It deliberately matches the black
 * window/splash background instead of Material's Normal surface, so no theme is previewed before the
 * saved one is known. The static wordmark (no animation) means the cold start reads as "AURALIS is
 * opening", never as an empty black frame.
 */
@Composable
private fun StartupSurface() {
    SystemBarsAppearance(darkTheme = true)
    Box(Modifier.fillMaxSize().background(GlassTokens.Canvas), contentAlignment = Alignment.Center) {
        AuralisLogo(modifier = Modifier.height(64.dp))
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
