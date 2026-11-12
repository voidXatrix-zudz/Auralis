package com.auralis.app.data.artwork

import com.auralis.app.data.db.ProviderCacheDao
import com.auralis.app.data.db.ProviderCacheEntity
import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.data.metadata.MetadataRepository
import com.auralis.app.data.youtube.ARTWORK_FALLBACK_PX
import com.auralis.app.data.youtube.ARTWORK_LARGEST_PX
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds real square album art for a track when the source only provided a video thumbnail (Part J).
 *
 * YouTube songs expose a 4:3 video thumbnail, which is not album art. This resolver looks a cover up
 * through the same metadata providers used elsewhere (Cover Art Archive via MusicBrainz, Deezer,
 * iTunes) using the cleaned title plus first artist, accepts only a close title match, and caches the
 * winner (or a 7-day "none found" marker) in the shared provider cache. The image itself is then
 * disk-cached by Coil at the requested square size — nothing is ever written into the audio file.
 */
class ArtworkResolver(
    private val providerCacheDao: ProviderCacheDao,
    private val metadata: MetadataRepository,
) {
    suspend fun resolve(track: Track): String? = withContext(Dispatchers.IO) {
        val cached = runCatching { providerCacheDao.get(KIND, track.id, PROVIDER) }.getOrNull()
        if (cached != null) {
            if (!cached.found) {
                if (System.currentTimeMillis() - cached.updatedAt < NEGATIVE_TTL_MS) return@withContext null
            } else {
                return@withContext cached.payload.takeIf { it.isNotBlank() }
            }
        }

        val title = LyricsMatcher.cleanTitle(track.title.ifBlank { track.displayName.substringBeforeLast('.') })
        val artist = LyricsMatcher.primaryArtist(track.artist)
        if (title.isBlank()) return@withContext null

        val candidates = runCatching {
            metadata.search(
                title = title,
                artist = artist,
                album = track.album.takeIf { it.isNotBlank() },
                durationMs = track.durationMs.takeIf { it > 0 },
            )
        }.getOrDefault(emptyList())

        val best = candidates.firstOrNull { candidate ->
            !candidate.coverUrl.isNullOrBlank() &&
                LyricsMatcher.similarity(title, candidate.title) >= MIN_TITLE_SIMILARITY &&
                (artist.isBlank() || candidate.artists.isBlank() ||
                    LyricsMatcher.similarity(artist, LyricsMatcher.primaryArtist(candidate.artists)) >= MIN_ARTIST_SIMILARITY)
        }
        val url = best?.coverUrl?.let { normalizeArtworkUrl(it, ARTWORK_LARGEST_PX) }
        runCatching {
            providerCacheDao.upsert(
                ProviderCacheEntity(
                    kind = KIND,
                    trackId = track.id,
                    provider = PROVIDER,
                    payload = url.orEmpty(),
                    found = url != null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        url
    }

    companion object {
        const val KIND = "artwork"
        private const val PROVIDER = "art"
        private const val MIN_TITLE_SIMILARITY = 0.8
        private const val MIN_ARTIST_SIMILARITY = 0.5
        /** A "none found" marker is re-checked after this long. */
        private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** The smaller square variant of a large art URL, used when the large one fails to load. */
        fun smallerVariant(url: String): String = normalizeArtworkUrl(url, ARTWORK_FALLBACK_PX)
    }
}
