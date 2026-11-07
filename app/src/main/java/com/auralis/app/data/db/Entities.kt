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
