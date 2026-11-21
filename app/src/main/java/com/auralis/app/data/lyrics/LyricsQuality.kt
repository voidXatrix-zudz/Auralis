package com.auralis.app.data.lyrics

import com.auralis.app.domain.model.Lyrics

/**
 * Ranking used to pick the best lyrics when several providers answer for the same song: word-synced
 * beats line-synced beats plain text, and a higher match confidence breaks ties inside a tier.
 *
 * [preferSynced] mirrors the user's "prefer synced lyrics" setting; when it is off, every result is
 * treated as the same quality tier so confidence alone decides.
 */
internal fun isBetterLyrics(candidate: Lyrics, current: Lyrics, preferSynced: Boolean = true): Boolean {
    val candidateRank = if (preferSynced) candidate.sync.ordinal else 0
    val currentRank = if (preferSynced) current.sync.ordinal else 0
    if (candidateRank != currentRank) return candidateRank > currentRank
    return confidenceScore(candidate) > confidenceScore(current)
}

/** Confidence with the out-of-sync penalty applied; 0.6 when a provider supplied no confidence. */
private fun confidenceScore(lyrics: Lyrics): Double {
    val confidence = lyrics.confidence ?: 0.6
    val outOfSyncPenalty = if (lyrics.outOfSync) 0.25 else 0.0
    return confidence - outOfSyncPenalty
}
