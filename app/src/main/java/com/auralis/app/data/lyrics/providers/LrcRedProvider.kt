package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import com.auralis.app.data.lyrics.TtmlParser
import com.auralis.app.data.lyrics.double
import com.auralis.app.data.lyrics.parseJsonObject
import com.auralis.app.data.lyrics.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.abs

/**
 * lrc.red, the community TTML database used by Better Lyrics / BiniLyrics.
 *
 * lrc.red itself only serves files at `https://lrc.red/s/<isrc>.ttml`; its metadata search runs
 * through the public binimum index, which returns those exact `lyricsUrl` values. We search there,
 * keep only lrc.red-hosted hits, then fetch the TTML and convert it with [TtmlParser].
 */
class LrcRedProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "LrcRed"
    override val displayName: String = "lrc.red"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val seconds = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } }
        val url = buildString {
            append(SEARCH).append("?track=").append(ProviderHttp.encode(title))
            append("&artist=").append(ProviderHttp.encode(artist))
            if (!album.isNullOrBlank()) append("&album=").append(ProviderHttp.encode(album))
            if (seconds != null) append("&duration=").append(seconds)
        }
        val body = http.getText(url) ?: return@withContext null
        val lyricsUrl = pickLrcRedUrl(body, title, artist, durationMs)
            ?: return@withContext LyricsResult(id, null, null)
        val ttml = http.getText(lyricsUrl) ?: return@withContext null
        LyricsResult(id, TtmlParser.parse(ttml), null)
    }

    private companion object {
        const val SEARCH = "https://lyrics-api.binimum.org/"
    }
}

/** True for the only hosts lrc.red serves TTML from, over HTTPS (SSRF guard). */
internal fun lrcRedUrlAllowed(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    return uri.host?.lowercase() in setOf("lrc.red", "lyrics-api.binimum.org")
}

/**
 * Best lrc.red `lyricsUrl` from a binimum search body. Title/artist must match and duration must be
 * within 3 s; syllable-timed results win ties, then the closest duration.
 */
internal fun pickLrcRedUrl(body: String?, title: String, artist: String, durationMs: Long?): String? {
    val results = parseJsonObject(body)?.get("results") as? kotlinx.serialization.json.JsonArray ?: return null
    val target = durationMs?.takeIf { it > 0 }
    var best: String? = null
    var bestScore = Int.MIN_VALUE
    var bestDelta = Long.MAX_VALUE
    for (element in results) {
        val row = element as? kotlinx.serialization.json.JsonObject ?: continue
        val lyricsUrl = row.string("lyricsUrl") ?: continue
        if (!lrcRedUrlAllowed(lyricsUrl)) continue
        if (!lyricsUrl.contains("lrc.red")) continue
        val candidateMs = row.double("duration")?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        if (!LyricsMatcher.matches(
                requestedTitle = title,
                requestedArtist = artist,
                candidateTitle = row.string("track_name").orEmpty(),
                candidateArtist = row.string("artist_name").orEmpty(),
                requestedDurationMs = target,
                candidateDurationMs = candidateMs,
            )
        ) {
            continue
        }
        val score = when (row.string("timing_type")?.lowercase().orEmpty()) {
            // binimum reports `word` (per-word) or `line` (per-line) for lrc.red entries.
            "word", "syllable" -> 1
            else -> 0
        }
        val delta = if (target != null && candidateMs != null) abs(target - candidateMs) else 0L
        if (best == null || score > bestScore || (score == bestScore && delta < bestDelta)) {
            best = lyricsUrl
            bestScore = score
            bestDelta = delta
        }
    }
    return best
}