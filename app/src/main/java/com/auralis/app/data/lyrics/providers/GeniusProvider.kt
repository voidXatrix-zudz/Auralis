package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Genius, used only as a plain-text fallback and always ranked last by the engine
 * ([plainOnly]/[alwaysLast]).
 *
 * Search uses the official API when an access token is configured, otherwise Genius's public search
 * endpoint (no token, the same one the website uses). The song page is then scanned for its
 * `data-lyrics-container="true"` blocks and the markup is reduced to plain text. Any block page,
 * missing container or shape change yields null rather than a broken lyrics screen.
 */
class GeniusProvider(
    private val http: ProviderHttp,
    private val accessToken: String = "",
) : LyricsProvider {
    override val id: String = "Genius"
    override val displayName: String = "Genius"
    override val supportsSynced: Boolean = false
    override val supportsWordSync: Boolean = false
    override val plainOnly: Boolean = true
    override val alwaysLast: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val pageUrl = findSongUrl(title, artist) ?: return@withContext LyricsResult(id, null, null)
        val html = http.getText(pageUrl, PAGE_HEADERS) ?: return@withContext null
        val lyrics = extractLyrics(html) ?: return@withContext LyricsResult(id, null, null)
        LyricsResult(id, null, lyrics)
    }

    private suspend fun findSongUrl(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        val token = accessToken.trim()
        val results = if (token.isNotEmpty()) {
            officialResults(query, token)
        } else {
            publicResults(query)
        } ?: return null

        var best: String? = null
        var bestScore = 0.0
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val candidateTitle = result.optString("title")
            val candidateArtist = result.optString("artist_names")
                .ifBlank { result.optString("primary_artist_names") }
                .ifBlank { result.optJSONObject("primary_artist")?.optString("name").orEmpty() }
            val url = result.optString("url")
            if (!geniusUrlAllowed(url)) continue
            if (!LyricsMatcher.matches(
                    requestedTitle = title,
                    requestedArtist = artist,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateArtist,
                    requestedDurationMs = null,
                    candidateDurationMs = null,
                )
            ) {
                continue
            }
            val score = LyricsMatcher.similarity(title, candidateTitle)
            if (score > bestScore) {
                bestScore = score
                best = url
            }
        }
        return best
    }

    private suspend fun publicResults(query: String): JSONArray? {
        val root = http.getJson("$PUBLIC_SEARCH?q=${ProviderHttp.encode(query)}", SEARCH_HEADERS)
            ?: return null
        val sections = root.optJSONObject("response")?.optJSONArray("sections") ?: return null
        val songs = JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val hits = section.optJSONArray("hits") ?: continue
            for (j in 0 until hits.length()) {
                val result = hits.optJSONObject(j)?.optJSONObject("result") ?: continue
                if (!result.optString("_type").equals("song", ignoreCase = true)) continue
                songs.put(result)
            }
        }
        return songs
    }

    private suspend fun officialResults(query: String, token: String): JSONArray? {
        val root = http.getJson(
            "$API_SEARCH?q=${ProviderHttp.encode(query)}",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
        ) ?: return null
        val hits = root.optJSONObject("response")?.optJSONArray("hits") ?: return null
        val songs = JSONArray()
        for (i in 0 until hits.length()) {
            hits.optJSONObject(i)?.optJSONObject("result")?.let(songs::put)
        }
        return songs
    }

    /** Joins every `data-lyrics-container="true"` block, then strips its markup to plain text. */
    private fun extractLyrics(html: String): String? {
        val raw = StringBuilder()
        var searchFrom = 0
        while (true) {
            val markerAt = html.indexOf(MARKER, searchFrom)
            if (markerAt < 0) break
            val tagEnd = html.indexOf('>', markerAt)
            if (tagEnd < 0) break
            val contentEnd = matchingDivEnd(html, tagEnd + 1) ?: break
            raw.append(html, tagEnd + 1, contentEnd).append('\n')
            searchFrom = contentEnd + CLOSE_DIV.length
        }
        if (raw.isEmpty()) return null
        val text = raw.toString()
            .replace(BR, "\n")
            .replace(P_CLOSE, "\n")
            .replace(ANY_TAG, "")
            .let(::decodeEntities)
            .replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        return text.takeIf { it.isNotBlank() }
    }

    /** Index of the `</div>` that closes the container opened just before [contentStart]. */
    private fun matchingDivEnd(html: String, contentStart: Int): Int? {
        var depth = 1
        var pos = contentStart
        while (pos < html.length) {
            val open = DIV_OPEN.find(html, pos)
            val close = CLOSE_DIV_MATCH.find(html, pos)
            if (close == null) return null
            if (open != null && open.range.first < close.range.first) {
                depth++
                pos = open.range.last + 1
            } else {
                depth--
                if (depth == 0) return close.range.first
                pos = close.range.last + 1
            }
        }
        return null
    }

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private companion object {
        const val PUBLIC_SEARCH = "https://genius.com/api/search/multi"
        const val API_SEARCH = "https://api.genius.com/search"
        const val MARKER = "data-lyrics-container=\"true\""
        const val CLOSE_DIV = "</div>"
        val SEARCH_HEADERS = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0 Safari/537.36",
        )
        val PAGE_HEADERS = SEARCH_HEADERS + ("Accept-Language" to "en-US,en;q=0.9")
        val DIV_OPEN = Regex("<div\\b", RegexOption.IGNORE_CASE)
        val CLOSE_DIV_MATCH = Regex("</div\\s*>", RegexOption.IGNORE_CASE)
        val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        val P_CLOSE = Regex("</p\\s*>", RegexOption.IGNORE_CASE)
        val ANY_TAG = Regex("<[^>]+>")
    }
}

/** True only for Genius song pages over HTTPS. */
internal fun geniusUrlAllowed(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "genius.com" || host.endsWith(".genius.com")
}