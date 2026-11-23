package com.auralis.app.data.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One sample of the current play: everything the reporter needs and nothing more.
 *
 * [generation] identifies the occurrence (a track change, a repeat or a re-listen), [listenedMs] is
 * the honest listened time for it (pauses and seeks excluded), and [playing] is whether audio is
 * actually advancing right now. A null [videoId] means a non-YouTube track or nothing playing.
 */
data class PlaybackSample(
    val videoId: String?,
    val generation: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val playing: Boolean,
)

/**
 * Reports a YouTube Music play to the account history the way a real client does, with at most three
 * pings per play:
 *
 *  - a playback-start ping about [START_AFTER_MS] into real listening, which creates the entry;
 *  - one watch-time ping at `min(30 s, half the title)`, carrying the real listened seconds;
 *  - a final watch-time ping (`final=1`) when the play ends — a track change, a stop, or a
 *    definitive pause that lasts [FINAL_AFTER_PAUSE_MS].
 *
 * A new play is detected from [PlaybackSample.generation], so a repeat-one loop or a re-listen
 * reports again. Real failures are retried a few times; a refusal (400/401/403) backs off for
 * 1/5/15 minutes before the next play, never a permanent switch-off for the run.
 *
 * The work runs on the scope the caller supplies, after playback has started, so it can never delay
 * tap-to-play or touch stream resolving. [gateway] is the only seam to the network, which keeps this
 * class pure and unit-testable.
 */
class YouTubePlaybackReporter(
    private val scope: CoroutineScope,
    private val gateway: PlaybackHistoryGateway,
    /** Whether reporting is currently wanted (settings switch + a real session). */
    private val enabled: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Called with the outcome of each report so the UI can show a readable status. */
    private val onResult: (PlaybackReportResult) -> Unit = {},
) {
    private class Play(
        val videoId: String,
        val generation: Long,
        var tracking: PlaybackTracking? = null,
        var started: Boolean = false,
        var watchtimeSent: Boolean = false,
        var finalSent: Boolean = false,
        var lastListenedMs: Long = 0L,
        var stoppedSinceMs: Long = 0L,
    )

    private val events = Channel<PlaybackSample>(Channel.UNLIMITED)
    private var current: Play? = null
    private var refusalStep = 0
    private var backoffUntilMs = 0L

    init {
        scope.launch { for (sample in events) process(sample) }
    }

    /**
     * Offers one playback-state sample. Cheap and non-blocking: it is handled on the consumer
     * coroutine, so the caller's collector is never delayed by a network call.
     */
    fun onSample(sample: PlaybackSample) {
        events.trySend(sample)
    }

    /** Forgets the current play (Disconnect / account switch); any open play is closed out first. */
    fun reset() {
        events.trySend(
            PlaybackSample(videoId = null, generation = -1L, listenedMs = 0L, durationMs = 0L, playing = false),
        )
        refusalStep = 0
        backoffUntilMs = 0L
    }

    private suspend fun process(sample: PlaybackSample) {
        val videoId = sample.videoId?.takeIf { it.isNotBlank() }
        val previous = current
        val changed = previous == null || previous.generation != sample.generation
        if (videoId == null || changed) {
            if (enabled()) previous?.let { finalize(it) }
            current = videoId?.let { Play(videoId = it, generation = sample.generation) }
        }
        val play = current ?: return
        play.lastListenedMs = sample.listenedMs

        // Stop sending at once when the switch is off; turning it back on resumes this same play.
        if (!enabled()) return

        if (!sample.playing) {
            // A pause is not necessarily the end: close the play out only after it settles.
            if (play.stoppedSinceMs == 0L) play.stoppedSinceMs = nowMs()
            if (play.started && !play.finalSent && nowMs() - play.stoppedSinceMs >= FINAL_AFTER_PAUSE_MS) {
                sendFinal(play)
            }
            return
        }
        play.stoppedSinceMs = 0L

        if (!play.started) {
            if (sample.listenedMs < START_AFTER_MS) return
            if (nowMs() < backoffUntilMs) return
            open(play)
            return
        }
        val tracking = play.tracking
        if (!play.watchtimeSent && tracking != null && sample.listenedMs >= watchThresholdMs(sample.durationMs)) {
            val result = gateway.reportWatchtime(tracking, sample.listenedMs / 1000L, final = false)
            onResult(result)
            if (result is PlaybackReportResult.Ok) {
                play.watchtimeSent = true
                onSuccess()
            } else {
                onFailure(result)
            }
        }
    }

    /** Opens a play, retrying genuine failures a few times before giving the play up. */
    private suspend fun open(play: Play) {
        var attempt = 0
        while (true) {
            val result = gateway.openPlayback(play.videoId)
            onResult(result)
            when (result) {
                is PlaybackReportResult.Ok -> {
                    play.tracking = result.tracking
                    play.started = true
                    onSuccess()
                    return
                }
                PlaybackReportResult.NoTrackingUrl, PlaybackReportResult.NoSession -> {
                    // A verdict, not a failure: no tracking block or no session means nothing to send.
                    play.started = true
                    return
                }
                is PlaybackReportResult.Refused -> {
                    if (isRefusal(result.code) || attempt >= MAX_ATTEMPTS - 1) {
                        play.started = true
                        onFailure(result)
                        return
                    }
                    attempt++
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun finalize(play: Play) {
        if (play.started && !play.finalSent) sendFinal(play)
    }

    private suspend fun sendFinal(play: Play) {
        play.finalSent = true
        val tracking = play.tracking ?: return
        val result = gateway.reportWatchtime(tracking, play.lastListenedMs / 1000L, final = true)
        onResult(result)
        if (result is PlaybackReportResult.Ok) onSuccess() else onFailure(result)
    }

    /** A 400/401/403 is a refusal to back off from; other failures are retried (see [open]). */
    private fun isRefusal(code: Int): Boolean = code == 400 || code == 401 || code == 403

    /** Escalating 1/5/15-minute backoff on a refusal; reset by the next success. */
    private fun onFailure(result: PlaybackReportResult) {
        val code = (result as? PlaybackReportResult.Refused)?.code ?: return
        if (!isRefusal(code)) return
        val step = refusalStep.coerceAtMost(REFUSAL_BACKOFF_MS.lastIndex)
        backoffUntilMs = nowMs() + REFUSAL_BACKOFF_MS[step]
        refusalStep = (step + 1).coerceAtMost(REFUSAL_BACKOFF_MS.lastIndex)
    }

    private fun onSuccess() {
        refusalStep = 0
        backoffUntilMs = 0L
    }

    /** `min(30 s, half the title)`, in real listened time; an unknown length still waits 30 s. */
    private fun watchThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_WATCH_MS
        return minOf(MIN_WATCH_MS, (durationMs / 2L).coerceAtLeast(1_000L))
    }

    companion object {
        /** How much real listening must pass before the play is registered. */
        const val START_AFTER_MS = 2_000L

        /** The watch-time ping waits for this much real listening, or half a short title. */
        const val MIN_WATCH_MS = 30_000L

        /** A pause this long is treated as the end of the play. */
        const val FINAL_AFTER_PAUSE_MS = 5_000L

        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_ATTEMPTS = 3

        /** Bounded refusal backoff: 1, 5 then 15 minutes. Never a permanent switch-off. */
        private val REFUSAL_BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}
