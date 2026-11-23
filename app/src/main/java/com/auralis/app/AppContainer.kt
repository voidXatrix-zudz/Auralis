package com.auralis.app

import android.content.Context
import com.auralis.app.data.backup.BackupRepository
import com.auralis.app.data.auth.ClerkAuthRepository
import com.auralis.app.data.db.AuralisDatabase
import com.auralis.app.data.lyrics.LyricsRepository
import com.auralis.app.data.metadata.MetadataEditor
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.data.settings.PlaybackSnapshotRepository
import com.auralis.app.data.sources.SourceRepository
import com.auralis.app.data.sources.MusicScanner
import com.auralis.app.data.sources.LibraryAutoScanner
import com.auralis.app.data.update.UpdateManager
import com.auralis.app.data.youtube.YouTubeRepository
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.performance.DevicePerformanceMonitor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Manual dependency container, created once by [AuralisApp]. Chosen over a DI framework to keep
 * the build lean; every dependency is constructed in one place so it is easy to swap in tests.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /**
     * Process-lifetime scope for work that must outlive the UI (backgrounded playback reporting).
     * A playback service keeps audio going after the task is swiped away, so reporting cannot ride
     * on `viewModelScope`; [SupervisorJob] keeps one failed child from cancelling the others.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Room opens lazily, so a broken migration or a corrupt file only surfaces on the first DAO call
    // (deep inside the ViewModel) as a launch crash. Opening it here, once, lets the UI show a
    // recovery screen instead. See [databaseFailureOrNull] and [resetDatabase].
    private val databaseLock = Any()

    @Volatile
    private var databaseResult: Result<AuralisDatabase>? = null

    val database: AuralisDatabase
        get() = databaseResult?.getOrThrow() ?: synchronized(databaseLock) {
            databaseResult?.getOrThrow() ?: openDatabase().also { databaseResult = it }.getOrThrow()
        }

    /** Opens the database once and returns the failure, or null when it opened cleanly. */
    fun databaseFailureOrNull(): Throwable? {
        synchronized(databaseLock) {
            if (databaseResult == null) databaseResult = openDatabase()
        }
        return databaseResult?.exceptionOrNull()
    }

    /**
     * Closes and deletes the on-device library database after the user explicitly confirms, so the
     * next [database] access rebuilds an empty one. Only touches the app's own database files.
     */
    fun resetDatabase() {
        synchronized(databaseLock) {
            runCatching { databaseResult?.getOrNull()?.close() }
            databaseResult = null
        }
        val base = appContext.getDatabasePath(AuralisDatabase.DB_NAME)
        listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
            .forEach { runCatching { if (it.exists()) it.delete() } }
    }

    private fun openDatabase(): Result<AuralisDatabase> = runCatching {
        AuralisDatabase.create(appContext).also {
            // Force the open (and any pending migration) now so a failure is reported here.
            it.openHelper.writableDatabase
        }
    }
    /**
     * Clerk identity layer (Google + email sign-in, session persistence). Inert when no
     * CLERK_PUBLISHABLE_KEY was supplied at build time, so the app keeps working without it.
     */
    val clerkAuth: ClerkAuthRepository by lazy { ClerkAuthRepository(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val playbackSnapshot: PlaybackSnapshotRepository by lazy { PlaybackSnapshotRepository(appContext) }
    val sources: SourceRepository by lazy { SourceRepository(appContext, database.sourceDao()) }
    val scanner: MusicScanner by lazy { MusicScanner(appContext, database.sourceDao(), database.trackDao(), settings) }
    /** Automatic new-song detection: ContentObserver + on-resume/periodic incremental diffs. */
    val autoScanner: LibraryAutoScanner by lazy { LibraryAutoScanner(appContext, scanner, settings) }
    val playback: PlaybackController by lazy { PlaybackController(appContext) }
    val lyrics: LyricsRepository by lazy {
        LyricsRepository(
            appContext,
            database.lyricsDao(),
            database.sourceDao(),
            database.providerCacheDao(),
            settings,
            youtubeHttp,
        )
    }
    /** Online metadata providers (MusicBrainz, Deezer, iTunes, Last.fm); enabled set read per call. */
    val metadataRepository: com.auralis.app.data.metadata.MetadataRepository by lazy {
        com.auralis.app.data.metadata.MetadataRepository(youtubeHttp, settings, BuildConfig.VERSION_NAME)
    }
    /** Real square album art lookup for songs that only have a video thumbnail (Part J). */
    val artworkResolver: com.auralis.app.data.artwork.ArtworkResolver by lazy {
        com.auralis.app.data.artwork.ArtworkResolver(database.providerCacheDao(), metadataRepository)
    }
    /** Circular artist photos with Room cache (Part L). */
    val artistImages: com.auralis.app.data.artists.ArtistImageRepository by lazy {
        com.auralis.app.data.artists.ArtistImageRepository(
            database.artistImageDao(),
            settings,
            youtube,
            youtubeHttp,
        )
    }
    val metadata: MetadataEditor by lazy { MetadataEditor(appContext) }
    /** GitHub release checks, APK download and the package-installer hand-off. */
    val updates: UpdateManager by lazy { UpdateManager(appContext, settings) }
    /**
     * ONE process-wide client for InnerTube search, radio, suggestions and NewPipe extraction.
     *
     * OkHttp keeps HTTP/2 connections alive and transparently requests gzip, so the second search
     * for a prefix reuses the same socket. The short connect/read budgets keep a dead network from
     * stalling the UI; extraction calls still get a little more headroom than a search.
     */
    val youtubeHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(6, 5, TimeUnit.MINUTES))
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
    }
    /**
     * Same client/pool as [youtubeHttp], but without a call/read deadline: an audio stream stays
     * open for the whole song, so the 20 s API budget must not apply. Sharing the ConnectionPool is
     * what lets Media3 reuse the socket that stream prefetching already warmed.
     */
    val playbackHttp: OkHttpClient by lazy {
        youtubeHttp.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    /** YouTube source: keyless search + lazily resolved audio stream URLs. */
    val youtubeAuth by lazy { com.auralis.app.data.youtube.YouTubeAuthStore(appContext) }
    /** Off-screen WebView used to recover cookies without a dialog. */
    val youTubeSilentRefresh by lazy {
        com.auralis.app.data.youtube.YouTubeSilentRefresh(appContext)
    }
    /**
     * QUICKJS-BACKED YOUTUBE.JS. One engine for the whole process, started on a private worker
     * thread at launch and reused by every resolve; never touched from the main thread.
     */
    val youtubeJsEngine by lazy {
        com.auralis.app.data.youtube.YouTubeJsEngine(appContext, youtubeHttp)
    }
    /** The YouTube.js provider; disabled by the Settings toggle, which the resolver checks. */
    val youtubeJsProvider by lazy {
        com.auralis.app.data.youtube.YouTubeJsProvider(youtubeJsEngine)
    }
    val youtube: YouTubeRepository by lazy {
        YouTubeRepository(
            youtubeHttp,
            youtubeAuth,
            settings,
            database.youtubeCacheDao(),
            silentRefresh = { youTubeSilentRefresh.refresh() },
            youtubeJs = youtubeJsProvider,
            metered = { com.auralis.app.data.network.NetworkQuality.isMetered(appContext) },
            signatureTimestamp = { youtubeJsEngine.signatureTimestamp() },
        )
    }
    /**
     * Reports plays to the account history from an application-level scope, so reporting survives a
     * locked screen or a swiped-away task. One instance for the whole process.
     */
    val playbackReporting: com.auralis.app.data.youtube.PlaybackReportingCoordinator by lazy {
        com.auralis.app.data.youtube.PlaybackReportingCoordinator(
            scope = applicationScope,
            playbackState = playback.state,
            settings = settings.settings,
            authenticated = { youtubeAuth.snapshot()?.signedIn == true },
            gateway = youtube.playbackHistory,
        )
    }
    /** Offline YouTube downloads: WorkManager jobs into the app's music folder. */
    val downloads by lazy { com.auralis.app.data.download.DownloadManager(appContext) }
    /**
     * Keeps the signed-in account live (liked songs, playlists) with efficient InnerTube polling.
     * Started/stopped by the ViewModel from settings + session state; also drives a WorkManager tier.
     */
    val liveSync: com.auralis.app.data.youtube.LiveSyncManager by lazy {
        com.auralis.app.data.youtube.LiveSyncManager(
            appContext,
            youtube,
            youtubeAuth,
            settings,
            database,
            performance,
        )
    }
    /** Spotify (Exportify) CSV imports: background matching into local playlists. */
    val spotifyImport by lazy { com.auralis.app.data.spotify.SpotifyImportManager(appContext) }
    /** Battery Saver + thermal source for Automatic performance. App-lifetime, no polling. */
    val performance: DevicePerformanceMonitor by lazy { DevicePerformanceMonitor(appContext) }
    val backup: BackupRepository by lazy {
        BackupRepository(
            appContext,
            database.trackDao(),
            database.playlistDao(),
            database.favoriteDao(),
            database.historyDao(),
            database.lyricsDao(),
            database.playEventDao(),
            settings,
        )
    }
}
