package com.auralis.app.playback

/**
 * The single history threshold for the whole app: a song is "played" only after ten seconds of real
 * listening. Local tracks and YouTube Music songs both use this constant.
 */
const val HISTORY_MIN_MS = 10_000L

/**
 * Accumulates *actual* playing time per track and reports once when it crosses [thresholdMs].
 *
 * The playback state publishes a position roughly every 500 ms. Two rules make the count honest:
 *  - while paused, nothing accumulates;
 *  - a forward jump that is much larger than the elapsed wall-clock time is a seek, so nothing is
 *    added for the jump. Seeking therefore cannot inflate a play into the history.
 *
 * Changing track resets the counter. Returning to a track (or repeating it after it was counted)
 * starts a new count, so a re-listen is recorded again and moves back to the top of history.
 *
 * Pure Kotlin and clock-injected, so the exact 9 s/10 s/seek/re-listen behaviour is unit-tested.
 */
class PlaybackHistoryTracker(
    private val thresholdMs: Long = HISTORY_MIN_MS,
    private val seekToleranceMs: Long = 1_500L,
) {
    private var currentId: String? = null
    private var accumulatedMs = 0L
    private var lastPositionMs = -1L
    private var lastWallMs = -1L
    private var fired = false

    /**
     * Bumped each time a new occurrence starts: a track change, a repeat/re-listen, or a stop. It is
     * the app's single "this is a different play" signal, reused by the history reporter so the two
     * never disagree about what counts as a new play.
     */
    var generation: Long = 0L
        private set

    /** Total listening time accumulated for the current track. */
    val listenedMs: Long get() = accumulatedMs

    /** True once the current occurrence of the current track has been recorded. */
    val recorded: Boolean get() = fired

    fun reset() {
        currentId = null
        accumulatedMs = 0L
        lastPositionMs = -1L
        lastWallMs = -1L
        fired = false
    }

    /**
     * Feeds one playback-state sample.
     *
     * @return true exactly once per track occurrence, on the sample that reaches the threshold.
     */
    fun onTick(trackId: String?, positionMs: Long, playing: Boolean, wallClockMs: Long): Boolean {
        if (trackId.isNullOrBlank()) {
            if (currentId != null) generation++
            reset()
            return false
        }
        if (trackId != currentId) {
            currentId = trackId
            accumulatedMs = 0L
            lastPositionMs = positionMs
            lastWallMs = wallClockMs
            fired = false
            generation++
            return false
        }
        val wallDelta = wallClockMs - lastWallMs
        if (wallDelta <= 0L) return false
        lastWallMs = wallClockMs

        val positionDelta = positionMs - lastPositionMs
        lastPositionMs = positionMs

        if (!playing) return false

        // A jump backwards after a counted play is a restart/repeat, not a seek: count it again.
        if (positionDelta < -seekToleranceMs) {
            if (fired) {
                accumulatedMs = 0L
                fired = false
                generation++
            }
            return false
        }
        val listened = when {
            positionDelta <= 0L -> 0L
            // Position advanced far more than wall time: a seek, not listening.
            positionDelta > wallDelta + seekToleranceMs -> 0L
            else -> minOf(positionDelta, wallDelta)
        }
        accumulatedMs += listened
        if (!fired && accumulatedMs >= thresholdMs) {
            fired = true
            return true
        }
        return false
    }
}