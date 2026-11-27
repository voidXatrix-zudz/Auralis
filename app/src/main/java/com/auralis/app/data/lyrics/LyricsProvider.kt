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
 * Online lyrics source. Implementations must be safe to call off the main thread, honour the
 * per-provider budget, and return null on any failure (never throw) so one bad provider cannot
 * block the others.
 */
interface LyricsProvider {
    /** Stable id used in settings and the cache (e.g. "LRCLIB", "LrcRed"). */
    val id: String

    /** Stable tie-break order; quality and match score always take precedence. */
    val priority: Int get() = 100

    /** Name shown in Settings; defaults to [id]. */
    val displayName: String get() = id

    /** True when the source can return timestamped lines. */
    val supportsSynced: Boolean get() = true

    /** True when the source can return per-word timing (enhanced LRC / TTML / QRC). */
    val supportsWordSync: Boolean get() = false

    /** Plain-text-only fallbacks (Genius) rank below every timed source and run last. */
    val plainOnly: Boolean get() = false

    /** When true the engine puts this provider after all others, regardless of user order. */
    val alwaysLast: Boolean get() = plainOnly

    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult?

    /** Candidate-oriented API used by the aggregator. Existing providers inherit a one-candidate adapter. */
    suspend fun searchCandidates(query: LyricsQuery): List<LyricsCandidate> {
        val hit = search(query.title, query.artist, query.album, query.durationMs, query.videoId) ?: return emptyList()
        val raw = hit.raw ?: return emptyList()
        return listOf(LyricsCandidate(id, query.title, query.artist, query.album, query.durationMs, raw, hit.hasSynced))
    }

    suspend fun fetch(candidate: LyricsCandidate): LyricsResult =
        LyricsResult(id, candidate.raw.takeIf { candidate.synced }, candidate.raw.takeUnless { candidate.synced })
}

data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val videoId: String? = null,
)

data class LyricsCandidate(
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val raw: String,
    val synced: Boolean,
)

/** Whether a catalog entry can actually be used right now. */
enum class ProviderAvailability {
    /** Works without extra configuration. */
    READY,

    /** Works once the matching key/token is filled in Settings; skipped silently until then. */
    NEEDS_KEY,

    /** No verified public endpoint (or required identifier) — registered disabled. */
    NOT_AVAILABLE,
}

/** A provider plus a human description, used by the Settings list. */
data class LyricsProviderInfo(
    val id: String,
    val label: String,
    val description: String,
    val availability: ProviderAvailability = ProviderAvailability.READY,
    /** Optional per-provider credential, rendered as a field in Settings > Lyrics. */
    val credential: ProviderCredential? = null,
)

/** Credential fields a provider may need. The value is persisted in DataStore (encrypted). */
enum class ProviderCredential(val settingLabel: String) {
    PAXSENIX("PaxSenix API key"),
    MUSIXMATCH("Musixmatch user token"),
    BETTERLYRICS("Better Lyrics API key (optional)"),
    GENIUS("Genius access token"),
}
