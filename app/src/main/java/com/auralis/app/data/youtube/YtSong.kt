package com.auralis.app.data.youtube

import com.auralis.app.domain.model.Track

/** One song returned by the YouTube Music search. */
data class YtSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
) {
    val thumbnail: String? get() = thumbnailUrl
}

typealias SearchResult = YtSong

/**
 * Normalizes YouTube and Google User Content artwork URLs:
 * - Forces https
 * - Handles "//" prefixes
 * - Sets size to =w544-h544 for googleusercontent URLs
 */
fun normalizeArtworkUrl(raw: String): String {
    var url = raw.trim()
    if (url.isBlank()) return url
    if (url.startsWith("//")) {
        url = "https:$url"
    } else if (url.startsWith("http://", ignoreCase = true)) {
        url = "https://" + url.substring(7)
    } else if (!url.startsWith("https://", ignoreCase = true)) {
        url = "https://$url"
    }

    if (url.contains("googleusercontent.com", ignoreCase = true) || url.contains("googleusercontent", ignoreCase = true)) {
        url = when {
            WIDTH_HEIGHT_PATTERN.containsMatchIn(url) -> url.replace(WIDTH_HEIGHT_PATTERN, "=w544-h544")
            SQUARE_SIZE_PATTERN.containsMatchIn(url) -> url.replace(SQUARE_SIZE_PATTERN, "=w544-h544")
            PARAM_SIZE_PATTERN.containsMatchIn(url) -> url.replace(PARAM_SIZE_PATTERN, "=w544-h544")
            else -> "$url=w544-h544"
        }
    }
    return url
}

private val WIDTH_HEIGHT_PATTERN = Regex("=w\\d+-h\\d+.*$")
private val SQUARE_SIZE_PATTERN = Regex("=s\\d+.*$")
private val PARAM_SIZE_PATTERN = Regex("=[^/]*$")

fun fallbackThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/**
 * Maps a remote result onto the app's existing [Track] model so every screen (rows, dock, Now
 * Playing, notification, lyrics) can keep working without a parallel UI model.
 *
 * The URI is a lightweight `auralis-yt://<videoId>` marker. The playback service's
 * ResolvingDataSource turns it into a signed audio URL on the background loading thread, so the
 * queue can hold 10-15 remote items immediately without resolving every stream up front.
 */
fun YtSong.toTrack(): Track = Track(
    id = "$YOUTUBE_TRACK_PREFIX$videoId",
    sourceId = YOUTUBE_SOURCE_ID,
    uri = youtubePlaybackUri(videoId),
    relativePath = "",
    displayName = title,
    sizeBytes = 0L,
    lastModified = 0L,
    ext = "webm",
    mime = "audio/webm",
    title = title,
    artist = artist,
    albumArtist = artist,
    album = album,
    genres = emptyList(),
    trackNumber = null,
    discNumber = null,
    year = null,
    composer = null,
    durationMs = durationMs,
    codec = null,
    container = null,
    sampleRateHz = null,
    bitDepth = null,
    bitrateBps = null,
    channels = null,
    lossless = false,
    replayGainTrackDb = null,
    replayGainAlbumDb = null,
    artKey = null,
    artColor = null,
    addedAt = 0L,
    tagError = null,
    isRemote = true,
    artworkUrl = (thumbnailUrl ?: thumbnail)?.let(::normalizeArtworkUrl) ?: fallbackThumbnailUrl(videoId),
    remoteId = videoId,
)

const val YOUTUBE_SOURCE_ID = "youtube"
const val YOUTUBE_TRACK_PREFIX = "yt:"

/** Mirrors a liked-songs (VLLM) result into the Room like table so it works offline. */
fun YtSong.toYouTubeLikeEntity(): com.auralis.app.data.db.YouTubeLikeEntity =
    com.auralis.app.data.db.YouTubeLikeEntity(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        likedAt = System.currentTimeMillis(),
    )

/** Browser UA used for InnerTube, extraction and the Media3 OkHttp data source. */
const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** Marker scheme handled by PlaybackService's ResolvingDataSource. */
const val YOUTUBE_PLAYBACK_SCHEME = "auralis-yt"

fun youtubePlaybackUri(videoId: String): String = "$YOUTUBE_PLAYBACK_SCHEME://$videoId"

fun videoIdFromPlaybackUri(uri: String): String? =
    runCatching { android.net.Uri.parse(uri).takeIf { it.scheme == YOUTUBE_PLAYBACK_SCHEME }?.host }.getOrNull()
