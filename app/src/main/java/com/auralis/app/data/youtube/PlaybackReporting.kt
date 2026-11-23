package com.auralis.app.data.youtube

/**
 * The `/videostats` endpoints and the client-playback-nonce for one play.
 *
 * A play is reported with pings that must all carry the same nonce: the playback-start ping creates
 * the history entry, and the watch-time pings record how much was actually heard. The endpoints come
 * from the signed `player` response's `playbackTracking` block, so they are only known once that
 * request has answered — the reporter holds this value between the pings.
 */
data class PlaybackTracking(
    val cpn: String,
    val playbackUrl: String?,
    val watchtimeUrl: String?,
)

/**
 * The outcome of one outbound history-report call. Success is judged on the HTTP status alone: any
 * 2xx counts (204 in particular), and nothing else does.
 */
sealed interface PlaybackReportResult {
    /**
     * The call was accepted. [tracking] is set only by the opening call, so the caller can hold the
     * endpoints and nonce and reuse them for the watch-time pings that follow.
     */
    data class Ok(val tracking: PlaybackTracking? = null) : PlaybackReportResult

    /** YouTube answered, but not 2xx. [code] is the HTTP status (0 when the request never landed). */
    data class Refused(val code: Int) : PlaybackReportResult

    /** The player returned no usable tracking block: there is no history to update for this play. */
    data object NoTrackingUrl : PlaybackReportResult

    /** There is no signed-in session to report against. */
    data object NoSession : PlaybackReportResult
}

/** How the last outbound report ended, in the one word the Settings status row shows. */
enum class PlaybackReportOutcome {
    /** Reporting is off: the switch is off, or there is no usable session. */
    Off,

    /** Reporting is on but nothing has been sent for the current install yet. */
    Idle,

    /** The last report was accepted. */
    Sent,

    /** The last report was answered with a non-2xx status (see [PlaybackReportingState.lastRefusalCode]). */
    Refused,

    /** The player returned no tracking block, so the play could not be reported. */
    NoTrackingUrl,

    /** There was no signed-in session at the moment of the report. */
    NoSession,
}

/**
 * The outbound report status the Settings/Logs screens read. It is a plain snapshot: no secrets, no
 * URLs, only what a user needs to tell whether plays are being sent. Mirrors
 * [com.auralis.app.data.youtube.LiveSyncUiState] for the inbound direction.
 */
data class PlaybackReportingState(
    /** Whether reporting is currently wanted: the switch is on and a real session exists. */
    val enabled: Boolean = false,
    val outcome: PlaybackReportOutcome = PlaybackReportOutcome.Off,
    /** The HTTP status of the last refused report, or null. */
    val lastRefusalCode: Int? = null,
    /** Epoch millis of the last report attempt; 0 means "never in this install". */
    val lastAttemptAt: Long = 0L,
    /** Epoch millis of the last accepted report; 0 means "never in this install". */
    val lastSuccessAt: Long = 0L,
    /** Consecutive refused/failed reports; reset by the next success. */
    val consecutiveFailures: Int = 0,
)

/**
 * The network seam for playback-history reporting. [InnerTubeClient] is the production
 * implementation; tests provide a fake to observe the ping schedule without a network.
 */
interface PlaybackHistoryGateway {
    /** Opens one play: a signed `player` request, then the playback-start ping. */
    suspend fun openPlayback(videoId: String): PlaybackReportResult

    /** Sends one watch-time ping for an open play; [final] closes the play out (`final=1`). */
    suspend fun reportWatchtime(
        tracking: PlaybackTracking,
        listenedSeconds: Long,
        final: Boolean,
    ): PlaybackReportResult
}
