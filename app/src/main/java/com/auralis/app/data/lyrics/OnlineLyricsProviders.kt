package com.auralis.app.data.lyrics

import com.auralis.app.logging.AppLog
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared HTTP plumbing for the online providers: 5 s connect / 8 s read, JSON helpers,
 * failures -> null.
 */
class ProviderHttp(base: OkHttpClient) {
    private val client = base.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        const val CONNECT_TIMEOUT_SECONDS = 5L
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

/**
 * BiniLyrics: `lyrics-api.binimum.org` (which redirects to lrc.red) searches by metadata, then the
 * returned `lyricsUrl` serves Apple word-timed TTML. The URL is gated to the two trusted hosts so a
 * hostile search response cannot point the fetch somewhere else.
 */
class BiniLyricsProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "BiniLyrics"

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
            append(BASE).append("?track=").append(ProviderHttp.encode(title))
            append("&artist=").append(ProviderHttp.encode(artist))
            if (!album.isNullOrBlank()) append("&album=").append(ProviderHttp.encode(album))
            if (seconds != null) append("&duration=").append(seconds)
        }
        val body = http.getText(url) ?: return@withContext null
        val lyricsUrl = pickBiniLyricsUrl(body, title, artist, durationMs)
            ?: return@withContext LyricsResult(id, null, null)
        val ttml = http.getText(lyricsUrl) ?: return@withContext null
        LyricsResult(id, TtmlParser.parse(ttml), null)
    }

    private companion object { const val BASE = "https://lyrics-api.binimum.org/" }
}

/** True for the only hosts BiniLyrics serves lyrics from, over HTTPS (SSRF guard). */
internal fun biniLyricsUrlAllowed(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    return when (uri.host?.lowercase()) {
        "lrc.red", "lyrics-api.binimum.org" -> true
        else -> false
    }
}

/**
 * Best `lyricsUrl` from a BiniLyrics search body: title/artist must match (duration within 3 s),
 * closest duration wins, and the URL must pass [biniLyricsUrlAllowed]. Empty/malformed/no-match
 * bodies yield null.
 */
internal fun pickBiniLyricsUrl(body: String?, title: String, artist: String, durationMs: Long?): String? {
    val results = parseJsonObject(body)?.get("results") as? JsonArray ?: return null
    val target = durationMs?.takeIf { it > 0 }
    var best: String? = null
    var bestDelta = Long.MAX_VALUE
    for (element in results) {
        val row = element as? JsonObject ?: continue
        val lyricsUrl = row.string("lyricsUrl") ?: continue
        if (!biniLyricsUrlAllowed(lyricsUrl)) continue
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
        val delta = if (target != null && candidateMs != null) abs(target - candidateMs) else 0L
        if (best == null || delta < bestDelta) {
            best = lyricsUrl
            bestDelta = delta
        }
    }
    return best
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

/**
 * KuGou (unofficial): the mobile search service finds the song hash, `lyrics.kugou.com` returns a
 * candidate id/accesskey, and the download endpoint returns the LRC as base64.
 *
 * The old `mobilecdn.kugou.com` search host is dead, so the mobile service is used instead. The
 * decoded file opens with credit lines ("Lyrics by: ...") stamped like lyrics, which are cut.
 */
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
        if (title.isBlank()) return@withContext null
        val keyword = "$title $artist".trim()
        val searchBody = http.getText(
            "$SEARCH?version=9108&plat=0&pagesize=8&showtype=0&keyword=${ProviderHttp.encode(keyword)}",
        ) ?: return@withContext null
        for (song in kugouCandidates(searchBody, title, artist, durationMs).take(3)) {
            val candidate = kugouCandidate(hash = song.hash) ?: continue
            val content = download(candidate) ?: continue
            toResult(content)?.let { return@withContext it }
        }
        // Nothing matched by hash: try KuGou's keyword-scoped candidate search as a last resort.
        val fallback = kugouCandidateByKeyword(keyword, durationMs) ?: return@withContext LyricsResult(id, null, null)
        val content = download(fallback) ?: return@withContext LyricsResult(id, null, null)
        toResult(content) ?: LyricsResult(id, null, null)
    }

    private fun kugouCandidate(hash: String): Pair<String, String>? =
        parseKugouCandidate(http.getText("$CANDIDATE?ver=1&man=yes&client=pc&hash=${ProviderHttp.encode(hash)}"))

    private fun kugouCandidateByKeyword(keyword: String, durationMs: Long?): Pair<String, String>? {
        val duration = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } }
        val suffix = duration?.let { "&duration=${it * 1000}" }.orEmpty()
        return parseKugouCandidate(
            http.getText("$CANDIDATE?ver=1&man=yes&client=pc&keyword=${ProviderHttp.encode(keyword)}$suffix"),
        )
    }

    private fun download(candidate: Pair<String, String>): String? {
        val body = http.getText(
            "$DOWNLOAD?fmt=lrc&charset=utf8&client=pc&ver=1&id=${ProviderHttp.encode(candidate.first)}" +
                "&accesskey=${ProviderHttp.encode(candidate.second)}",
        ) ?: return null
        val base64 = parseKugouContent(body) ?: return null
        return runCatching { String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)) }.getOrNull()
    }

    /** Timestamped content is cleaned of KuGou credits; untimed content is kept as plain text. */
    private fun toResult(content: String): LyricsResult? {
        val text = content.replace("\r\n", "\n").trim()
        if (text.isBlank()) return null
        if (KUGOU_STAMPED.containsMatchIn(text)) {
            val stripped = cleanKugouCredits(text)
            if (stripped.isBlank() || isKugouInstrumentalNotice(stripped)) return null
            return LyricsResult(id, stripped, null)
        }
        if (isKugouInstrumentalNotice(text)) return null
        return LyricsResult(id, null, text)
    }

    private companion object {
        const val SEARCH = "https://mobileservice.kugou.com/api/v3/search/song"
        const val CANDIDATE = "https://lyrics.kugou.com/search"
        const val DOWNLOAD = "https://lyrics.kugou.com/download"
    }
}

/** One KuGou search hit: only the hash and its duration matter for the follow-up lookups. */
internal data class KugouSong(val hash: String, val durationSeconds: Long)

private val providerJson = Json { isLenient = true; ignoreUnknownKeys = true }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull ?: (this[key] as? JsonPrimitive)?.intOrNull?.toLong()

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun parseJsonObject(body: String?): JsonObject? =
    body?.let { runCatching { providerJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject

private fun parseJsonArray(body: String?): JsonArray? =
    body?.let { runCatching { providerJson.parseToJsonElement(it) }.getOrNull() } as? JsonArray

/**
 * Songs from a KuGou search body that match the requested title/artist (duration within 5 s),
 * closest duration first. Empty, malformed and non-matching bodies yield an empty list.
 */
internal fun kugouCandidates(body: String?, title: String, artist: String, durationMs: Long?): List<KugouSong> {
    val info = parseJsonObject(body)?.get("data") as? JsonObject
    val songs = info?.get("info") as? JsonArray ?: return emptyList()
    val target = durationMs?.takeIf { it > 0 }
    return songs.mapNotNull { element ->
        val json = element as? JsonObject ?: return@mapNotNull null
        val hash = json.string("hash") ?: return@mapNotNull null
        val duration = json.long("duration")?.takeIf { it > 0 } ?: 0L
        val songTitle = json.string("songname").orEmpty()
        val songArtist = json.string("singername").orEmpty()
        if (!LyricsMatcher.matches(
                requestedTitle = title,
                requestedArtist = artist,
                candidateTitle = songTitle,
                candidateArtist = songArtist,
                requestedDurationMs = target,
                candidateDurationMs = duration.takeIf { it > 0 }?.times(1000L),
                durationToleranceMs = 5_000L,
            )
        ) {
            return@mapNotNull null
        }
        KugouSong(hash, duration)
    }.sortedBy { song -> target?.let { abs(song.durationSeconds - it / 1000L) } ?: 0L }
}

/** The first id/accesskey pair in a KuGou candidate body, or null when there is none. */
internal fun parseKugouCandidate(body: String?): Pair<String, String>? {
    val candidates = parseJsonObject(body)?.get("candidates") as? JsonArray ?: return null
    val first = candidates.firstOrNull() as? JsonObject ?: return null
    val id = first.string("id") ?: return null
    val accessKey = first.string("accesskey") ?: return null
    return id to accessKey
}

/** The base64 LRC payload from a KuGou download body, or null when it is missing/empty. */
internal fun parseKugouContent(body: String?): String? =
    parseJsonObject(body)?.string("content")?.trim()?.takeIf { it.isNotEmpty() }

private val KUGOU_STAMPED = Regex("^\\[\\d{2}:\\d{2}\\.\\d{2,3}\\].*")
private val KUGOU_CREDIT = Regex(".+\\][^\\[]+:.+")
private val KUGOU_STAMP = Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]")
private val KUGOU_METADATA = Regex("^\\[[a-zA-Z]+:.*]$")
private val KUGOU_INSTRUMENTAL = Regex("纯音乐.*欣赏")

/**
 * Drops KuGou's stamped credit lines ("Lyrics by: ...", "Composed by: ...") at the head/tail of the
 * file while keeping `[ti:]`/`[ar:]` metadata, so the result parses as ordinary LRC.
 */
internal fun cleanKugouCredits(lrc: String): String {
    val lines = lrc.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val metadata = lines.filter { KUGOU_METADATA.matches(it.trim()) }
    val stamped = lines.filter { KUGOU_STAMPED.matches(it.trim()) }
    if (stamped.isEmpty()) return ""
    var head = 0
    for (index in minOf(30, stamped.lastIndex) downTo 0) {
        if (KUGOU_CREDIT.containsMatchIn(stamped[index].trim())) {
            head = index + 1
            break
        }
    }
    val body = stamped.drop(head)
    var tail = 0
    for (index in 0..minOf(30, body.lastIndex)) {
        if (KUGOU_CREDIT.containsMatchIn(body[body.lastIndex - index].trim())) {
            tail = index + 1
            break
        }
    }
    val lyrics = body.dropLast(tail)
    if (lyrics.isEmpty()) return ""
    return (metadata + lyrics).joinToString("\n")
}

/** True when the file holds nothing but KuGou's "纯音乐,请欣赏" ("pure music") instrumental notice. */
internal fun isKugouInstrumentalNotice(lrc: String): Boolean {
    val lines = lrc.replace("\r\n", "\n").split('\n')
        .map { KUGOU_STAMP.replace(it, "").trim() }
        .filter { it.isNotEmpty() }
    return lines.isNotEmpty() && lines.all { KUGOU_INSTRUMENTAL.containsMatchIn(it) }
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
 * BetterLyrics (unofficial aggregator): `lyrics-api.boidu.dev` returns Apple word-timed TTML for a
 * title/artist/duration query, which [TtmlParser] turns into enhanced LRC. The previous
 * `lyrics.betterlyrics.com` endpoint does not exist; any failure returns null for the next provider.
 */
class BetterLyricsProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "BetterLyrics"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val seconds = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } }
        val url = buildString {
            append(BASE).append("?s=").append(ProviderHttp.encode(title))
            append("&a=").append(ProviderHttp.encode(artist))
            if (seconds != null) append("&d=").append(seconds)
            if (!album.isNullOrBlank()) append("&al=").append(ProviderHttp.encode(album))
        }
        val body = http.getText(url) ?: return@withContext null
        val ttml = betterLyricsTtml(body)
        val synced = ttml?.let { TtmlParser.parse(it) }
        LyricsResult(id, synced, null)
    }

    private companion object { const val BASE = "https://lyrics-api.boidu.dev/getLyrics" }
}

/** The `ttml` field of a BetterLyrics response body, or null when absent/malformed. */
internal fun betterLyricsTtml(body: String?): String? =
    parseJsonObject(body)?.string("ttml")?.takeIf { it.isNotBlank() }

/**
 * LyricsPlus community mirrors, which serve Apple word-synced lyrics as structured JSON. The
 * `prjktla` mirror is tried first (the `binimum` one answered 404/429 in testing); each mirror can
 * be missing or rate-limited independently, so both are tried before giving up.
 */
class LyricsPlusProvider(private val http: ProviderHttp) : LyricsProvider {
    override val id: String = "LyricsPlus"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val seconds = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } }
        var reached = false
        for (base in MIRRORS) {
            val url = buildString {
                append(base).append("/v2/lyrics/get?title=").append(ProviderHttp.encode(title))
                append("&artist=").append(ProviderHttp.encode(artist))
                if (seconds != null) append("&duration=").append(seconds)
                if (!album.isNullOrBlank()) append("&album=").append(ProviderHttp.encode(album))
                append("&source=").append(SOURCE_ORDER)
            }
            val body = http.getText(url) ?: continue
            reached = true
            val synced = lyricsPlusFromBody(body) ?: continue
            return@withContext LyricsResult(id, synced, null)
        }
        // A mirror answered with nothing: a clean miss. No mirror answered: a transient failure.
        if (reached) LyricsResult(id, null, null) else null
    }

    private companion object {
        val MIRRORS = listOf("https://lyricsplus.prjktla.my.id", "https://lyricsplus.binimum.org")
        const val SOURCE_ORDER = "apple,lyricsplus"
    }
}

/**
 * Converts a LyricsPlus body to enhanced LRC. Each `lyrics[]` row keeps its `syllabus[]` word
 * timings as inline `<mm:ss.mmm>` tags; rows without a syllabus stay line-level. Empty, malformed
 * and lyric-less bodies yield null.
 */
internal fun lyricsPlusFromBody(body: String?): String? {
    val lyrics = parseJsonObject(body)?.get("lyrics") as? JsonArray ?: return null
    val lines = lyrics.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val lineTime = row.long("time") ?: return@mapNotNull null
        if (lineTime < 0) return@mapNotNull null
        val words = (row["syllabus"] as? JsonArray).orEmpty().mapNotNull { syllabus ->
            val word = syllabus as? JsonObject ?: return@mapNotNull null
            val wordTime = word.long("time") ?: return@mapNotNull null
            val text = word.string("text")?.trim().orEmpty()
            if (text.isEmpty()) null else wordTime to text
        }
        lrcLine(lineTime, words, row.string("text")?.trim())
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** One LRC line: word-tagged when timed words exist, otherwise plain text (or an empty gap line). */
private fun lrcLine(timeMs: Long, words: List<Pair<Long, String>>, text: String?): String {
    if (words.isEmpty()) {
        val value = text.orEmpty()
        return if (value.isEmpty()) "[${lrcTimestamp(timeMs)}]" else "[${lrcTimestamp(timeMs)}]$value"
    }
    return buildString {
        append('[').append(lrcTimestamp(timeMs)).append(']')
        words.forEach { (time, word) ->
            append('<').append(lrcTimestamp(time)).append('>').append(word).append(' ')
        }
    }.trimEnd()
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
