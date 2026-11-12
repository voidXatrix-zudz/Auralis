package com.auralis.app.data.lyrics

/**
 * One lyrics hit. [synced] is timestamped LRC when the provider had it, [plain] is untimed text.
 * Exactly one is normally set; a provider may return both.
 */
data class LyricsResult(
    val provider: String,
    val synced: String?,
    val plain: String?,
) {
    /** The text to store and parse: timestamped when available, otherwise plain. */
    val raw: String? get() = (synced ?: plain)?.takeIf { it.isNotBlank() }
    val hasSynced: Boolean get() = !synced.isNullOrBlank()
}

/**
 * Online lyrics source. Implementations must be safe to call off the main thread, honour the 8 s
 * budget, and return null on any failure (never throw) so one bad provider cannot block the others.
 */
interface LyricsProvider {
    /** Stable id used in settings and the cache (e.g. "LRCLIB", "NetEase"). */
    val id: String

    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult?
}

/** A provider plus a human description, used by the Settings list (Part H). */
data class LyricsProviderInfo(val id: String, val label: String, val description: String)
