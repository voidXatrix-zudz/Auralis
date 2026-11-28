package com.auralis.app.ui

import com.auralis.app.domain.model.Track

/**
 * One entry of a local playlist, unified across on-device tracks and streamed YouTube songs so the
 * playlist UI can list, play and reorder them together.
 */
data class PlaylistItem(
    val entryId: Long,
    val remote: Boolean,
    val track: Track,
    val position: Int,
)

/**
 * Playlist order: the song added most recently is FIRST, the oldest is last.
 *
 * `position` is a single counter shared by on-device and streamed entries (every insert uses
 * `max(both tables) + 1`), so the highest position is the newest song. A manual move rewrites the
 * positions with [positionForDisplayIndex], which keeps this rule true afterwards. Ties can only come
 * from rows written before the counter was shared; they resolve deterministically, streamed entries
 * first, then the higher entry id.
 */
fun List<PlaylistItem>.newestFirst(): List<PlaylistItem> =
    sortedWith(
        compareByDescending<PlaylistItem> { it.position }
            .thenByDescending { it.remote }
            .thenByDescending { it.entryId },
    )

/** The position to store for the row shown at [index] of [size] rows, so the top row has the highest. */
fun positionForDisplayIndex(index: Int, size: Int): Int = size - 1 - index

/** One YouTube Music candidate for a Spotify import row, decoded from the stored JSON. */
data class SpotifyCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val score: Double,
)
