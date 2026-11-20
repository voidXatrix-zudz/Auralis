package com.auralis.app.data.youtube

/**
 * One rule for "is this a song?" across every YouTube Music surface.
 *
 * YouTube Music mixes real catalogue tracks (`MUSIC_VIDEO_TYPE_ATV`), label uploads
 * (`OFFICIAL_SOURCE_MUSIC`), official music videos (`OMV`), user uploads (`UGC`) and podcasts in
 * the same shelves. AURALIS is a music player, so anything that is not a song has to be dropped in
 * one place — the library, liked songs, playlists, history, radio and Home rows all call here.
 *
 * Default (strict = false): ATV, OMV and official-source music are kept. Rows that carry no type at
 * all are kept only when they are shorter than [MAX_MUSIC_TRACK_MS]; a typeless long upload is a
 * podcast/mix, not a song.
 *
 * Strict (the "Songs only (strict)" setting): only catalogue audio (ATV) and official-source music.
 * Music videos and typeless rows are hidden too.
 */
fun isMusicTrack(song: YtSong, strict: Boolean = false): Boolean {
    val type = song.musicVideoType?.trim()?.uppercase()
    return when {
        type == MUSIC_VIDEO_TYPE_ATV -> true
        type == MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC -> !strict
        type == MUSIC_VIDEO_TYPE_OMV -> !strict
        type.isNullOrEmpty() -> !strict && song.durationMs < MAX_MUSIC_TRACK_MS
        // UGC, PODCAST_EPISODE, PRIVATELY_OWNED_TRACK and every future/unknown type.
        else -> false
    }
}

/** A Home/discovery card is filtered only when it is a playable song card. */
fun isMusicCard(card: YtCard, strict: Boolean = false): Boolean {
    if (card.videoId == null) return true
    val song = YtSong(
        videoId = card.videoId,
        title = card.title,
        artist = card.subtitle,
        album = "",
        durationMs = card.durationMs,
        thumbnailUrl = card.thumbnailUrl,
        musicVideoType = card.musicVideoType,
    )
    return isMusicTrack(song, strict)
}

/** Keeps only songs. The hidden count is returned so callers can log it without leaking titles. */
fun filterMusicTracks(songs: List<YtSong>, strict: Boolean = false): List<YtSong> =
    songs.filter { isMusicTrack(it, strict) }

/** Same as [filterMusicTracks] but reports how many rows were hidden. */
fun filterMusicTracksCounting(songs: List<YtSong>, strict: Boolean = false): Pair<List<YtSong>, Int> {
    val kept = songs.filter { isMusicTrack(it, strict) }
    return kept to (songs.size - kept.size)
}

const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
const val MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC = "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"
const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
const val MUSIC_VIDEO_TYPE_PODCAST_EPISODE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
const val MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK = "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

/** Anything at or over twelve minutes without an ATV marker is not a song. */
const val MAX_MUSIC_TRACK_MS = 12L * 60L * 1000L

/** `VL` is a browse prefix, not part of the id: strip it, then add exactly one. */
fun normalizePlaylistBrowseId(playlistId: String): String {
    val withoutVl = playlistId.trim().removePrefix("VL")
    // An album card carries the album browse id (`MPREb...`); that page is browsed as-is. The
    // `VL` form is for playlists (PL..., LM, OLAK...) and is rejected for albums.
    return if (withoutVl.startsWith("MPRE")) withoutVl else "VL$withoutVl"
}