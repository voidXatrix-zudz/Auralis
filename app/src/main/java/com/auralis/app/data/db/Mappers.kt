package com.auralis.app.data.db

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
