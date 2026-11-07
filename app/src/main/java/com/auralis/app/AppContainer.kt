package com.auralis.app

import android.content.Context
import com.auralis.app.data.backup.BackupRepository
import com.auralis.app.data.db.AuralisDatabase
import com.auralis.app.data.lyrics.LyricsRepository
import com.auralis.app.data.metadata.MetadataEditor
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.data.settings.PlaybackSnapshotRepository
import com.auralis.app.data.sources.SourceRepository
import com.auralis.app.data.sources.MusicScanner
import com.auralis.app.data.sources.LibraryAutoScanner
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.performance.DevicePerformanceMonitor

/**
 * Manual dependency container, created once by [AuralisApp]. Chosen over a DI framework to keep
 * the build lean; every dependency is constructed in one place so it is easy to swap in tests.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AuralisDatabase by lazy { AuralisDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val playbackSnapshot: PlaybackSnapshotRepository by lazy { PlaybackSnapshotRepository(appContext) }
    val sources: SourceRepository by lazy { SourceRepository(appContext, database.sourceDao()) }
    val scanner: MusicScanner by lazy { MusicScanner(appContext, database.sourceDao(), database.trackDao(), settings) }
    /** Automatic new-song detection: ContentObserver + on-resume/periodic incremental diffs. */
    val autoScanner: LibraryAutoScanner by lazy { LibraryAutoScanner(appContext, scanner, settings) }
    val playback: PlaybackController by lazy { PlaybackController(appContext) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(appContext, database.lyricsDao(), database.sourceDao()) }
    val metadata: MetadataEditor by lazy { MetadataEditor(appContext) }
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
