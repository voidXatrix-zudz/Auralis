package com.auralis.app.data.lyrics.providers

import com.auralis.app.data.lyrics.LyricsProvider
import com.auralis.app.data.lyrics.LyricsResult
import com.auralis.app.data.lyrics.ProviderHttp
import com.auralis.app.data.lyrics.QrcParser
import com.auralis.app.data.lyrics.parseJsonObject
import com.auralis.app.data.lyrics.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Better Lyrics "Portato" provider: the `/qq/getLyrics` endpoint returns QQ word-timed QRC, which
 * [QrcParser] turns into enhanced LRC (per-word timing) for the existing renderer.
 *
 * The public endpoint currently answers without a key; an `X-API-Key` is sent only when one is
 * configured, so the provider keeps working if the API later starts requiring one.
 */
class BetterLyricsPortatoProvider(
    private val http: ProviderHttp,
    private val apiKey: String = "",
) : LyricsProvider {
    override val id: String = "BetterLyricsPortato"
    override val displayName: String = "BetterLyrics Portato"
    override val supportsSynced: Boolean = true
    override val supportsWordSync: Boolean = true

    override suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        videoId: String?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val seconds = durationMs?.let { (it / 1000L).toInt().takeIf { s -> s > 0 } }
        val url = buildString {
            append(BASE).append("?s=").append(ProviderHttp.encode(title))
            append("&a=").append(ProviderHttp.encode(artist))
            if (seconds != null) append("&d=").append(seconds)
            if (!album.isNullOrBlank()) append("&al=").append(ProviderHttp.encode(album))
            if (!videoId.isNullOrBlank()) append("&videoId=").append(ProviderHttp.encode(videoId))
        }
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey)
        val body = http.getText(url, headers) ?: return@withContext null
        val qrc = parseJsonObject(body)?.string("lyrics") ?: return@withContext null
        val synced = QrcParser.parse(qrc) ?: return@withContext LyricsResult(id, null, null)
        LyricsResult(id, synced, null)
    }

    private companion object {
        const val BASE = "https://api.betterlyrics.org/qq/getLyrics"
    }
}