package com.auralis.app

import com.auralis.app.domain.model.Track

/** Builds a Track with sensible defaults so tests only state what matters. */
fun fakeTrack(
    id: String = "t1",
    ext: String = "flac",
    codec: String? = "FLAC",
    lossless: Boolean = true,
    bitDepth: Int? = 16,
    sampleRateHz: Int? = 44_100,
    bitrateBps: Int? = null,
    durationMs: Long = 200_000,
): Track = Track(
    id = id, sourceId = "s1", uri = "content://media/$id", relativePath = "Album/$id.$ext",
    displayName = "$id.$ext", sizeBytes = 1_000_000, lastModified = 0, ext = ext, mime = "audio/$ext",
    title = "Title $id", artist = "Artist", albumArtist = "Artist", album = "Album", genres = emptyList(),
    trackNumber = 1, discNumber = null, year = 2020, composer = null, durationMs = durationMs,
    codec = codec, container = null, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
    bitrateBps = bitrateBps, channels = 2, lossless = lossless, replayGainTrackDb = null,
    replayGainAlbumDb = null, artKey = null, artColor = null, addedAt = 0, tagError = null,
)
