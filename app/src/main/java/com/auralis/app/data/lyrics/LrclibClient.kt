package com.auralis.app.data.lyrics

import com.auralis.app.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Outcome of one LRCLIB lookup. */
sealed interface LrclibResult {
    /** LRCLIB knows this song. [synced] is timestamped LRC when available, [plain] otherwise. */
    data class Found(val synced: String?, val plain: String?) : LrclibResult

    /** The API answered but has no lyrics for this song (safe to remember as "checked"). */
    data object NotFound : LrclibResult
}

/**
 * Minimal LRCLIB (https://lrclib.net) client.
 *
 * Lookups are exact first (`/api/get` with title/artist/album/duration) and fall back to
 * `/api/search` for re-tagged files. Every request runs on [Dispatchers.IO] via
 * [runInterruptible], so cancelling the caller (a new song started) interrupts the socket; all
 * failures are swallowed and reported as `null` so playback and the lyrics screen never block.
 */
class LrclibClient {
    suspend fun lookup(track: Track): LrclibResult? {
        val title = metadataTitle(track)
        if (title.isBlank()) return LrclibResult.NotFound
        return lookup(
            title = title,
            artist = cleanField(track.artist),
            album = cleanField(track.album),
            durationSeconds = (track.durationMs / 1000L).toInt().takeIf { it > 0 },
        )
    }

    /** Field-based lookup, so the provider chain can reuse LRCLIB without building a [Track]. */
    suspend fun lookup(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
    ): LrclibResult? = runInterruptible(Dispatchers.IO) {
        if (title.isBlank()) return@runInterruptible LrclibResult.NotFound
        val seconds = durationSeconds

        val exact = buildList {
            add("track_name" to title)
            artist?.let { add("artist_name" to it) }
            album?.let { add("album_name" to it) }
            seconds?.let { add("duration" to it.toString()) }
        }
        val exactReply = request("/api/get", exact) ?: return@runInterruptible null
        if (exactReply.first == HttpURLConnection.HTTP_OK) {
            parseItem(exactReply.second)?.let { return@runInterruptible it }
        } else if (exactReply.first == HTTP_TOO_MANY_REQUESTS || exactReply.first >= 500) {
            // Rate limit / server problem: do not remember this as "no lyrics".
            return@runInterruptible null
        }
        // Anything else (404 unknown, 400 incomplete metadata such as a missing artist) falls
        // through to the search endpoint, which only needs a title.

        val search = buildList {
            add("track_name" to title)
            artist?.let { add("artist_name" to it) }
        }
        val searchReply = request("/api/search", search) ?: return@runInterruptible null
        if (searchReply.first != HttpURLConnection.HTTP_OK) return@runInterruptible null
        bestSearchHit(searchReply.second, title, seconds)
    }

    /** Returns (HTTP status, body) or `null` when the request could not be made at all. */
    private fun request(path: String, params: List<Pair<String, String>>): Pair<Int, String?>? {
        val query = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$BASE_URL$path?$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            code to body
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseItem(body: String?): LrclibResult.Found? = runCatching {
        parseItem(JSONObject(body.orEmpty()))
    }.getOrNull()

    private fun parseItem(json: JSONObject): LrclibResult.Found? {
        val synced = json.optString("syncedLyrics").trim().takeIf { it.isNotEmpty() }
        val plain = json.optString("plainLyrics").trim().takeIf { it.isNotEmpty() }
        return if (synced != null || plain != null) LrclibResult.Found(synced, plain) else null
    }

    /** Picks the closest-duration result, preferring one that has timestamps. */
    private fun bestSearchHit(body: String?, title: String, seconds: Int?): LrclibResult {
        val array = runCatching { JSONArray(body.orEmpty()) }.getOrNull() ?: return LrclibResult.NotFound
        var best: LrclibResult.Found? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val found = runCatching { parseItem(json) }.getOrNull() ?: continue
            var score = if (found.synced != null) 1_000 else 0
            if (json.optString("trackName").trim().equals(title, ignoreCase = true)) score += 200
            val duration = json.optDouble("duration", -1.0)
            if (seconds != null && duration > 0) {
                score -= kotlin.math.abs(duration - seconds).toInt().coerceAtMost(120)
            }
            if (score > bestScore) {
                bestScore = score
                best = found
            }
        }
        return best ?: LrclibResult.NotFound
    }

    private fun cleanField(value: String): String? = value.trim()
        .takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }

    private companion object {
        const val BASE_URL = "https://lrclib.net"
        const val TIMEOUT_MS = 8_000
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val USER_AGENT = "AURALIS/1.0 (https://github.com/auralis)"
    }
}

/**
 * The track metadata sent to LRCLIB. Falls back to a cleaned file name when the title tag is
 * blank or an "unknown" placeholder, so every song is searchable.
 */
internal fun metadataTitle(track: Track): String {
    val tagged = track.title.trim()
    if (tagged.isNotEmpty() && !tagged.equals("<unknown>", ignoreCase = true)) return tagged
    return cleanFileName(track.displayName)
}

/** "01 - Some_Song_name.mp3" -> "Some Song name". */
internal fun cleanFileName(name: String): String {
    // Strip a real file extension only (".mp3", ".flac", ...), not dots inside the title.
    val dot = name.lastIndexOf('.')
    val bare = if (dot > 0 && name.length - dot in 2..6) name.substring(0, dot) else name
    return bare
        .replace(Regex("^\\s*\\d{1,3}\\s*[-._)\\s]+"), "")
        .replace('_', ' ')
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}
