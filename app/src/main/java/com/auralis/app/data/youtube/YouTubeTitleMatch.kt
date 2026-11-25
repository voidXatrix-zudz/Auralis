package com.auralis.app.data.youtube

import java.util.Locale

/**
 * Best-effort artist/title extraction for a YouTube `player` response that only carries the uploader
 * channel name. Kept pure and dependency-free so the heuristics can be unit-tested.
 *
 * YouTube Music encodes the real artist in the channel name for its auto-generated uploads:
 * "<Artist> - Topic" (Art Track / provided-to-YouTube) and "<Artist>VEVO" (label channel). For
 * ordinary user uploads the channel is a label/aggregator and the artist is usually the left half of
 * an "Artist - Song" title. Everything else falls back to the channel name as the artist.
 *
 * The function never returns a blank title, and never invents an artist from a title that looks like
 * a single song name, so a wrong guess can only ever reduce to "channel as artist".
 */
internal fun artistAndTitle(rawTitle: String, channel: String): Pair<String, String> {
    val title = rawTitle.trim()
    val channelName = channel.trim()

    topicArtist(channelName)?.let { return it to title }
    vevoArtist(channelName)?.let { return it to title }

    // A UGC "Artist - Song" split is only trusted when the channel is a label/aggregator (or missing):
    // an artist's own channel name is more reliable than guessing from the title.
    if (channelName.isBlank() || looksLikeLabel(channelName)) {
        splitArtistTitle(title)?.let { (artist, song) -> return artist to song }
    }
    return channelName to title
}

/** "<Artist> - Topic" → the real artist; null when the channel is not an auto-generated one. */
internal fun topicArtist(channel: String): String? =
    channel.takeIf { it.length > TOPIC_SUFFIX.length && it.endsWith(TOPIC_SUFFIX, ignoreCase = true) }
        ?.dropLast(TOPIC_SUFFIX.length)?.trim()?.takeIf { it.isNotEmpty() }

/** "<Artist>VEVO" → the real artist; null when the channel is not a VEVO channel. */
internal fun vevoArtist(channel: String): String? =
    channel.takeIf { it.length > VEVO_SUFFIX.length && it.endsWith(VEVO_SUFFIX, ignoreCase = true) }
        ?.dropLast(VEVO_SUFFIX.length)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Splits "Artist - Song" (en dash/em dash also accepted) when both halves look like a name/song.
 * Returns null for a plain title, a title whose right half is edit noise ("- Remastered 2011",
 * "- Official Video", "- Live"), or an unreasonably long left half.
 */
internal fun splitArtistTitle(title: String): Pair<String, String>? {
    val match = TITLE_DASH.find(title) ?: return null
    val artist = title.substring(0, match.range.first).trim()
    val song = title.substring(match.range.last + 1).trim()
    if (artist.isEmpty() || song.isEmpty()) return null
    if (artist.split(' ').count { it.isNotBlank() } > MAX_ARTIST_WORDS) return null
    if (SONG_NOISE.containsMatchIn(song)) return null
    return artist to song
}

/** True when a channel name reads like a label, distributor or aggregator rather than a person. */
internal fun looksLikeLabel(channel: String): Boolean = LABEL_HINTS.containsMatchIn(channel)

/**
 * True when cached/fetched player metadata is worth keeping and serving: it must carry a real title,
 * not the video-id placeholder. Artist/album are allowed to be missing so an incomplete entry can be
 * enriched later instead of being re-fetched on every request.
 */
internal fun isUsableMetadata(song: YtSong?, videoId: String): Boolean =
    song != null && song.title.isNotBlank() && song.title.trim() != videoId

private const val TOPIC_SUFFIX = " - Topic"
private const val VEVO_SUFFIX = "VEVO"
private const val MAX_ARTIST_WORDS = 5

/** " - ", " – " or " — " (surrounded by spaces) is the separator YouTube uploads use. */
private val TITLE_DASH = Regex("\\s[-–—]\\s")

/** A right half starting with one of these is an edit marker, not a song title. */
private val SONG_NOISE = Regex(
    "^(remaster(ed)?|live|version|edit|remix|audio|video|lyric(s)?|official|hd|hq|4k|feat|ft|" +
        "from|slowed|sped\\s*up|nightcore)\\b",
    RegexOption.IGNORE_CASE,
)

private val LABEL_HINTS = Regex(
    "records|music|official|label|entertainment|studios|studio|films|productions|audio|vevo|topic|" +
        "network|media|musics|sound",
    RegexOption.IGNORE_CASE,
)

/** Lower-cased, punctuation/space tolerant form used only for the label/artist equality checks. */
internal fun looseEquals(a: String, b: String): Boolean =
    a.lowercase(Locale.US).filter { it.isLetterOrDigit() } ==
        b.lowercase(Locale.US).filter { it.isLetterOrDigit() }
