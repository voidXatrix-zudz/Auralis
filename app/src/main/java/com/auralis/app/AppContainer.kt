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
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Manual dependency container, created once by [AuralisApp]. Chosen over a DI framework to keep
 * the build lean; every dependency is constructed in one place so it is easy to swap in tests.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val database: AuralisDatabase by lazy { AuralisDatabase.create(appContext) }
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
    val youtube: YouTubeRepository by lazy { YouTubeRepository(youtubeHttp, youtubeAuth) }
    /** Offline YouTube downloads: WorkManager jobs into the app's music folder. */
    val downloads by lazy { com.auralis.app.data.download.DownloadManager(appContext) }
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
