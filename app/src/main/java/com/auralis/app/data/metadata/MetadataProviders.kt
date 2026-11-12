package com.auralis.app.data.metadata

import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.ProviderHttp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** One metadata result from a provider; merged field-by-field by [MetadataRepository]. */
data class MetadataCandidate(
    val source: String,
    val title: String = "",
    val artists: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val genre: String = "",
    val coverUrl: String? = null,
    val durationMs: Long? = null,
) {
    /** Compact JSON used for the per-track override cache (YouTube songs). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("title", title)
        put("artists", artists)
        put("albumArtist", albumArtist)
        put("album", album)
        year?.let { put("year", it) }
        trackNumber?.let { put("trackNumber", it) }
        put("genre", genre)
        coverUrl?.let { put("coverUrl", it) }
        durationMs?.let { put("durationMs", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): MetadataCandidate = MetadataCandidate(
            source = json.optString("source"),
            title = json.optString("title"),
            artists = json.optString("artists"),
            albumArtist = json.optString("albumArtist"),
            album = json.optString("album"),
            year = json.optInt("year", 0).takeIf { it > 0 },
            trackNumber = json.optInt("trackNumber", 0).takeIf { it > 0 },
            genre = json.optString("genre"),
            coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
            durationMs = json.optLong("durationMs", 0L).takeIf { it > 0 },
        )
    }
}

/** Online metadata source. Implementations must never throw and honour the shared 8 s budget. */
interface MetadataProvider {
    val id: String
    suspend fun search(title: String, artist: String, album: String?, durationMs: Long?): List<MetadataCandidate>
}

/** Names + descriptions for the Settings list (Part H). */
data class MetadataProviderInfo(val id: String, val label: String, val description: String)

object MetadataProviderCatalog {
    val ALL: List<MetadataProviderInfo> = listOf(
        MetadataProviderInfo("MusicBrainz", "MusicBrainz", "Community database · cover art from the Cover Art Archive"),
        MetadataProviderInfo("Deezer", "Deezer", "Public API · high-resolution cover art"),
        MetadataProviderInfo("iTunes", "iTunes", "Apple Search API · cover art"),
        MetadataProviderInfo("LastFm", "Last.fm", "Genre tags · needs your API key"),
    )
}

/** MusicBrainz + Cover Art Archive. Rate limited to one request per second. */
class MusicBrainzProvider(
    http: ProviderHttp,
    private val version: String,
) : MetadataProvider {
    private val http = http
    override val id: String = "MusicBrainz"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        rateLimit()
        val query = buildString {
            append("recording:\"").append(title.replace("\"", " ")).append("\"")
            if (artist.isNotBlank()) append(" AND artist:\"").append(artist.replace("\"", " ")).append("\"")
        }
        val url = "$BASE/recording?query=${ProviderHttp.encode(query)}&fmt=json&limit=5"
        val root = http.getJson(url, mapOf("User-Agent" to userAgent())) ?: return@withContext emptyList()
        val recordings = root.optJSONArray("recordings") ?: return@withContext emptyList()
        val out = ArrayList<MetadataCandidate>()
        for (i in 0 until recordings.length()) {
            val rec = recordings.optJSONObject(i) ?: continue
            val recTitle = rec.optString("title")
            val recArtist = artistCredit(rec.optJSONArray("artist-credit"))
            val length = rec.optLong("length", 0L).takeIf { it > 0 }
            val releases = rec.optJSONArray("releases")
            val release = releases?.optJSONObject(0)
            val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
            val year = release?.optString("date")?.take(4)?.toIntOrNull()
            val cover = releaseId?.let { "$CAA/release/$it/front-500" }
            out += MetadataCandidate(
                source = id,
                title = recTitle,
                artists = recArtist,
                album = release?.optString("title").orEmpty(),
                year = year,
                coverUrl = cover,
                durationMs = length,
            )
        }
        out
    }

    private fun artistCredit(array: JSONArray?): String {
        if (array == null) return ""
        return (0 until array.length()).mapNotNull { index ->
            val credit = array.optJSONObject(index) ?: return@mapNotNull null
            credit.optJSONObject("artist")?.optString("name")
        }.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun userAgent(): String = "AURALIS/$version (https://github.com/auralis)"

    private suspend fun rateLimit() {
        val now = System.currentTimeMillis()
        rateLock.withLock {
            val wait = lastRequest.get() + 1_000L - now
            if (wait > 0) delay(wait)
            lastRequest.set(System.currentTimeMillis())
        }
    }

    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2"
        const val CAA = "https://coverartarchive.org"
        val rateLock = Mutex()
        val lastRequest = AtomicLong(0L)
    }
}

/** Deezer public API. */
class DeezerProvider(http: ProviderHttp) : MetadataProvider {
    private val http = http
    override val id: String = "Deezer"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val query = buildString {
            append(title)
            if (artist.isNotBlank()) append(" ").append(artist)
        }
        val root = http.getJson("$BASE/search?q=${ProviderHttp.encode(query)}&limit=5")
            ?: return@withContext emptyList()
        val data = root.optJSONArray("data") ?: return@withContext emptyList()
        val out = ArrayList<MetadataCandidate>()
        for (i in 0 until data.length()) {
            val track = data.optJSONObject(i) ?: continue
            val albumObj = track.optJSONObject("album")
            out += MetadataCandidate(
                source = id,
                title = track.optString("title"),
                artists = track.optJSONObject("artist")?.optString("name").orEmpty(),
                albumArtist = track.optJSONObject("artist")?.optString("name").orEmpty(),
                album = albumObj?.optString("title").orEmpty(),
                coverUrl = albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                    ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() },
                durationMs = track.optLong("duration", 0L).takeIf { it > 0 }?.times(1000L),
            )
        }
        out
    }

    private companion object { const val BASE = "https://api.deezer.com" }
}

/** Apple iTunes Search API. */
class ITunesProvider(http: ProviderHttp) : MetadataProvider {
    private val http = http
    override val id: String = "iTunes"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val term = "$title $artist".trim()
        val root = http.getJson("$BASE/search?term=${ProviderHttp.encode(term)}&entity=song&limit=5")
            ?: return@withContext emptyList()
        val results = root.optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<MetadataCandidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val releaseDate = item.optString("releaseDate")
            out += MetadataCandidate(
                source = id,
                title = item.optString("trackName"),
                artists = item.optString("artistName"),
                albumArtist = item.optString("artistName"),
                album = item.optString("collectionName"),
                year = releaseDate.take(4).toIntOrNull(),
                trackNumber = item.optInt("trackNumber", 0).takeIf { it > 0 },
                genre = item.optString("primaryGenreName"),
                coverUrl = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                    ?.replace("100x100bb", "1200x1200bb"),
                durationMs = item.optLong("trackTimeMillis", 0L).takeIf { it > 0 },
            )
        }
        out
    }

    private companion object { const val BASE = "https://itunes.apple.com" }
}

/** Last.fm genre tags. Only enabled once the user supplies an API key. */
class LastFmProvider(http: ProviderHttp, private val apiKey: String) : MetadataProvider {
    private val http = http
    override val id: String = "LastFm"

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || title.isBlank() || artist.isBlank()) return@withContext emptyList()
        val url = "$BASE/2.0/?method=track.getInfo&api_key=${ProviderHttp.encode(apiKey)}" +
            "&artist=${ProviderHttp.encode(artist)}&track=${ProviderHttp.encode(title)}&format=json"
        val root = http.getJson(url) ?: return@withContext emptyList()
        val track = root.optJSONObject("track") ?: return@withContext emptyList()
        val tags = track.optJSONObject("toptags")?.optJSONArray("tag") ?: return@withContext emptyList()
        val genre = (0 until tags.length())
            .mapNotNull { tags.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(", ")
        if (genre.isBlank()) emptyList() else listOf(MetadataCandidate(source = id, genre = genre))
    }

    private companion object { const val BASE = "https://ws.audioscrobbler.com" }
}

/**
 * Runs the enabled metadata providers, ranks candidates by title/artist similarity and duration,
 * and returns them best-first. Each candidate carries only the fields its provider supplied, so the
 * UI can merge a chosen candidate over the existing tags.
 */
class MetadataRepository(
    http: OkHttpClient,
    private val settings: com.auralis.app.data.settings.SettingsRepository?,
    private val version: String,
) {
    private val providerHttp = ProviderHttp(http)
    private val deezer = DeezerProvider(providerHttp)
    private val iTunes = ITunesProvider(providerHttp)
    private val musicBrainz = MusicBrainzProvider(providerHttp, version)

    /** All candidates from every enabled provider, best first. Never throws. */
    suspend fun search(title: String, artist: String, album: String?, durationMs: Long?): List<MetadataCandidate> {
        val current = runCatching { settings?.settings?.first() }.getOrNull()
        val enabled = current?.metadataProvidersEnabled ?: emptySet()
        val results = ArrayList<MetadataCandidate>()

        if ("MusicBrainz" in enabled) {
            runCatching { musicBrainz.search(title, artist, album, durationMs) }
                .onFailure { com.auralis.app.logging.AppLog.w(TAG, "MusicBrainz lookup failed", it) }
                .getOrDefault(emptyList()).let(results::addAll)
        }
        if ("Deezer" in enabled) {
            runCatching { deezer.search(title, artist, album, durationMs) }
                .onFailure { com.auralis.app.logging.AppLog.w(TAG, "Deezer lookup failed", it) }
                .getOrDefault(emptyList()).let(results::addAll)
        }
        if ("iTunes" in enabled) {
            runCatching { iTunes.search(title, artist, album, durationMs) }
                .onFailure { com.auralis.app.logging.AppLog.w(TAG, "iTunes lookup failed", it) }
                .getOrDefault(emptyList()).let(results::addAll)
        }
        if ("LastFm" in enabled) {
            val key = current?.lastFmApiKey.orEmpty()
            if (key.isNotBlank()) {
                runCatching { LastFmProvider(providerHttp, key).search(title, artist, album, durationMs) }
                    .onFailure { com.auralis.app.logging.AppLog.w(TAG, "Last.fm lookup failed", it) }
                    .getOrDefault(emptyList()).let(results::addAll)
            }
        }

        return results.sortedByDescending { score(it, title, artist, durationMs) }
    }

    private fun score(
        candidate: MetadataCandidate,
        title: String,
        artist: String,
        durationMs: Long?,
    ): Double {
        val titleScore = LyricsMatcher.similarity(title, candidate.title)
        val artistScore = if (artist.isBlank() || candidate.artists.isBlank()) 0.5
        else LyricsMatcher.similarity(LyricsMatcher.primaryArtist(artist), LyricsMatcher.primaryArtist(candidate.artists))
        var score = titleScore * 0.7 + artistScore * 0.3
        if (durationMs != null && durationMs > 0 && candidate.durationMs != null && candidate.durationMs > 0) {
            val delta = kotlin.math.abs(durationMs - candidate.durationMs)
            score += when {
                delta <= 3_000 -> 0.2
                delta <= 10_000 -> 0.05
                else -> -0.1
            }
        }
        if (!candidate.coverUrl.isNullOrBlank()) score += 0.02
        return score
    }

    private companion object { const val TAG = "Metadata" }
}
