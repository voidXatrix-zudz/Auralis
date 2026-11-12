package com.auralis.app.data.lyrics

import com.auralis.app.logging.AppLog
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Shared HTTP plumbing for the online providers: 8 s budget, JSON helpers, failures -> null. */
class ProviderHttp(base: OkHttpClient) {
    private val client = base.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        runCatching {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) null else JSONObject(body)
            }
        }.getOrNull()

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) null else body
            }
        }.getOrNull()

    fun postJson(url: String, payload: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        runCatching {
            val builder = Request.Builder().url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) null else JSONObject(body)
            }
        }.getOrNull()

    companion object {
        const val TIMEOUT_SECONDS = 8L
        const val USER_AGENT = "AURALIS/1.2.0 (https://github.com/auralis)"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

/** LRCLIB, the existing provider #1, wrapped into the provider interface (behaviour unchanged). */
class LrclibProvider(private val client: LrclibClient = LrclibClient()) : LyricsProvider {
    override val id: String = "LRCLIB"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = when (val result = client.lookup(
        title = title,
        artist = artist.takeIf { it.isNotBlank() },
        album = album?.takeIf { it.isNotBlank() },
        durationSeconds = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } },
    )) {
        null -> null
        LrclibResult.NotFound -> LyricsResult(id, null, null)
        is LrclibResult.Found -> LyricsResult(id, result.synced, result.plain)
    }
}

/** lyrics.ovh: plain text only, no timestamps. Last-resort fallback. */
class LyricsOvhProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "LyricsOvh"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext null
        val url = "$BASE/${ProviderHttp.encode(artist)}/${ProviderHttp.encode(title)}"
        val json = http.getJson(url) ?: return@withContext null
        val text = json.optString("lyrics").replace("\r\n", "\n").trim()
        if (text.isBlank()) null else LyricsResult(id, null, text)
    }

    private companion object { const val BASE = "https://api.lyrics.ovh/v1" }
}

/** NetEase Cloud Music (unofficial): search then the synced LRC endpoint. */
class NetEaseProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "NetEase"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()
        if (query.isBlank()) return@withContext null
        val search = http.getJson(
            "$SEARCH?s=${ProviderHttp.encode(query)}&type=1&offset=0&total=true&limit=5",
            HEADERS,
        ) ?: return@withContext null
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val songTitle = song.optString("name")
            val songArtist = artistNames(song.optJSONArray("artists"))
            val duration = song.optLong("duration", 0L).takeIf { it > 0 }
            if (!LyricsMatcher.matches(title, artist, songTitle, songArtist, durationMs, duration)) continue
            val id = song.optLong("id")
            val lyric = http.getJson("$LYRIC?id=$id&lv=1&kv=1&tv=-1", HEADERS)
                ?: return@withContext null
            if (lyric.optBoolean("nolyric") || lyric.optBoolean("uncollected")) {
                return@withContext LyricsResult(this@NetEaseProvider.id, null, null)
            }
            val synced = lyric.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            if (synced != null) return@withContext LyricsResult(this@NetEaseProvider.id, synced, null)
        }
        LyricsResult(id, null, null)
    }

    private fun artistNames(array: JSONArray?): String {
        if (array == null) return ""
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    private companion object {
        const val SEARCH = "https://music.163.com/api/search/get/web"
        const val LYRIC = "https://music.163.com/api/song/lyric"
        val HEADERS = mapOf("Referer" to "https://music.163.com")
    }
}

/** KuGou (unofficial): search then the krc/lrc download endpoint. */
class KuGouProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "KuGou"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()
        if (query.isBlank()) return@withContext null
        val search = http.getJson(
            "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${ProviderHttp.encode(query)}&page=1&pagesize=5",
        ) ?: return@withContext null
        val info = search.optJSONObject("data")?.optJSONArray("info") ?: return@withContext null
        for (i in 0 until info.length()) {
            val song = info.optJSONObject(i) ?: continue
            val songTitle = song.optString("songname")
            val songArtist = song.optString("singername")
            val duration = song.optLong("duration", 0L).takeIf { it > 0 }?.times(1000L)
            if (!LyricsMatcher.matches(title, artist, songTitle, songArtist, durationMs, duration)) continue
            val hash = song.optString("hash")
            val durationSec = song.optLong("duration", 0L)
            val candidate = lyricCandidate(hash, durationSec) ?: continue
            val content = downloadLyric(candidate.first, candidate.second) ?: continue
            val synced = if (content.contains("[")) content else null
            return@withContext LyricsResult(id, synced, if (synced == null) content else null)
        }
        LyricsResult(id, null, null)
    }

    private fun lyricCandidate(hash: String, durationSeconds: Long): Pair<String, String>? {
        val json = http.getJson(
            "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&hash=$hash" +
                "&duration=${durationSeconds * 1000}&album_id=0",
        ) ?: return null
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id")
        val accessKey = first.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return null
        return id to accessKey
    }

    private fun downloadLyric(id: String, accessKey: String): String? {
        val json = http.getJson(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8",
        ) ?: return null
        val base64 = json.optString("content").takeIf { it.isNotBlank() } ?: return null
        return runCatching { String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)) }.getOrNull()
    }
}

/** SimpMusic community lyrics API, keyed by YouTube video id (YouTube songs only). */
class SimpMusicProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "SimpMusic"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext null
        val body = http.getText("$BASE/$videoId") ?: return@withContext null
        val text = extractLyrics(body) ?: return@withContext null
        val synced = if (text.contains(Regex("\\[\\d{1,2}:\\d{2}"))) text else null
        LyricsResult(id, synced, if (synced == null) text else null)
    }

    /** The endpoint is usually raw LRC but occasionally wraps it in JSON. */
    private fun extractLyrics(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed.takeIf { it.isNotBlank() }
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        for (key in listOf("lyrics", "lrc", "syncedLyrics", "plainLyrics")) {
            val value = json.optString(key).takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private companion object { const val BASE = "https://api-lyrics.simpmusic.org/v1" }
}

/**
 * BetterLyrics community aggregator (unofficial). Word-synced LRC where the community has it.
 * Endpoint availability varies; any failure returns null and is logged by the repository.
 */
class BetterLyricsProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "BetterLyrics"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val query = "$title $artist".trim()
        val json = http.getJson("$BASE?q=${ProviderHttp.encode(query)}") ?: return@withContext null
        val text = json.optString("lyrics").takeIf { it.isNotBlank() }
            ?: json.optString("lrc").takeIf { it.isNotBlank() }
            ?: return@withContext null
        val synced = if (text.contains(Regex("\\[\\d{1,2}:\\d{2}"))) text else null
        LyricsResult(id, synced, if (synced == null) text else null)
    }

    private companion object { const val BASE = "https://lyrics.betterlyrics.com/api/search" }
}

/**
 * YouTube Music lyrics via InnerTube: `next` exposes a "Lyrics" tab whose browseId is then
 * browsed for the description shelf. Unsynchronised text only.
 */
class YouTubeMusicLyricsProvider(http: ProviderHttp) : LyricsProvider {
    private val http = http
    override val id: String = "YouTubeMusic"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext null
        val lyricsBrowseId = findLyricsBrowseId(videoId) ?: return@withContext null
        val text = browseLyrics(lyricsBrowseId) ?: return@withContext null
        LyricsResult(id, null, text.takeIf { it.isNotBlank() })
    }

    private fun findLyricsBrowseId(videoId: String): String? {
        val payload = buildObject(
            "videoId" to videoId,
            "playlistId" to "RDAMVM$videoId",
            "isAudioOnly" to true,
        )
        val root = http.postJson(NEXT_URL, payload, HEADERS) ?: return null
        return findTabBrowseId(root, "lyrics")
    }

    private fun findTabBrowseId(element: Any?, wanted: String): String? {
        when (element) {
            is JSONObject -> {
                val tab = element.optJSONObject("tabRenderer")
                if (tab != null) {
                    val title = tab.optJSONObject("title")?.let { runsText(it) }
                    if (title != null && title.equals(wanted, ignoreCase = true)) {
                        val browseId = tab.optJSONObject("endpoint")
                            ?.optJSONObject("browseEndpoint")?.optString("browseId")
                        if (!browseId.isNullOrBlank()) return browseId
                    }
                }
                for (key in element.keys()) {
                    findTabBrowseId(element.opt(key), wanted)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until element.length()) {
                findTabBrowseId(element.opt(i), wanted)?.let { return it }
            }
        }
        return null
    }

    private fun browseLyrics(browseId: String): String? {
        val root = http.postJson(BROWSE_URL, buildObject("browseId" to browseId), HEADERS) ?: return null
        val description = findDescriptionShelf(root) ?: return null
        return runsText(description)
    }

    private fun findDescriptionShelf(element: Any?): JSONObject? {
        when (element) {
            is JSONObject -> {
                element.optJSONObject("musicDescriptionShelfRenderer")?.let { return it }
                for (key in element.keys()) findDescriptionShelf(element.opt(key))?.let { return it }
            }
            is JSONArray -> for (i in 0 until element.length()) findDescriptionShelf(element.opt(i))?.let { return it }
        }
        return null
    }

    private fun runsText(element: JSONObject): String? {
        val runs = element.optJSONArray("runs") ?: return element.optString("simpleText").takeIf { it.isNotBlank() }
        val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        return text.trim().takeIf { it.isNotBlank() }
    }

    private fun buildObject(vararg pairs: Pair<String, Any?>): String {
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250101.00.00")
            .put("hl", "en")
            .put("gl", "US")
        val context = JSONObject().put("client", client)
        val root = JSONObject().put("context", context)
        pairs.forEach { (key, value) -> if (value != null) root.put(key, value) }
        return root.toString()
    }

    private companion object {
        const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val HEADERS = mapOf(
            "X-Youtube-Client-Name" to "67",
            "X-Youtube-Client-Version" to "1.20250101.00.00",
            "Origin" to "https://music.youtube.com",
        )
    }
}

/** Names + descriptions for the Settings list, in default order. */
object LyricsProviderCatalog {
    val ALL: List<LyricsProviderInfo> = listOf(
        LyricsProviderInfo("LRCLIB", "LRCLIB", "Community synced lyrics · reliable, default"),
        LyricsProviderInfo("SimpMusic", "SimpMusic", "Community lyrics for YouTube songs"),
        LyricsProviderInfo("NetEase", "NetEase Cloud Music", "Unofficial · synced lyrics"),
        LyricsProviderInfo("KuGou", "KuGou", "Unofficial · synced lyrics"),
        LyricsProviderInfo("BetterLyrics", "BetterLyrics", "Unofficial · community, word-synced where available"),
        LyricsProviderInfo("YouTubeMusic", "YouTube Music lyrics", "From the YouTube Music app · unsynced"),
        LyricsProviderInfo("LyricsOvh", "lyrics.ovh", "Plain text, no timestamps"),
    )
}
