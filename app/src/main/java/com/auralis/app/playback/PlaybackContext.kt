package com.auralis.app.playback

import androidx.media3.common.MediaItem

/**
 * The list the user actually started playback from. Every queue can be split into two blocks:
 * the CONTEXT songs (the playlist/album/liked list, in its saved order) and, after them, the
 * AUTOPLAY songs the radio/recommendation engine contributed.
 *
 * Next/Previous only ever walk the context block; autoplay is entered only once the context is
 * exhausted and the user has not turned autoplay off. Keeping the distinction on the MediaItem
 * (not in Compose) means the notification, lock screen, headset and Android Auto all obey the
 * same rule as the in-app buttons.
 */
enum class PlaybackContextKind {
    PLAYLIST,
    ALBUM,
    LIKED,
    DOWNLOADED,
    LOCAL,
    SEARCH,
    RADIO,
    HISTORY,
}

/** A context is identified by kind + stable id, and labelled by [name] on the Queue screen. */
data class PlaybackContext(
    val kind: PlaybackContextKind,
    val id: String = "",
    val name: String = "",
) {
    /** Radio-style playback has no finite context: recommendations are the queue. */
    val isRadio: Boolean
        get() = kind == PlaybackContextKind.RADIO || kind == PlaybackContextKind.SEARCH

    companion object {
        val Local = PlaybackContext(PlaybackContextKind.LOCAL, "local", "Local Files")
        val Liked = PlaybackContext(PlaybackContextKind.LIKED, "liked", "Liked Songs")
        val Downloaded = PlaybackContext(PlaybackContextKind.DOWNLOADED, "downloaded", "Downloaded")
        val History = PlaybackContext(PlaybackContextKind.HISTORY, "history", "History")
        val Search = PlaybackContext(PlaybackContextKind.SEARCH, "search", "Search")
        fun radio(name: String = "Radio") = PlaybackContext(PlaybackContextKind.RADIO, "radio", name)
        fun playlist(id: Long, name: String) =
            PlaybackContext(PlaybackContextKind.PLAYLIST, id.toString(), name.ifBlank { "Playlist" })
        fun album(id: String, name: String) =
            PlaybackContext(PlaybackContextKind.ALBUM, id, name.ifBlank { "Album" })
    }
}

/** MediaMetadata extra that tells the playback service which block an item belongs to. */
internal const val QUEUE_ROLE_EXTRA = "com.auralis.app.QUEUE_ROLE"

/** The item is part of the list the user started from. */
internal const val QUEUE_ROLE_CONTEXT = "context"

/** The item was appended by the radio/recommendation engine after the context. */
internal const val QUEUE_ROLE_AUTOPLAY = "autoplay"

/** The block this item belongs to; untagged (legacy/timeline) items count as context. */
internal fun MediaItem.queueRole(): String =
    mediaMetadata.extras?.getString(QUEUE_ROLE_EXTRA) ?: QUEUE_ROLE_CONTEXT

internal fun MediaItem.isAutoplayItem(): Boolean = queueRole() == QUEUE_ROLE_AUTOPLAY