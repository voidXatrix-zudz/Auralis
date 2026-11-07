package com.auralis.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.playbackSnapshotStore: DataStore<Preferences> by preferencesDataStore(name = "playback_snapshot")

data class PlaybackSnapshot(
    val trackId: String?,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/** Small playback checkpoint kept separately from user settings and the Room queue. */
class PlaybackSnapshotRepository(context: Context) {
    private val store = context.applicationContext.playbackSnapshotStore

    val snapshot: Flow<PlaybackSnapshot?> = store.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { prefs ->
            val trackId = prefs[Keys.trackId] ?: return@map null
            PlaybackSnapshot(
                trackId = trackId,
                positionMs = (prefs[Keys.positionMs] ?: 0L).coerceAtLeast(0L),
                wasPlaying = prefs[Keys.wasPlaying] ?: false,
                shuffle = prefs[Keys.shuffle] ?: false,
                repeatMode = (prefs[Keys.repeatMode] ?: 0).coerceIn(0, 2),
            )
        }

    suspend fun save(snapshot: PlaybackSnapshot) {
        store.edit { prefs ->
            snapshot.trackId?.let { prefs[Keys.trackId] = it } ?: prefs.remove(Keys.trackId)
            prefs[Keys.positionMs] = snapshot.positionMs.coerceAtLeast(0L)
            prefs[Keys.wasPlaying] = snapshot.wasPlaying
            prefs[Keys.shuffle] = snapshot.shuffle
            prefs[Keys.repeatMode] = snapshot.repeatMode.coerceIn(0, 2)
        }
    }

    private object Keys {
        val trackId = stringPreferencesKey("track_id")
        val positionMs = longPreferencesKey("position_ms")
        val wasPlaying = booleanPreferencesKey("was_playing")
        val shuffle = booleanPreferencesKey("shuffle")
        val repeatMode = intPreferencesKey("repeat_mode")
    }
}
