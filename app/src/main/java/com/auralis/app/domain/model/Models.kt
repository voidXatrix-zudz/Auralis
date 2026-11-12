package com.auralis.app.domain.model

/** Where a library track was discovered. */
enum class SourceKind { MEDIA_STORE, TREE }

enum class RepeatMode { OFF, ALL, ONE }

enum class ThemeMode { DARK, LIGHT }

/** Full-screen photo drawn behind the Now Playing player. NONE keeps the existing look. */
enum class PlayerBackground { NONE, PHOTO_1, PHOTO_2 }

enum class ReplayGainMode { OFF, TRACK, ALBUM }

enum class LyricsSource { EMBEDDED, LRC, USER, LRCLIB }

/** One timed word inside an enhanced-LRC line. */
data class LrcWord(val timeMs: Long, val text: String)

/**
 * One timestamped lyric line. [words] is only populated for enhanced LRC (inline <mm:ss.xx> tags);
 * it stays empty for ordinary line-synced lyrics so existing behaviour is unchanged.
 */
data class LrcLine(
    val timeMs: Long,
    val text: String,
    val words: List<LrcWord> = emptyList(),
)

/**
 * Lyrics for a track. [raw] is the original text (LRC or plain) so the user can edit it;
 * [synced] is non-null only when timestamps were parsed.
 */
data class Lyrics(
    val source: LyricsSource,
    val raw: String,
    val synced: List<LrcLine>?,
    val plain: String?,
    /** Name of the online provider that supplied these lyrics (e.g. "LRCLIB", "NetEase"), or null for local sources. */
    val provider: String? = null,
)

/** A music file in the library. Mirrors the Track model of the AURALIS web reference. */
data class Track(
    val id: String,
    val sourceId: String,
    /** content:// URI used for playback and metadata reads. */
    val uri: String,
    /** Path relative to the source root, e.g. "Album/01 Song.flac". Used for folder views. */
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val ext: String,
    val mime: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genres: List<String>,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val composer: String?,
    val durationMs: Long,
    val codec: String?,
    val container: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateBps: Int?,
    val channels: Int?,
    val lossless: Boolean,
    val replayGainTrackDb: Float?,
    val replayGainAlbumDb: Float?,
    /** Key of the cached artwork file, null when the track has no artwork. */
    val artKey: String?,
    /** Dominant artwork colour as ARGB, used for the Now Playing background. */
    val artColor: Int?,
    val addedAt: Long,
    val tagError: String?,
    /** True for streamed sources (YouTube) that are not indexed into the local library. */
    val isRemote: Boolean = false,
    /** Remote artwork URL for streamed songs; local tracks keep using [artKey]. */
    val artworkUrl: String? = null,
    /** Provider-specific id for streamed songs (the YouTube video id). */
    val remoteId: String? = null,
) {
    val thumbnail: String? get() = artworkUrl
}

data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A folder or storage source the user added. */
data class MusicSource(
    val id: String,
    val kind: SourceKind,
    val name: String,
    /** SAF tree URI for [SourceKind.TREE]; null for MediaStore. */
    val treeUri: String?,
    val addedAt: Long,
    val lastScanAt: Long?,
)
