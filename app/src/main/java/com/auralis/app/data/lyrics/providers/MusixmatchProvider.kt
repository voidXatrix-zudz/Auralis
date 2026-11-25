package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * Musixmatch via the public desktop endpoint (`apic-desktop.musixmatch.com/ws/1.1`), which is the
 * same API the LyricsPlus backend uses. It needs a user token; when the token field in Settings is
 * empty the provider makes no request and is skipped silently.
 *
 * Search matches title/artist/duration (±3 s), then `track.subtitle.get` returns line-synced LRC.
 * `lyrics.get` is deliberately not used: its `lyrics_body` is a truncated preview with a commercial
 * notice, which would be worse than a clean miss.
 */
class MusixmatchProvider(
    private val http: ProviderHttp,
    private val userToken: String = "",
) : LyricsProvider {
    override val id: String = "Musixmatch"
    override val displayName: String = "Musixmatch"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = false

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val token = userToken.trim()
        if (token.isEmpty() || title.isBlank() || artist.isBlank()) return@withContext null

        val searchUrl = url(
            "track.search",
            "usertoken" to token,
            "q_track" to title,
            "q_artist" to LyricsMatcher.primaryArtist(artist),
            "page_size" to "6",
            "s_track_rating" to "desc",
        )
        val search = http.getJson(searchUrl) ?: return@withContext null
        val tracks = search.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONArray("track_list")
            ?: return@withContext null

        val target = durationMs?.takeIf { it > 0 }
        var bestId: Long? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i)?.optJSONObject("track") ?: continue
            val candidateMs = track.optLong("track_length", 0L).takeIf { it > 0 }?.times(1000L)
            if (!LyricsMatcher.matches(
                    requestedTitle = title,
                    requestedArtist = artist,
                    candidateTitle = track.optString("track_name").orEmpty(),
                    candidateArtist = track.optString("artist_name").orEmpty(),
                    requestedDurationMs = target,
                    candidateDurationMs = candidateMs,
                )
            ) {
                continue
            }
            val delta = if (target != null && candidateMs != null) abs(target - candidateMs) else 0L
            if (delta < bestDelta) {
                bestDelta = delta
                bestId = track.optLong("track_id", 0L).takeIf { it > 0 }
            }
        }
        val trackId = bestId ?: return@withContext LyricsResult(id, null, null)

        val subtitle = http.getJson(
            url(
                "track.subtitle.get",
                "usertoken" to token,
                "track_id" to trackId.toString(),
                "subtitle_format" to "lrc",
            ),
        ) ?: return@withContext null
        if (!isOk(subtitle)) return@withContext LyricsResult(id, null, null)
        val body = subtitle.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONObject("subtitle")
            ?.optString("subtitle_body")
            ?.replace("\r\n", "\n")
            ?.trim()
        if (body.isNullOrBlank()) return@withContext LyricsResult(id, null, null)
        LyricsResult(id, body, null)
    }

    private fun isOk(json: JSONObject): Boolean =
        json.optJSONObject("message")?.optJSONObject("header")?.optInt("status_code", 200) == 200

    private fun url(path: String, vararg params: Pair<String, String>): String = buildString {
        append(BASE).append('/').append(path)
        append("?app_id=").append(APP_ID)
        append("&format=json")
        for ((name, value) in params) {
            append('&').append(name).append('=').append(ProviderHttp.encode(value))
        }
    }

    private companion object {
        const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
        const val APP_ID = "web-desktop-app-v1.0"
    }
}