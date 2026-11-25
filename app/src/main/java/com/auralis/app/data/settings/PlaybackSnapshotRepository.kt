package com.auralis.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.playbackSnapshotStore: DataStore<Preferences> by preferencesDataStore(name = "playback_snapshot")

/** Small playback checkpoint kept separately from user settings and the Room queue. */
class PlaybackSnapshotRepository(context: Context) {
    private val store = context.applicationContext.playbackSnapshotStore

    val snapshot: Flow<PlaybackSnapshot?> = store.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { prefs -> playbackSnapshotFrom(prefs) }

    suspend fun save(snapshot: PlaybackSnapshot) {
        store.edit { prefs ->
            snapshot.trackId?.let { prefs[PlaybackSnapshotKeys.trackId] = it } ?: prefs.remove(PlaybackSnapshotKeys.trackId)
            prefs[PlaybackSnapshotKeys.positionMs] = snapshot.positionMs.coerceAtLeast(0L)
            prefs[PlaybackSnapshotKeys.wasPlaying] = snapshot.wasPlaying
            prefs[PlaybackSnapshotKeys.shuffle] = snapshot.shuffle
            prefs[PlaybackSnapshotKeys.repeatMode] = snapshot.repeatMode.coerceIn(0, 2)
            snapshot.title?.let { prefs[PlaybackSnapshotKeys.title] = it } ?: prefs.remove(PlaybackSnapshotKeys.title)
            snapshot.artist?.let { prefs[PlaybackSnapshotKeys.artist] = it } ?: prefs.remove(PlaybackSnapshotKeys.artist)
            snapshot.album?.let { prefs[PlaybackSnapshotKeys.album] = it } ?: prefs.remove(PlaybackSnapshotKeys.album)
            prefs[PlaybackSnapshotKeys.durationMs] = snapshot.durationMs.coerceAtLeast(0L)
            snapshot.artworkUrl?.let { prefs[PlaybackSnapshotKeys.artworkUrl] = it } ?: prefs.remove(PlaybackSnapshotKeys.artworkUrl)
            prefs[PlaybackSnapshotKeys.isRemote] = snapshot.isRemote
            snapshot.remoteId?.let { prefs[PlaybackSnapshotKeys.remoteId] = it } ?: prefs.remove(PlaybackSnapshotKeys.remoteId)
            snapshot.queueJson?.let { prefs[PlaybackSnapshotKeys.queueJson] = it } ?: prefs.remove(PlaybackSnapshotKeys.queueJson)
            prefs[PlaybackSnapshotKeys.savedAt] = snapshot.savedAt
            snapshot.contextKind?.let { prefs[PlaybackSnapshotKeys.contextKind] = it } ?: prefs.remove(PlaybackSnapshotKeys.contextKind)
            snapshot.contextId?.let { prefs[PlaybackSnapshotKeys.contextId] = it } ?: prefs.remove(PlaybackSnapshotKeys.contextId)
            snapshot.contextName?.let { prefs[PlaybackSnapshotKeys.contextName] = it } ?: prefs.remove(PlaybackSnapshotKeys.contextName)
        }
    }

    /** Drops the checkpoint entirely (used when the user clears the queue). */
    suspend fun clear() {
        store.edit { it.clear() }
    }
}
