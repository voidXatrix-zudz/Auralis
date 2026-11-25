package com.auralis.app.data.youtube

import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.logging.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * The YouTube.js stream provider: a thin, defensive wrapper over [YouTubeJsEngine] that adapts the
 * engine to the [StreamProvider] contract and to the app's [YtSong] model.
 *
 * Gated by the user's "YouTube.js source" setting through [enabled]. The provider never fabricates a
 * URL: an engine reply that is not a plausible googlevideo/youtube URL is dropped so a malformed
 * result can never win the race and hand Media3 something unplayable.
 */
class YouTubeJsProvider(
    private val engine: YouTubeJsEngine,
    private val enabled: () -> Boolean = { true },
) : StreamProvider {
    override val kind: ResolverKind = ResolverKind.YOUTUBEJS

    /** Starts the QuickJS engine on a background thread; safe to call more than once. */
    fun warmUp() = engine.warmUp()

    override suspend fun isAvailable(): Boolean = enabled() && engine.isViable()

    /** True once the bundle is loaded and the provider globals exist (used for status logging). */
    fun isWarm(): Boolean = enabled() && engine.isReady()

    override suspend fun resolveAudio(videoId: String, quality: StreamQuality): ResolvedStream? {
        if (!enabled()) return null
        val targetKbps = if (quality == StreamQuality.BEST) 0 else quality.approxKbps
        val json = engine.resolveAudio(videoId, targetKbps, RESOLVE_TIMEOUT_MS) ?: return null
        val payload = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val url = payload.optString("url").takeIf { it.isPlayableAudioUrl() } ?: return null
        // The engine reports the mime type and bitrate of the stream it actually picked; either may
        // be missing, in which case the format simply stays unknown rather than being guessed.
        val mime = payload.optString("mimeType").takeIf { it.isNotBlank() }
        val bitrate = payload.optInt("bitrate", 0).takeIf { it > 0 }
        return ResolvedStream(
            url = url,
            format = normalizeAudioFormat(mimeType = mime),
            mimeType = mime,
            codec = shortCodecLabel(null, mime),
            bitrate = bitrate,
            resolver = ResolverKind.YOUTUBEJS,
        )
    }

    /**
     * Song search via YouTube.js Music, used only as a fallback when InnerTube returns nothing.
     * Returns null on any failure so callers keep their existing empty-result behaviour.
     */
    suspend fun searchSongs(query: String): List<YtSong>? {
        if (!enabled()) return null
        val json = engine.search(query, SEARCH_TIMEOUT_MS) ?: return null
        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("videoId")
                    if (id.isBlank()) continue
                    add(
                        YtSong(
                            videoId = id,
                            title = item.optString("title", "Unknown title"),
                            artist = item.optString("artist", "Unknown artist"),
                            album = item.optString("album"),
                            durationMs = item.optLong("durationMs"),
                            thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            albumId = item.optString("albumId").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.onFailure { AppLog.d(TAG, "YouTube.js search skipped: ${it.message}") }.getOrNull()
    }

    /** True for URLs the player can actually open; a host check guards against engine garbage. */
    private fun String.isPlayableAudioUrl(): Boolean {
        if (!startsWith("http://", ignoreCase = true) && !startsWith("https://", ignoreCase = true)) return false
        val host = substringAfter("://").substringBefore('/')
        return host.contains("googlevideo.com", ignoreCase = true) ||
            host.contains("youtube.com", ignoreCase = true) ||
            host.contains("ytimg.com", ignoreCase = true)
    }

    private companion object {
        const val TAG = "YouTube"
        /** Soft per-call bound; the overall race timeout is the hard stop. */
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val SEARCH_TIMEOUT_MS = 6_000L
    }
}
