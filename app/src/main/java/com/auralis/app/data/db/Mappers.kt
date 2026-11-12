package com.auralis.app.data.db

import com.auralis.app.data.youtube.YOUTUBE_SOURCE_ID
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.data.youtube.fallbackThumbnailUrl
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.data.youtube.youtubePlaybackUri
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.SourceKind
import com.auralis.app.domain.model.Track

private const val GENRE_SEPARATOR = '\u001F'

fun SourceEntity.toDomain(): MusicSource = MusicSource(
    id = id,
    kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.TREE),
    name = name,
    treeUri = treeUri,
    addedAt = addedAt,
    lastScanAt = lastScanAt,
)

fun TrackEntity.toDomain(): Track = Track(
    id = id, sourceId = sourceId, uri = uri, relativePath = relativePath, displayName = displayName,
    sizeBytes = sizeBytes, lastModified = lastModified, ext = ext, mime = mime, title = title,
    artist = artist, albumArtist = albumArtist, album = album,
    genres = if (genres.isEmpty()) emptyList() else genres.split(GENRE_SEPARATOR),
    trackNumber = trackNumber, discNumber = discNumber, year = year, composer = composer,
    durationMs = durationMs, codec = codec, container = container, sampleRateHz = sampleRateHz,
    bitDepth = bitDepth, bitrateBps = bitrateBps, channels = channels, lossless = lossless,
    replayGainTrackDb = replayGainTrackDb, replayGainAlbumDb = replayGainAlbumDb,
    artKey = artKey, artColor = artColor, addedAt = addedAt, tagError = tagError,
)

fun Track.toYouTubeRecentEntity(playCount: Int = 0): YouTubeRecentEntity = YouTubeRecentEntity(
    videoId = remoteId ?: id.removePrefix(YOUTUBE_TRACK_PREFIX),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = artworkUrl,
    playedAt = System.currentTimeMillis(),
    playCount = playCount,
)

fun Track.toYouTubeLikeEntity(): YouTubeLikeEntity = YouTubeLikeEntity(
    videoId = remoteId ?: id.removePrefix(YOUTUBE_TRACK_PREFIX),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = artworkUrl,
    likedAt = System.currentTimeMillis(),
)

private fun remoteTrack(
    videoId: String,
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
    thumbnailUrl: String?,
    addedAt: Long,
): Track = Track(
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
    addedAt = addedAt,
    tagError = null,
    isRemote = true,
    artworkUrl = thumbnailUrl?.takeIf { it.isNotBlank() }?.let(::normalizeArtworkUrl) ?: fallbackThumbnailUrl(videoId),
    remoteId = videoId,
)

fun YouTubeLikeEntity.toDomain(): Track = remoteTrack(videoId, title, artist, album, durationMs, thumbnailUrl, likedAt)

fun YouTubeRecentEntity.toDomain(): Track = remoteTrack(videoId, title, artist, album, durationMs, thumbnailUrl, playedAt)

fun PlaylistYtEntryEntity.toDomain(): Track = remoteTrack(videoId, title, artist, album, durationMs, thumbnailUrl, 0L)

/** Offline download as a local (non-remote) track so it plays without network. */
fun DownloadEntity.toDomain(): Track = Track(
    id = "$YOUTUBE_TRACK_PREFIX$videoId",
    sourceId = YOUTUBE_SOURCE_ID,
    uri = java.io.File(filePath).toURI().toString(),
    relativePath = "",
    displayName = title,
    sizeBytes = sizeBytes,
    lastModified = downloadedAt,
    ext = format.lowercase(),
    mime = if (format.equals("Opus", ignoreCase = true)) "audio/ogg" else "audio/mp4",
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
    codec = format,
    container = null,
    sampleRateHz = null,
    bitDepth = null,
    bitrateBps = null,
    channels = null,
    lossless = false,
    replayGainTrackDb = null,
    replayGainAlbumDb = null,
    artKey = artPath,
    artColor = null,
    addedAt = downloadedAt,
    tagError = null,
    isRemote = false,
    artworkUrl = artPath,
    remoteId = videoId,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id, sourceId = sourceId, uri = uri, relativePath = relativePath, displayName = displayName,
    sizeBytes = sizeBytes, lastModified = lastModified, ext = ext, mime = mime, title = title,
    artist = artist, albumArtist = albumArtist, album = album,
    genres = genres.joinToString(GENRE_SEPARATOR.toString()),
    trackNumber = trackNumber, discNumber = discNumber, year = year, composer = composer,
    durationMs = durationMs, codec = codec, container = container, sampleRateHz = sampleRateHz,
    bitDepth = bitDepth, bitrateBps = bitrateBps, channels = channels, lossless = lossless,
    replayGainTrackDb = replayGainTrackDb, replayGainAlbumDb = replayGainAlbumDb,
    artKey = artKey, artColor = artColor, addedAt = addedAt, tagError = tagError,
)
