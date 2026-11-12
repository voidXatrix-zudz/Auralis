package com.auralis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Lightweight projection used for incremental rescans (skip files that did not change). */
data class TrackFingerprint(
    val id: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY addedAt")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY addedAt")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: String): SourceEntity?

    @Query("UPDATE sources SET lastScanAt = :at WHERE id = :id")
    suspend fun markScanned(id: String, at: Long)

    /** Tracks of the source are removed by the foreign-key cascade. */
    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    /** Blocking batch variant called only by the scanner from Dispatchers.IO while it owns a cursor. */
    @Upsert
    fun upsertBatch(tracks: List<TrackEntity>)

    @Query("SELECT id FROM tracks WHERE sourceId = :sourceId")
    fun idsBySource(sourceId: String): List<String>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    fun deleteIdsNow(ids: List<String>)

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    suspend fun getAllNow(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query ORDER BY title COLLATE NOCASE")
    fun search(query: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT id, lastModified, sizeBytes FROM tracks WHERE sourceId = :sourceId")
    suspend fun fingerprints(sourceId: String): List<TrackFingerprint>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM tracks WHERE sourceId = :sourceId AND id NOT IN (:ids)")
    suspend fun deleteMissing(sourceId: String, ids: List<String>)

    @Query("DELETE FROM tracks WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface LyricsDao {
    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun forTrack(trackId: String): List<LyricsEntity>

    @Query("SELECT * FROM lyrics")
    suspend fun getAll(): List<LyricsEntity>

    @Query("DELETE FROM lyrics WHERE trackId = :trackId AND source = :source")
    suspend fun delete(trackId: String, source: String)
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    /** The remote YouTube playlist id mirroring a local playlist, or null when it is local-only. */
    @Query("SELECT remoteId FROM playlists WHERE id = :id")
    suspend fun remoteIdOf(id: Long): String?

    @Query("UPDATE playlists SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: String?)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    /** Entries are removed by the foreign-key cascade. */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    fun observeEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    suspend fun getAllEntries(): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    /** Duplicates are ignored through the unique (playlistId, trackId) index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeEntry(playlistId: Long, trackId: String)

    @Query("UPDATE playlist_entries SET position = :position WHERE entryId = :entryId")
    suspend fun setPosition(entryId: Long, position: Int)

    // --- Streamed YouTube entries (no foreign key to the on-device tracks table) ---

    @Query("SELECT * FROM playlist_yt_entries ORDER BY playlistId, position")
    fun observeYtEntries(): Flow<List<PlaylistYtEntryEntity>>

    @Query("SELECT * FROM playlist_yt_entries ORDER BY playlistId, position")
    suspend fun getAllYtEntries(): List<PlaylistYtEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_yt_entries WHERE playlistId = :playlistId")
    suspend fun maxYtPosition(playlistId: Long): Int

    /** Duplicates are ignored through the unique (playlistId, videoId) index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertYtEntries(entries: List<PlaylistYtEntryEntity>)

    @Query("DELETE FROM playlist_yt_entries WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeYtEntry(playlistId: Long, videoId: String)

    @Query("UPDATE playlist_yt_entries SET position = :position WHERE entryId = :entryId")
    suspend fun setYtPosition(entryId: Long, position: Int)
}

/** Durable queue of YouTube account mutations awaiting retry while offline. */
@Dao
interface YouTubeSyncDao {
    @Insert
    suspend fun insert(op: YouTubeSyncOpEntity): Long

    @Query("SELECT * FROM youtube_sync_ops ORDER BY createdAt, id")
    suspend fun getAll(): List<YouTubeSyncOpEntity>

    @Query("DELETE FROM youtube_sync_ops WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM youtube_sync_ops")
    suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT trackId FROM favorites ORDER BY addedAt DESC")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun remove(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavorite(trackId: String): Boolean

    @Transaction
    suspend fun toggle(trackId: String, now: Long) {
        if (isFavorite(trackId)) remove(trackId) else add(FavoriteEntity(trackId, now))
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM play_history ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history WHERE trackId = :trackId")
    suspend fun get(trackId: String): PlayHistoryEntity?

    @Query("SELECT * FROM play_history ORDER BY lastPlayedAt DESC")
    suspend fun getAll(): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history ORDER BY lastPlayedAt DESC")
    fun observeAll(): Flow<List<PlayHistoryEntity>>

    @Upsert
    suspend fun upsert(entry: PlayHistoryEntity)

    @Transaction
    suspend fun recordPlay(trackId: String, now: Long) {
        val existing = get(trackId)
        upsert(PlayHistoryEntity(trackId, now, (existing?.playCount ?: 0) + 1))
    }

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Upsert
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun getAll(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replaceAll(items: List<QueueItemEntity>) {
        clear()
        insertAll(items)
    }
}

/** Append-only play log for monthly and yearly listening recaps. */
@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Query("SELECT * FROM play_events ORDER BY playedAt DESC")
    fun observeAll(): Flow<List<PlayEventEntity>>

    @Query("SELECT * FROM play_events ORDER BY playedAt DESC")
    suspend fun getAll(): List<PlayEventEntity>

    @Query("DELETE FROM play_events")
    suspend fun clear()
}

@Dao
interface YouTubeDao {
    @Query("SELECT videoId FROM youtube_likes ORDER BY likedAt DESC")
    fun observeLikedIds(): Flow<List<String>>

    @Query("SELECT * FROM youtube_likes ORDER BY likedAt DESC")
    fun observeLiked(): Flow<List<YouTubeLikeEntity>>

    @Upsert
    suspend fun upsertLike(entry: YouTubeLikeEntity)

    @Query("DELETE FROM youtube_likes WHERE videoId = :videoId")
    suspend fun deleteLike(videoId: String)

    @Query("DELETE FROM youtube_likes")
    suspend fun clearLikes()

    @Query("SELECT EXISTS(SELECT 1 FROM youtube_likes WHERE videoId = :videoId)")
    suspend fun isLiked(videoId: String): Boolean

    @Query("SELECT * FROM youtube_recents ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int): Flow<List<YouTubeRecentEntity>>

    /** Full history list, newest first, for the History screen. */
    @Query("SELECT * FROM youtube_recents ORDER BY playedAt DESC")
    fun observeHistory(): Flow<List<YouTubeRecentEntity>>

    @Query("SELECT playCount FROM youtube_recents WHERE videoId = :videoId")
    suspend fun playCountOf(videoId: String): Int?

    @Upsert
    suspend fun upsertRecent(entry: YouTubeRecentEntity)

    @Query("DELETE FROM youtube_recents")
    suspend fun clearRecents()
}

/** Offline YouTube downloads saved into the user's music folder. */
@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun get(videoId: String): DownloadEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE videoId = :videoId)")
    suspend fun exists(videoId: String): Boolean

    @Query("SELECT videoId FROM downloads")
    suspend fun allVideoIds(): List<String>

    @Upsert
    suspend fun upsert(entry: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

/** Spotify (Exportify) CSV imports: run state, per-row matches and their candidates. */
@Dao
interface SpotifyDao {
    @Insert
    suspend fun insertImport(entry: SpotifyImportEntity): Long

    @Query("UPDATE spotify_imports SET state = :state, processed = :processed, matched = :matched, review = :review, failed = :failed WHERE id = :id")
    suspend fun updateProgress(id: Long, state: String, processed: Int, matched: Int, review: Int, failed: Int)

    @Query("UPDATE spotify_imports SET playlistId = :playlistId WHERE id = :id")
    suspend fun setPlaylist(id: Long, playlistId: Long?)

    @Query("SELECT * FROM spotify_imports ORDER BY createdAt DESC")
    fun observeImports(): Flow<List<SpotifyImportEntity>>

    @Query("SELECT * FROM spotify_imports WHERE id = :id")
    suspend fun getImport(id: Long): SpotifyImportEntity?

    @Query("DELETE FROM spotify_imports WHERE id = :id")
    suspend fun deleteImport(id: Long)

    @Insert
    suspend fun insertItems(items: List<SpotifyImportItemEntity>)

    @Upsert
    suspend fun upsertItem(item: SpotifyImportItemEntity)

    @Query("UPDATE spotify_import_items SET status = :status, matchedVideoId = :videoId, matchedTitle = :title, matchedArtist = :artist WHERE id = :id")
    suspend fun resolveItem(id: Long, status: String, videoId: String?, title: String?, artist: String?)

    @Query("SELECT * FROM spotify_import_items WHERE importId = :importId AND status IN ('REVIEW', 'FAILED') ORDER BY position")
    fun observeNeedsReview(importId: Long): Flow<List<SpotifyImportItemEntity>>

    @Query("SELECT * FROM spotify_import_items WHERE importId = :importId ORDER BY position")
    suspend fun itemsFor(importId: Long): List<SpotifyImportItemEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM spotify_import_items WHERE importId = :importId")
    suspend fun maxItemPosition(importId: Long): Int

    @Query("SELECT * FROM yt_match_cache WHERE queryKey = :key")
    suspend fun cachedMatch(key: String): YtMatchCacheEntity?

    @Upsert
    suspend fun putMatch(entry: YtMatchCacheEntity)
}

/** Shared lyrics/metadata provider cache (winners and negative results). */
@Dao
interface ProviderCacheDao {
    @Upsert
    suspend fun upsert(entry: ProviderCacheEntity)

    @Query("SELECT * FROM provider_cache WHERE kind = :kind AND trackId = :trackId AND provider = :provider")
    suspend fun get(kind: String, trackId: String, provider: String): ProviderCacheEntity?

    @Query("SELECT * FROM provider_cache WHERE kind = :kind AND trackId = :trackId ORDER BY updatedAt DESC")
    suspend fun forKey(kind: String, trackId: String): List<ProviderCacheEntity>

    @Query("DELETE FROM provider_cache WHERE kind = :kind")
    suspend fun clearKind(kind: String)

    @Query("DELETE FROM provider_cache WHERE kind = :kind AND trackId = :trackId")
    suspend fun deleteKey(kind: String, trackId: String)

    @Query("DELETE FROM provider_cache WHERE found = 0")
    suspend fun clearNegative()

    @Query("DELETE FROM provider_cache")
    suspend fun clearAll()
}
