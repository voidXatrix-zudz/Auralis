package com.auralis.app.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.auralis.app.data.youtube.YOUTUBE_SOURCE_ID
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.data.youtube.youtubePlaybackUri
import com.auralis.app.domain.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Storage keys for the playback checkpoint. Kept in one object so the repository and its unit tests
 * read the exact same keys; every "display" key is optional so a store written by an older build
 * (which only had the five original keys) still loads.
 */
object PlaybackSnapshotKeys {
    val trackId = stringPreferencesKey("track_id")
    val positionMs = longPreferencesKey("position_ms")
    val wasPlaying = booleanPreferencesKey("was_playing")
    val shuffle = booleanPreferencesKey("shuffle")
    val repeatMode = intPreferencesKey("repeat_mode")

    // Added for "resume the last played song": display data for the current song plus a compact queue.
    val title = stringPreferencesKey("track_title")
    val artist = stringPreferencesKey("track_artist")
    val album = stringPreferencesKey("track_album")
    val durationMs = longPreferencesKey("track_duration_ms")
    val artworkUrl = stringPreferencesKey("track_artwork_url")
    val isRemote = booleanPreferencesKey("track_is_remote")
    val remoteId = stringPreferencesKey("track_remote_id")
    val queueJson = stringPreferencesKey("queue_json")
    val savedAt = longPreferencesKey("saved_at")
}

/**
 * The playback checkpoint: enough state to show the last played song, paused, on the next launch.
 *
 * Only [trackId] is mandatory for a saved checkpoint to exist; every other field has a default so an
 * old snapshot (five keys) decodes cleanly. No stream URL is ever stored.
 */
data class PlaybackSnapshot(
    val trackId: String?,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val isRemote: Boolean = false,
    val remoteId: String? = null,
    /** JSON-encoded compact queue (see [PlaybackCheckpointCodec]); null for an old snapshot. */
    val queueJson: String? = null,
    val savedAt: Long = 0L,
)

/** The display data of one queue entry: enough to draw a row and to rebuild a playable [Track]. */
@Serializable
data class CheckpointTrack(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val isRemote: Boolean = false,
    val remoteId: String? = null,
)

@Serializable
private data class QueueBlob(val v: Int = 1, val items: List<CheckpointTrack> = emptyList())

/**
 * Encodes/decodes the saved queue as one JSON string. Pure and total: any malformed payload decodes
 * to an empty list, and the item count is always capped at [MAX_QUEUE] on both ends, so a corrupt or
 * hostile store can neither crash the launch nor grow without bound.
 */
object PlaybackCheckpointCodec {
    const val MAX_QUEUE = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(items: List<CheckpointTrack>): String =
        json.encodeToString(QueueBlob.serializer(), QueueBlob(items = items.take(MAX_QUEUE)))

    fun decode(payload: String?): List<CheckpointTrack> {
        if (payload.isNullOrBlank()) return emptyList()
        val blob = runCatching { json.decodeFromString(QueueBlob.serializer(), payload) }.getOrNull()
            ?: return emptyList()
        return blob.items.take(MAX_QUEUE)
    }
}

/**
 * Maps raw preferences to a snapshot. Returns null when nothing was ever saved. Tolerates a store
 * written before the display fields existed (all new fields fall back to their defaults).
 */
fun playbackSnapshotFrom(prefs: Preferences): PlaybackSnapshot? {
    val trackId = prefs[PlaybackSnapshotKeys.trackId] ?: return null
    return PlaybackSnapshot(
        trackId = trackId,
        positionMs = (prefs[PlaybackSnapshotKeys.positionMs] ?: 0L).coerceAtLeast(0L),
        wasPlaying = prefs[PlaybackSnapshotKeys.wasPlaying] ?: false,
        shuffle = prefs[PlaybackSnapshotKeys.shuffle] ?: false,
        repeatMode = (prefs[PlaybackSnapshotKeys.repeatMode] ?: 0).coerceIn(0, 2),
        title = prefs[PlaybackSnapshotKeys.title],
        artist = prefs[PlaybackSnapshotKeys.artist],
        album = prefs[PlaybackSnapshotKeys.album],
        durationMs = (prefs[PlaybackSnapshotKeys.durationMs] ?: 0L).coerceAtLeast(0L),
        artworkUrl = prefs[PlaybackSnapshotKeys.artworkUrl],
        isRemote = prefs[PlaybackSnapshotKeys.isRemote] ?: false,
        remoteId = prefs[PlaybackSnapshotKeys.remoteId],
        queueJson = prefs[PlaybackSnapshotKeys.queueJson],
        savedAt = prefs[PlaybackSnapshotKeys.savedAt] ?: 0L,
    )
}

/** Display data of a [Track], enough to rebuild the pill without the library. */
fun Track.toCheckpointTrack(): CheckpointTrack = CheckpointTrack(
    id = id,
    title = title.ifBlank { displayName },
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    isRemote = isRemote,
    remoteId = remoteId ?: if (isRemote) id.removePrefix(YOUTUBE_TRACK_PREFIX) else null,
)

/**
 * Rebuilds a [Track] from stored display data. A remote song gets the lazy `auralis-yt://<videoId>`
 * marker (no stream URL is held); a local song is display-only until the library supplies its URI,
 * tags and artwork.
 */
fun CheckpointTrack.toTrack(): Track {
    val remoteVideoId = if (isRemote) remoteId ?: id.removePrefix(YOUTUBE_TRACK_PREFIX) else null
    val trackId = if (isRemote && !id.startsWith(YOUTUBE_TRACK_PREFIX)) "$YOUTUBE_TRACK_PREFIX$remoteVideoId" else id
    return Track(
        id = trackId,
        sourceId = if (isRemote) YOUTUBE_SOURCE_ID else "",
        uri = if (isRemote) remoteVideoId?.let(::youtubePlaybackUri).orEmpty() else "",
        relativePath = "",
        displayName = title,
        sizeBytes = 0L,
        lastModified = 0L,
        ext = if (isRemote) "webm" else "",
        mime = if (isRemote) "audio/webm" else "",
        title = title,
        artist = artist,
        albumArtist = artist,
        album = album,
        genres = emptyList(),
        trackNumber = null,
        discNumber = null,
        year = null,
        composer = null,
        durationMs = durationMs,
        codec = null,
        container = null,
        sampleRateHz = null,
        bitDepth = null,
        bitrateBps = null,
        channels = null,
        lossless = false,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
        artKey = null,
        artColor = null,
        addedAt = 0L,
        tagError = null,
        isRemote = isRemote,
        artworkUrl = artworkUrl,
        remoteId = remoteVideoId,
    )
}
