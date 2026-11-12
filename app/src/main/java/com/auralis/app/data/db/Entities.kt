package com.auralis.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    /** [com.auralis.app.domain.model.SourceKind] name. */
    val kind: String,
    val name: String,
    val treeUri: String?,
    val addedAt: Long,
    val lastScanAt: Long?,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId"), Index("addedAt"), Index("album"), Index("artist")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val ext: String,
    val mime: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    /** Genres joined with U+001F (unit separator). */
    val genres: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val composer: String?,
    val durationMs: Long,
    val codec: String?,
    val container: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateBps: Int?,
    val channels: Int?,
    val lossless: Boolean,
    val replayGainTrackDb: Float?,
    val replayGainAlbumDb: Float?,
    val artKey: String?,
    val artColor: Int?,
    val addedAt: Long,
    val tagError: String?,
)

/** Raw lyrics text per track and origin; parsed to timed lines at read time. */
@Entity(
    tableName = "lyrics",
    primaryKeys = ["trackId", "source"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class LyricsEntity(
    val trackId: String,
    /** [com.auralis.app.domain.model.LyricsSource] name. */
    val source: String,
    val raw: String,
    val updatedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The YouTube Music playlist id this local playlist mirrors once synced; null when local-only. */
    val remoteId: String? = null,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index(value = ["playlistId", "trackId"], unique = true)],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val trackId: String,
    val position: Int,
)

/**
 * A streamed YouTube song inside a local playlist. It cannot live in [PlaylistEntryEntity] because
 * that table has a foreign key to the on-device `tracks` table. The metadata is stored inline so a
 * playlist still renders when offline; the stream is resolved on play.
 */
@Entity(
    tableName = "playlist_yt_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId", "videoId"], unique = true)],
)
data class PlaylistYtEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val position: Int,
)

/**
 * One queued YouTube account mutation that could not be applied immediately (offline, or a request
 * that failed). Replayed in creation order by the sync queue once the account is reachable again.
 */
@Entity(tableName = "youtube_sync_ops", indices = [Index("createdAt")])
data class YouTubeSyncOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CREATE, RENAME, DELETE, ADD_VIDEO or REMOVE_VIDEO. */
    val kind: String,
    /** The local playlist row this change belongs to; 0 when it no longer exists (e.g. DELETE). */
    val playlistId: Long,
    /** The remote playlist id, when it was already known at queue time. */
    val remoteId: String?,
    /** The YouTube video id for ADD_VIDEO/REMOVE_VIDEO. */
    val videoId: String?,
    /** The playlist title for CREATE/RENAME. */
    val title: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "play_history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lastPlayedAt")],
)
data class PlayHistoryEntity(
    @PrimaryKey val trackId: String,
    val lastPlayedAt: Long,
    val playCount: Int,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

/** YouTube songs hearted in-app. No foreign key: these are not library tracks. */
@Entity(tableName = "youtube_likes")
data class YouTubeLikeEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val likedAt: Long,
)

/** Recently played YouTube songs, for the Home "Recents" row and the History screen. */
@Entity(tableName = "youtube_recents", indices = [Index("playedAt")])
data class YouTubeRecentEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val playedAt: Long,
    val playCount: Int,
)

/** One offline YouTube download saved into the user's music folder. */
@Entity(tableName = "downloads", indices = [Index("downloadedAt")])
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    val artPath: String?,
    val format: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
)

/** One play occurrence, used for Wrapped listening statistics. */
@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index("playedAt")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val durationMs: Long,
)

/** One row of the persisted playback queue, in play order. */
@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val trackId: String,
    /** Position before shuffling, so turning shuffle off restores the original order. */
    val originalPosition: Int,
)

/** One Importify/Exportify CSV import run, tracked for progress and its final summary. */
@Entity(tableName = "spotify_imports", indices = [Index("createdAt")])
data class SpotifyImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val createdAt: Long,
    /** RUNNING while the worker is matching, DONE when finished. */
    val state: String,
    val total: Int,
    val processed: Int,
    val matched: Int,
    val review: Int,
    val failed: Int,
    /** The local playlist this import filled; null once the playlist is deleted. */
    val playlistId: Long?,
)

/**
 * One Spotify row being matched. Low-confidence rows keep their candidate list as JSON so the
 * "Needs review" screen can offer another pick without re-searching.
 */
@Entity(
    tableName = "spotify_import_items",
    indices = [Index("importId"), Index(value = ["importId", "position"])],
)
data class SpotifyImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val importId: Long,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** MATCHED, REVIEW, FAILED or SKIPPED. */
    val status: String,
    val matchedVideoId: String?,
    val matchedTitle: String?,
    val matchedArtist: String?,
    /** JSON array of candidate songs, for the review screen. */
    val candidates: String?,
)

/** Cached YouTube Music match per normalized "title artist" query, so re-importing is instant. */
@Entity(tableName = "yt_match_cache", indices = [Index("cachedAt")])
data class YtMatchCacheEntity(
    @PrimaryKey val queryKey: String,
    /** Null when the search found nothing; the miss is cached too. */
    val videoId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val cachedAt: Long,
)
