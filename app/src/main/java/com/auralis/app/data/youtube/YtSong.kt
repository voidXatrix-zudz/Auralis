package com.auralis.app.data.youtube

import com.auralis.app.domain.model.Track
import kotlinx.serialization.Serializable

/** One song returned by the YouTube Music search. */
@Serializable
data class YtSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val albumId: String? = null,
    val artistId: String? = null,
    val explicit: Boolean = false,
    /** Music videos are playable as audio, but the Home "Music videos" rows label them differently. */
    val isVideo: Boolean = false,
    /** Raw InnerTube `musicVideoType` (`MUSIC_VIDEO_TYPE_ATV`, `..._OMV`, `..._UGC`, ...). */
    val musicVideoType: String? = null,
    val shelfTitle: String = "",
) {
    val thumbnail: String? get() = thumbnailUrl
}

typealias SearchResult = YtSong

/**
 * Normalizes YouTube and Google User Content artwork URLs:
 * - Forces https
 * - Handles "//" prefixes
 * - Sets the requested square size for googleusercontent URLs (largest by default, Part J)
 *
 * [sizePx] defaults to the largest square YouTube Music serves; pass [ARTWORK_FALLBACK_PX] for the
 * smaller variant used when the large one fails to load.
 */
fun normalizeArtworkUrl(raw: String, sizePx: Int = ARTWORK_LARGEST_PX): String {
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
        val requested = "=w$sizePx-h$sizePx"
        url = when {
            WIDTH_HEIGHT_PATTERN.containsMatchIn(url) -> url.replace(WIDTH_HEIGHT_PATTERN, requested)
            SQUARE_SIZE_PATTERN.containsMatchIn(url) -> url.replace(SQUARE_SIZE_PATTERN, requested)
            PARAM_SIZE_PATTERN.containsMatchIn(url) -> url.replace(PARAM_SIZE_PATTERN, requested)
            else -> "$url$requested"
        }
    }
    return url
}

/** The largest square album-art size we request from YouTube Music. */
const val ARTWORK_LARGEST_PX = 1200

/** The smaller square fallback requested when the largest size fails to load. */
const val ARTWORK_FALLBACK_PX = 544

private val WIDTH_HEIGHT_PATTERN = Regex("=w\\d+-h\\d+.*$")
private val SQUARE_SIZE_PATTERN = Regex("=s\\d+.*$")
private val PARAM_SIZE_PATTERN = Regex("=[^/]*$")

fun fallbackThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** True when a URL is the low-res video thumbnail (4:3 letterboxed), not real square album art. */
fun isVideoThumbnailUrl(url: String?): Boolean =
    url != null && url.contains("ytimg.com", ignoreCase = true)

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

/**
 * True when [this] is a song streamed from YouTube Music.
 *
 * A downloaded YouTube song also keeps a `yt:` id and a `remoteId`, but its URI points at a local
 * file. The playback scheme is therefore the decisive test: sending a download down the stream
 * path would ignore the file on disk and re-download it.
 */
fun Track.isYouTubeTrack(): Boolean = uri.startsWith("$YOUTUBE_PLAYBACK_SCHEME://")

/** The queue and start index for one tapped row, plus whether the row was found in the list. */
data class PlaybackStart(val queue: List<Track>, val index: Int, val found: Boolean)

/**
 * Chooses what to hand the player for a tap in [list].
 *
 * When [track] is not in [list] the old code coerced the missing index to 0 and played a completely
 * different song. Here the tapped song is played on its own instead, so a mismatched list can never
 * substitute another track.
 */
fun playbackStart(track: Track, list: List<Track>): PlaybackStart {
    val index = list.indexOfFirst { it.id == track.id }
    return if (index < 0) PlaybackStart(listOf(track), 0, found = false)
    else PlaybackStart(list, index, found = true)
}

/**
 * Sends a tap in a list that mixes local and YouTube tracks down the matching playback path.
 * Kept pure so the routing rule can be unit-tested without Compose or a player.
 */
inline fun routeLibraryPlay(
    track: Track,
    playLocal: (Track) -> Unit,
    playYouTube: (Track) -> Unit,
) {
    if (track.isYouTubeTrack()) playYouTube(track) else playLocal(track)
}

/** A playable card becomes the same remote [Track] the search/radio pipeline already uses. */
fun YtCard.toTrack(): Track = YtSong(
    videoId = videoId.orEmpty(),
    title = title,
    artist = subtitle.ifBlank { "Unknown artist" },
    album = "",
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    explicit = explicit,
    isVideo = type == YtItemType.VIDEO,
    musicVideoType = musicVideoType,
).toTrack()

const val YOUTUBE_SOURCE_ID = "youtube"
const val YOUTUBE_TRACK_PREFIX = "yt:"

/** Mirrors a liked-songs (VLLM) result into the Room like table so it works offline. */
fun YtSong.toYouTubeLikeEntity(
    likedAt: Long = System.currentTimeMillis(),
): com.auralis.app.data.db.YouTubeLikeEntity =
    com.auralis.app.data.db.YouTubeLikeEntity(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        likedAt = likedAt,
    )

/** Browser UA used for InnerTube, extraction and the Media3 OkHttp data source. */
const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** Marker scheme handled by PlaybackService's ResolvingDataSource. */
const val YOUTUBE_PLAYBACK_SCHEME = "auralis-yt"

fun youtubePlaybackUri(videoId: String): String = "$YOUTUBE_PLAYBACK_SCHEME://$videoId"

fun videoIdFromPlaybackUri(uri: String): String? =
    runCatching { android.net.Uri.parse(uri).takeIf { it.scheme == YOUTUBE_PLAYBACK_SCHEME }?.host }.getOrNull()
