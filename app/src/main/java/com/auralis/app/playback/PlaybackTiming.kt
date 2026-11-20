package com.auralis.app.playback

/**
 * A tiny cross-layer marker for the tap → resolved → audible timing trail.
 *
 * The tap happens in the ViewModel, the URL is resolved on Media3's loader thread and the first
 * audible frame arrives in the service; none of those three sees the others. A single volatile
 * timestamp (plus a generation counter, so tapping the same song twice still counts as two taps) is
 * enough to line them up and log honest end-to-end numbers in the Logs screen.
 */
object PlaybackTiming {
    /** Taps older than this are treated as history, not as the cause of the current playback event. */
    private const val FRESH_TAP_MS = 30_000L

    @Volatile private var tapAt: Long = 0L
    @Volatile private var generation: Int = 0

    /** Records a user tap that is about to start a YouTube song. */
    fun markTap() {
        tapAt = System.currentTimeMillis()
        generation++
    }

    /** Milliseconds since the last tap, or -1 when there has not been one. */
    fun sinceTap(): Long {
        val at = tapAt
        return if (at == 0L) -1L else System.currentTimeMillis() - at
    }

    /** True when the last tap happened recently enough to explain the current playback event. */
    fun isFreshTap(): Boolean {
        val elapsed = sinceTap()
        return elapsed in 0..FRESH_TAP_MS
    }

    /** Increments on every tap; lets the service log once per tap even for a repeated song. */
    fun tapGeneration(): Int = generation
}