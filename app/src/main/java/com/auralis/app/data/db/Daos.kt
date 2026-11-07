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
