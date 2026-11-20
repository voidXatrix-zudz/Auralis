package com.auralis.app.data.youtube

import com.auralis.app.domain.model.StreamQuality

/**
 * One audio-stream resolver taking part in the tap-to-play race.
 *
 * Implementations must be safe to call concurrently, must never touch the main thread, and must not
 * throw for an ordinary "no audio here" outcome — they return `null` (or throw, which the race
 * treats as a loss). A provider that cannot run at all reports `false` from [isAvailable] so the
 * race does not waste a slot on it.
 */
interface StreamProvider {
    /** Which resolver this is; used for race logs, per-network preference and retry routing. */
    val kind: ResolverKind

    /** True when the provider is currently able to serve requests (engine ready, feature enabled). */
    suspend fun isAvailable(): Boolean

    /** A directly playable audio URL for [videoId], or `null` when none could be produced. */
    suspend fun resolveAudio(videoId: String, quality: StreamQuality): String?
}
