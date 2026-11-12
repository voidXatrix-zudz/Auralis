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
