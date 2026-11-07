package com.auralis.app.data.metadata

/** Editable metadata fields shared by the MP3/ID3v2 and FLAC/Vorbis writers. */
data class TagData(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val composer: String = "",
    /** Embedded front-cover bytes, when one exists or the user picked a new image. */
    val artwork: ByteArray? = null,
    /** True when the user explicitly asked to remove existing artwork. */
    val removeArtwork: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagData) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && genre == other.genre && year == other.year &&
            trackNumber == other.trackNumber && discNumber == other.discNumber &&
            composer == other.composer && artwork.contentEquals(other.artwork) &&
            removeArtwork == other.removeArtwork
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
}

enum class TagFormat { MP3, FLAC, UNSUPPORTED }
