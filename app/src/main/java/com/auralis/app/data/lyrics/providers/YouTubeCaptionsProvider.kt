package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * YouTube video captions, converted to timed LRC lines.
 *
 * This reuses the same keyless InnerTube `player` request the app already uses for audio streams
 * (ANDROID_VR client): the response carries `captions.playerCaptionsTracklistRenderer.captionTracks`,
 * whose `baseUrl` serves the cue list. YouTube currently answers with srv3 XML
 * (`<p t="1360" d="1680">…</p>`), so that is parsed directly; json3 (`events[].segs[]`) is still
 * supported in case a track returns it. Manual captions are preferred over auto-generated (ASR).
 */
class YouTubeCaptionsProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "YouTubeCaptions"
    override val displayName: String = "YouTube captions"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = false

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext null
        val baseUrl = captionTrackUrl(videoId) ?: return@withContext null
        val body = http.getText(baseUrl) ?: return@withContext null
        val lines = parseJson3(body) ?: parseTimedText(body)
        val synced = lines?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: return@withContext LyricsResult(id, null, null)
        LyricsResult(id, synced, null)
    }

    /** First non-ASR caption track, else the first ASR one; only YouTube-hosted URLs are accepted. */
    private suspend fun captionTrackUrl(videoId: String): String? {
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "ANDROID_VR")
                        .put("clientVersion", "1.60.19")
                        .put("deviceModel", "Quest 3")
                        .put("androidSdkVersion", 32)
                        .put("hl", "en")
                        .put("gl", "US"),
                ),
            )
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
        val root = http.postJson(PLAYER_URL, payload, HEADERS) ?: return null
        val tracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return null
        var fallback: String? = null
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val base = track.optString("baseUrl").takeIf { captionUrlAllowed(it) } ?: continue
            if (!track.optString("kind").equals("asr", ignoreCase = true)) return base
            if (fallback == null) fallback = base
        }
        return fallback
    }

    /** json3: `events[].tStartMs` + `segs[].utf8`. Returns null when the body is not json3. */
    private fun parseJson3(body: String): List<String>? {
        if (!body.trimStart().startsWith("{")) return null
        val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: return null
        val cues = sortedMapOf<Long, String>()
        var lastName = ""
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val start = event.optLong("tStartMs", -1L)
            if (start < 0) continue
            val segs = event.optJSONArray("segs") ?: continue
            val text = (0 until segs.length())
                .joinToString("") { segs.optJSONObject(it)?.optString("utf8").orEmpty() }
                .replace("\n", " ")
                .replace(WHITESPACE, " ")
                .trim()
            if (text.isEmpty() || text == lastName) continue
            if (start !in cues) cues[start] = text
            lastName = text
        }
        return cues.map { (start, text) -> lrcAt(start, text) }
    }

    /**
     * srv3 (`<p t="1360" d="1680">text</p>`) with a legacy `<text start="1.36">` fallback.
     * Returns null when neither shape is present.
     */
    private fun parseTimedText(body: String): List<String>? {
        val cues = sortedMapOf<Long, String>()
        for (match in P_TAG.findAll(body)) {
            val start = match.groupValues[1].toLongOrNull() ?: continue
            val text = clean(match.groupValues[2])
            if (text.isNotEmpty()) cues[start] = text
        }
        if (cues.isNotEmpty()) return cues.map { (start, text) -> lrcAt(start, text) }
        for (match in TEXT_TAG.findAll(body)) {
            val startMs = (match.groupValues[1].toDoubleOrNull() ?: continue).let { (it * 1000).toLong() }
            val text = clean(match.groupValues[2])
            if (text.isNotEmpty()) cues[startMs] = text
        }
        return cues.takeIf { it.isNotEmpty() }?.map { (start, text) -> lrcAt(start, text) }
    }

    private fun clean(raw: String): String =
        decodeXml(raw.replace(BR, " "))
            .replace(ANY_TAG, "")
            .replace(WHITESPACE, " ")
            .trim()

    private fun decodeXml(value: String): String {
        if ('&' !in value) return value
        return ENTITY.replace(value) { match ->
            val entity = match.groupValues[1]
            when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                } ?: match.value
            }
        }
    }

    private fun lrcAt(ms: Long, text: String): String {
        val safe = ms.coerceAtLeast(0L)
        return "[%02d:%02d.%03d]%s".format(
            Locale.US,
            safe / 60_000L,
            (safe % 60_000L) / 1_000L,
            safe % 1_000L,
            text,
        )
    }

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        val HEADERS = mapOf(
            "Content-Type" to "application/json",
            "X-YouTube-Client-Name" to "28",
            "X-YouTube-Client-Version" to "1.60.19",
            "User-Agent" to "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; " +
                "en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip",
            "Origin" to "https://www.youtube.com",
        )
        val WHITESPACE = Regex("\\s+")
        val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        val ANY_TAG = Regex("<[^>]+>")
        val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#\\d+|[A-Za-z]+);")
        val P_TAG = Regex(
            "<p\\b[^>]*\\bt=\"(\\d+)\"[^>]*>(.*?)</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val TEXT_TAG = Regex(
            "<text\\b[^>]*\\bstart=\"([^\"]+)\"[^>]*>(.*?)</text>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}

/** Captions are fetched only from YouTube/googlevideo hosts (the player response is untrusted). */
internal fun captionUrlAllowed(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}