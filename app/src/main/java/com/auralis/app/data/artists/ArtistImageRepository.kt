package com.auralis.app.data.artists

import com.auralis.app.data.db.ArtistImageDao
import com.auralis.app.data.db.ArtistImageEntity
import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.lyrics.ProviderHttp
import com.auralis.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Resolves a circular artist photo (Part L) with a strict priority:
 * 1. an InnerTube thumbnail the caller already has in hand (used as-is and cached),
 * 2. the artist page header image via a browse request when only a browse id is known,
 * 3. a Deezer public artist search (no key) for artists without a YouTube id, on a close name match.
 *
 * Winners and 7-day "none found" markers are cached in Room (`artist_images`); the image itself is
 * disk-cached by Coil. At most [MAX_CONCURRENT] lookups run at once so scrolling is never blocked.
 */
class ArtistImageRepository(
    private val dao: ArtistImageDao,
    private val settingsRepo: com.auralis.app.data.settings.SettingsRepository,
    private val youtube: YouTubeRepository,
    http: OkHttpClient,
) {
    private val http = ProviderHttp(http)
    private val gate = Semaphore(MAX_CONCURRENT)

    /**
     * Returns a square image URL for [name] (and [browseId] when known), or null when none exists.
     * [knownUrl] is the thumbnail already attached to the card/track, if any.
     */
    suspend fun resolve(name: String, browseId: String?, knownUrl: String?): String? = withContext(Dispatchers.IO) {
        if (name.isBlank() && browseId == null) return@withContext null
        val key = keyFor(name, browseId)
        if (!knownUrl.isNullOrBlank()) {
            store(key, knownUrl, SOURCE_YT)
            return@withContext knownUrl
        }
        val settings = runCatching { settingsRepo.settings.first() }.getOrNull()
        if (settings?.fetchArtistPhotos == false) return@withContext null

        val cached = runCatching { dao.get(key) }.getOrNull()
        if (cached != null) {
            if (!cached.url.isBlank()) return@withContext cached.url
            if (System.currentTimeMillis() - cached.fetchedAt < NEGATIVE_TTL_MS) return@withContext null
        }

        val resolved = gate.withPermit {
            when {
                browseId != null -> youtube.artistImage(browseId)
                settings?.useDeezerForArtistPhotos != false -> deezerArtistImage(name)
                else -> null
            }
        }
        store(key, resolved.orEmpty(), if (resolved != null) SOURCE_RESOLVED else SOURCE_NONE)
        resolved
    }

    /** Drops every cached artist photo and negative marker (Settings "clear cache"). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { dao.clearAll() }
        Unit
    }

    private suspend fun deezerArtistImage(name: String): String? = withContext(Dispatchers.IO) {
        val query = LyricsMatcher.primaryArtist(name)
        if (query.isBlank()) return@withContext null
        val root = http.getJson("$DEEZER_BASE/search/artist?q=${ProviderHttp.encode(query)}&limit=5")
            ?: return@withContext null
        val data = root.optJSONArray("data") ?: return@withContext null
        var best: String? = null
        var bestScore = 0.0
        for (i in 0 until data.length()) {
            val artist = data.optJSONObject(i) ?: continue
            val score = LyricsMatcher.similarity(query, artist.optString("name"))
            if (score > bestScore) {
                bestScore = score
                best = artist.optString("picture_xl").takeIf { it.isNotBlank() }
                    ?: artist.optString("picture_big").takeIf { it.isNotBlank() }
            }
        }
        if (bestScore >= MIN_NAME_SIMILARITY) best else null
    }

    private suspend fun store(key: String, url: String, source: String) {
        runCatching {
            dao.upsert(ArtistImageEntity(key = key, url = url, source = source, fetchedAt = System.currentTimeMillis()))
        }
    }

    private fun keyFor(name: String, browseId: String?): String =
        if (browseId != null) "yt:$browseId" else "name:${LyricsMatcher.normalize(LyricsMatcher.primaryArtist(name))}"

    private companion object {
        const val DEEZER_BASE = "https://api.deezer.com"
        const val MAX_CONCURRENT = 3
        const val MIN_NAME_SIMILARITY = 0.75
        /** A "none found" marker is re-queried after this long. */
        const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val SOURCE_YT = "innertube"
        const val SOURCE_RESOLVED = "resolved"
        const val SOURCE_NONE = "none"
    }
}
