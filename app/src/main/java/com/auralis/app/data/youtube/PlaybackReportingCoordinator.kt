package com.auralis.app.data.youtube

import android.os.SystemClock
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.playback.PlaybackController
import com.auralis.app.playback.PlaybackHistoryTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Feeds the outbound history reporter from real playback and exposes its status.
 *
 * This is the ONE reporting collector, and it lives on an application-level scope so a locked screen
 * or a swiped-away task (the playback service keeps running) does not drop reports. It reuses
 * [PlaybackHistoryTracker] for the honest listened clock, so reporting and the local history agree on
 * what "really played" means; [YouTubePlaybackReporter] owns the ping policy.
 *
 * Reporting is gated live by [AppSettings.youtubeSyncHistory] and [authenticated], so flipping the
 * switch off stops sends at once and flipping it on resumes them.
 */
class PlaybackReportingCoordinator(
    scope: CoroutineScope,
    playbackState: Flow<PlaybackController.State>,
    settings: Flow<AppSettings>,
    private val authenticated: () -> Boolean,
    gateway: PlaybackHistoryGateway,
    private val wallClockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow(PlaybackReportingState())
    val state: StateFlow<PlaybackReportingState> = _state.asStateFlow()

    /** The switch as last read; [authenticated] is checked live so an expiry takes effect at once. */
    @Volatile
    private var switchOn = false

    private val tracker = PlaybackHistoryTracker()
    private val reporter = YouTubePlaybackReporter(
        scope = scope,
        gateway = gateway,
        enabled = { switchOn && authenticated() },
        onResult = ::onResult,
    )

    init {
        scope.launch {
            settings.collect { current ->
                switchOn = current.youtubeSyncHistory
                val enabled = switchOn && authenticated()
                _state.update { previous ->
                    when {
                        !enabled -> previous.copy(enabled = false, outcome = PlaybackReportOutcome.Off)
                        previous.outcome == PlaybackReportOutcome.Off ->
                            previous.copy(enabled = true, outcome = PlaybackReportOutcome.Idle)
                        else -> previous.copy(enabled = true)
                    }
                }
            }
        }
        scope.launch {
            playbackState.collect { playback ->
                val id = playback.trackId
                val playing = playback.connected && playback.playing
                tracker.onTick(id, playback.positionMs, playing, wallClockMs())
                reporter.onSample(
                    PlaybackSample(
                        videoId = id?.takeIf { it.startsWith(YOUTUBE_TRACK_PREFIX) }
                            ?.removePrefix(YOUTUBE_TRACK_PREFIX),
                        generation = tracker.generation,
                        listenedMs = tracker.listenedMs,
                        durationMs = playback.durationMs,
                        playing = playing,
                    ),
                )
            }
        }
    }

    /** Drops the current play and any refusal backoff (sign-out / account switch). */
    fun reset() {
        reporter.reset()
        val enabled = switchOn && authenticated()
        _state.update { previous ->
            previous.copy(
                enabled = enabled,
                outcome = if (enabled) PlaybackReportOutcome.Idle else PlaybackReportOutcome.Off,
                lastRefusalCode = null,
                consecutiveFailures = 0,
                pullFailed = false,
            )
        }
    }

    /**
     * Records one `FEmusic_history` pull so Settings can show "Last pulled" beside "Last sent". A
     * failed pull keeps the previous success time and only raises the failure flag.
     */
    fun noteHistoryPull(failed: Boolean, now: Long = System.currentTimeMillis()) {
        _state.update { previous ->
            previous.copy(
                lastPulledAt = if (failed) previous.lastPulledAt else now,
                pullFailed = failed,
            )
        }
    }

    private fun onResult(result: PlaybackReportResult) {
        val now = System.currentTimeMillis()
        _state.update { previous ->
            when (result) {
                is PlaybackReportResult.Ok -> previous.copy(
                    enabled = true,
                    outcome = PlaybackReportOutcome.Sent,
                    lastAttemptAt = now,
                    lastSuccessAt = now,
                    lastRefusalCode = null,
                    consecutiveFailures = 0,
                )
                is PlaybackReportResult.Refused -> previous.copy(
                    enabled = true,
                    outcome = PlaybackReportOutcome.Refused,
                    lastAttemptAt = now,
                    lastRefusalCode = result.code,
                    consecutiveFailures = previous.consecutiveFailures + 1,
                )
                PlaybackReportResult.NoTrackingUrl -> previous.copy(
                    enabled = true,
                    outcome = PlaybackReportOutcome.NoTrackingUrl,
                    lastAttemptAt = now,
                    consecutiveFailures = previous.consecutiveFailures + 1,
                )
                PlaybackReportResult.NoSession -> previous.copy(
                    enabled = false,
                    outcome = PlaybackReportOutcome.NoSession,
                    lastAttemptAt = now,
                )
            }
        }
    }
}
