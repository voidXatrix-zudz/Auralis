package com.auralis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        TrackEntity::class,
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        FavoriteEntity::class,
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        QueueItemEntity::class,
        PlayEventEntity::class,
        YouTubeLikeEntity::class,
        YouTubeRecentEntity::class,
        DownloadEntity::class,
        PlaylistYtEntryEntity::class,
        SpotifyImportEntity::class,
        SpotifyImportItemEntity::class,
        YtMatchCacheEntity::class,
        YouTubeSyncOpEntity::class,
        ProviderCacheEntity::class,
        ArtistImageEntity::class,
    ],
    version = 10,
    exportSchema = true,
) abstract class AuralisDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun trackDao(): TrackDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun youtubeDao(): YouTubeDao
    abstract fun downloadDao(): DownloadDao
    abstract fun spotifyDao(): SpotifyDao
    abstract fun youtubeSyncDao(): YouTubeSyncDao
    abstract fun providerCacheDao(): ProviderCacheDao
    abstract fun artistImageDao(): ArtistImageDao

    companion object {
        /** Adds the play-event log without touching the existing library, queues or settings. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_events_trackId` ON `play_events` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_events_playedAt` ON `play_events` (`playedAt`)")
            }
        }

        /** Adds the YouTube likes and recents tables; no existing table changes. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `youtube_likes` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `likedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `youtube_recents` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `playedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_youtube_recents_playedAt` " +
                        "ON `youtube_recents` (`playedAt`)",
                )
            }
        }

        /** Adds the offline download table; no existing table changes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `artPath` TEXT,
                        `format` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `downloadedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_downloadedAt` ON `downloads` (`downloadedAt`)")
            }
        }

        /** Adds the play count to recents so History can show how often a song was played. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `youtube_recents` ADD COLUMN `playCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds YouTube songs inside local playlists; no existing table changes. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_yt_entries` (
                        `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `position` INTEGER NOT NULL,
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_yt_entries_playlistId_videoId` " +
                        "ON `playlist_yt_entries` (`playlistId`, `videoId`)",
                )
            }
        }

        /** Adds the Spotify CSV import tables and the YouTube match cache; no existing table changes. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `spotify_imports` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `total` INTEGER NOT NULL,
                        `processed` INTEGER NOT NULL,
                        `matched` INTEGER NOT NULL,
                        `review` INTEGER NOT NULL,
                        `failed` INTEGER NOT NULL,
                        `playlistId` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spotify_imports_createdAt` ON `spotify_imports` (`createdAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `spotify_import_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `importId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `matchedVideoId` TEXT,
                        `matchedTitle` TEXT,
                        `matchedArtist` TEXT,
                        `candidates` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spotify_import_items_importId` ON `spotify_import_items` (`importId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spotify_import_items_importId_position` ON `spotify_import_items` (`importId`, `position`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `yt_match_cache` (
                        `queryKey` TEXT NOT NULL,
                        `videoId` TEXT,
                        `title` TEXT,
                        `artist` TEXT,
                        `album` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `thumbnailUrl` TEXT,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`queryKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_yt_match_cache_cachedAt` ON `yt_match_cache` (`cachedAt`)")
            }
        }

        /** Adds the remote YouTube playlist id and the offline sync queue; existing data untouched. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `remoteId` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `youtube_sync_ops` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `remoteId` TEXT,
                        `videoId` TEXT,
                        `title` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_youtube_sync_ops_createdAt` ON `youtube_sync_ops` (`createdAt`)")
            }
        }

        /** Adds the shared lyrics/metadata provider cache. Existing data untouched. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `provider_cache` (
                        `kind` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `found` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`kind`, `trackId`, `provider`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_cache_updatedAt` ON `provider_cache` (`updatedAt`)")
            }
        }

        /** Adds the circular artist-photo cache (Part L). Existing data untouched. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `artist_images` (
                        `key` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_images_fetchedAt` ON `artist_images` (`fetchedAt`)")
            }
        }

        fun create(context: Context): AuralisDatabase =
            Room.databaseBuilder(context.applicationContext, AuralisDatabase::class.java, "auralis.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
    }
}
