package com.auralis.app.data.youtube

import kotlinx.serialization.Serializable

/** What a card represents; the Home UI uses this to pick a card shape. */
enum class YtItemType(val wire: String) {
    SONG("song"),
    VIDEO("video"),
    ALBUM("album"),
    PLAYLIST("playlist"),
    ARTIST("artist"),
    MOOD("mood"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun from(value: String?): YtItemType =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** One card/row on the YouTube Music home feed. [videoId] cards play; [browseId] cards are shelves. */
@Serializable
data class YtCard(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String? = null,
    val browseId: String? = null,
    val durationMs: Long = 0L,
    /** "song", "video", "album", "playlist", "artist" or "mood". */
    val itemType: String = YtItemType.SONG.wire,
    val explicit: Boolean = false,
    /** Raw InnerTube `musicVideoType`; null for rows that are not songs. */
    val musicVideoType: String? = null,
) {
    val type: YtItemType get() = YtItemType.from(itemType)
}

/** One titled horizontal shelf of the home feed. */
@Serializable
data class YtShelf(
    val title: String,
    val items: List<YtCard>,
    /** Token for the next page of this shelf, when YouTube supplied one. */
    val continuation: String? = null,
)

/** A saved album from the signed-in library. */
@Serializable
data class YtAlbum(
    val browseId: String,
    val title: String,
    val artist: String = "",
    val year: String = "",
    val thumbnailUrl: String? = null,
    /** "Album", "Single" or "EP", as printed by YouTube Music. */
    val releaseType: String = "",
)

/** A library/subscribed artist. */
@Serializable
data class YtArtist(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subscribers: String = "",
)

/** Subset of the home feed the app renders: top picks, moods and the remaining shelves. */
@Serializable
data class YouTubeHomeFeed(
    val topPicks: List<YtCard> = emptyList(),
    val moods: List<YtCard> = emptyList(),
    val shelves: List<YtShelf> = emptyList(),
    val refreshedAt: Long = 0L,
)

/** Account playlist shown read-only in Library. */
@Serializable
data class YtPlaylist(
    /** Browse id, normally already prefixed with "VL". */
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val trackCount: Int = 0,
    val owner: String = "",
)
