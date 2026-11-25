package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import com.auralis.app.data.lyrics.TtmlParser
import com.auralis.app.data.lyrics.parseJsonObject
import com.auralis.app.data.lyrics.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Unison, the crowdsourced Better Lyrics database. It is keyed by YouTube video id and returns
 * TTML (usually rich/word-synced) or plain LRC in `data.lyrics`.
 *
 * Reading needs no key. Unison's licence requires crediting Unison and the submitter when lyrics are
 * shown; see the catalog description in Settings. The provider itself only returns the lyrics text.
 */
class UnisonProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "Unison"
    override val displayName: String = "Unison"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext null
        val url = "$BASE/lyrics?v=${ProviderHttp.encode(videoId)}"
        val body = http.getText(url) ?: return@withContext null
        val root = parseJsonObject(body) ?: return@withContext null
        // A miss is an HTTP 404 (`{"success":false,...}`), already turned into null by the client.
        // Success wraps the entry as `data: { lyrics, song, artist, format, syncType, ... }`.
        val data = root["data"] as? JsonObject ?: return@withContext LyricsResult(id, null, null)
        val raw = data.string("lyrics") ?: return@withContext LyricsResult(id, null, null)
        if (!LyricsMatcher.matches(
                requestedTitle = title,
                requestedArtist = artist,
                candidateTitle = data.string("song").orEmpty(),
                candidateArtist = data.string("artist").orEmpty(),
                requestedDurationMs = durationMs?.takeIf { it > 0 },
                candidateDurationMs = null,
            )
        ) {
            return@withContext LyricsResult(id, null, null)
        }
        when (data.string("format")?.lowercase()) {
            "ttml" -> LyricsResult(id, TtmlParser.parse(raw), null)
            "lrc" -> LyricsResult(id, raw.takeIf { LRC_TIMESTAMP.containsMatchIn(it) }, null)
            "plain" -> LyricsResult(id, null, raw)
            else -> when {
                raw.contains("<tt", ignoreCase = true) -> LyricsResult(id, TtmlParser.parse(raw), null)
                LRC_TIMESTAMP.containsMatchIn(raw) -> LyricsResult(id, raw, null)
                else -> LyricsResult(id, null, raw)
            }
        }
    }

    private companion object {
        const val BASE = "https://unison.betterlyrics.org"
        val LRC_TIMESTAMP = Regex("\\[\\d{1,3}:\\d{2}")
    }
}