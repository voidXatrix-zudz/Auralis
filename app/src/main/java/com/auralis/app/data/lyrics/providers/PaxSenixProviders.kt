package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult

/**
 * PaxSenix provider family.
 *
 * These are registered so the user can see them in Settings, but they are intentionally disabled:
 * at implementation time the documented host (`api.paxsenix.biz.id`) did not resolve and no
 * verifiable public request format for the lyrics endpoints could be found. Per the project rule
 * "never invent an endpoint", these return null without making a network call.
 *
 * If a verified endpoint + key format becomes available, replace the `search` bodies below; the
 * engine, cache and settings plumbing already support them.
 */
class PaxSenixProvider : LyricsProvider {
    override val id: String = "PaxSenix"
    override val displayName: String = "PaxSenix"
    override val supportsSynced: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = null
}

class PaxSenixSpotifyProvider : LyricsProvider {
    override val id: String = "PaxSenixSpotify"
    override val displayName: String = "PaxSenix: Spotify"
    override val supportsSynced: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = null
}

class PaxSenixMusixmatchProvider : LyricsProvider {
    override val id: String = "PaxSenixMusixmatch"
    override val displayName: String = "PaxSenix: Musixmatch"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = null
}