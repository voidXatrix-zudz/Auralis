package com.auralis.app.data.youtube

/** One card/row on the YouTube Music home feed. [videoId] cards play; [browseId] cards are shelves. */
data class YtCard(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String? = null,
    val browseId: String? = null,
    val durationMs: Long = 0L,
)

/** One titled horizontal shelf of the home feed. */
data class YtShelf(
    val title: String,
    val items: List<YtCard>,
)

/** Subset of the home feed the app renders: top picks, moods and the remaining shelves. */
data class YouTubeHomeFeed(
    val topPicks: List<YtCard> = emptyList(),
    val moods: List<YtCard> = emptyList(),
    val shelves: List<YtShelf> = emptyList(),
)

/** Account playlist shown read-only in Library. */
data class YtPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val trackCount: Int = 0,
)
