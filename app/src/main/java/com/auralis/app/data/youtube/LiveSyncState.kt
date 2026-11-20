package com.auralis.app.data.youtube

/**
 * What the live-sync engine is doing right now. Drives the tiny "Live" indicator on Liked Songs and
 * the Settings status row. Every value is safe to show to the user; none carries secrets.
 */
enum class LiveSyncState {
    /** Integration off, signed out, or the user turned live sync off. */
    Disabled,

    /** Configured but not currently polling (for example the app is in the background). */
    Idle,

    /** A poll is in flight and something may change. */
    Syncing,

    /** The last poll succeeded and the account is up to date. */
    Live,

    /** No usable network right now; polling is paused and will resume automatically. */
    Offline,

    /** YouTube rejected the stored session; the user must sign in again. */
    AuthExpired,

    /** The last poll failed for another reason (parse error, server error). */
    Error,
}

/** One immutable snapshot of the live-sync engine, exposed to the UI as a single StateFlow. */
data class LiveSyncUiState(
    val state: LiveSyncState = LiveSyncState.Disabled,
    /** Epoch millis of the last successful account read; 0 means "never in this install". */
    val lastSyncedAt: Long = 0L,
    /**
     * Bumped once each time new liked songs land in Room. The UI uses it as a one-shot pulse key and
     * never animates continuously.
     */
    val newItemsPulse: Long = 0L,
)

/**
 * A like/unlike the user just did locally. The poller leaves the matching id alone until it expires,
 * so a 4-second poll cannot overwrite the user's own action before YouTube has caught up.
 */
internal data class PendingLikeAction(
    val liked: Boolean,
    val expiresAt: Long,
)
