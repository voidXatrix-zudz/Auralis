package com.auralis.app.data.youtube

import com.auralis.app.logging.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Resolves a YouTube video id to a directly playable audio URL through NewPipeExtractor.
 *
 * URLs are signed and expire, so they are cached in memory for [CACHE_TTL_MS]. Several callers may
 * ask for the same video at once (tap pre-fetch + Media3's data source); extraction runs once and
 * every caller awaits the same [Deferred]. Extraction lives in its own scope, so cancelling a
 * prefetch never cancels the resolution the player is waiting on.
 *
 * The lightest audio-only progressive stream is selected: it starts fastest and uses the least
 * bandwidth, which matters on mobile data.
 */
class StreamResolver(private val http: OkHttpClient) {
    private val downloader = NewPipeDownloader(http)
    private val cache = ConcurrentHashMap<String, CachedStream>()
    private val inFlight = HashMap<String, Deferred<String>>()
    private val inFlightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(videoId: String): String = withContext(Dispatchers.IO) {
        cache[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
            return@withContext it.url
        }
        inFlightFor(videoId).await()
    }

    /** Best downloadable audio: Opus when available, otherwise AAC/M4A. */
    suspend fun resolveBest(videoId: String): ResolvedAudio = withContext(Dispatchers.IO) {
        extractBest(videoId)
    }

    /** Blocking bridge for [androidx.media3.datasource.ResolvingDataSource] on its loader thread. */
    fun resolveBlocking(videoId: String): String = runBlocking { resolve(videoId) }

    private fun inFlightFor(videoId: String): Deferred<String> = synchronized(inFlightLock) {
        inFlight[videoId]?.let { return it }
        val job = scope.async { extractWithRetry(videoId) }
        inFlight[videoId] = job
        job.invokeOnCompletion {
            synchronized(inFlightLock) {
                if (inFlight[videoId] === job) inFlight.remove(videoId)
            }
        }
        job
    }

    private suspend fun extractWithRetry(videoId: String): String = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val url = extract(videoId)
                cache[videoId] = CachedStream(url, System.currentTimeMillis() + CACHE_TTL_MS)
                // Keep the audio host warm: PlaybackService uses the same OkHttp client, so this
                // 2-byte range request leaves a pooled connection ready for the first real read.
                scope.launch { warmConnection(url) }
                AppLog.i(
                    TAG,
                    "Resolved lightest audio stream in ${System.currentTimeMillis() - startedAt} ms " +
                        "on attempt ${attempt + 1}",
                )
                return@withContext url
            } catch (error: Throwable) {
                lastError = error
                AppLog.w(TAG, "Stream resolution attempt ${attempt + 1} failed", error)
                if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        AppLog.e(TAG, "Could not resolve a playable stream", lastError)
        throw lastError ?: IllegalStateException("No playable audio stream for this song")
    }

    private fun extract(videoId: String): String {
        ensureInitialized()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        val progressive = info.audioStreams.filter {
            // Progressive HTTP URLs only; DASH/manifest entries cannot be handed to Media3 as-is.
            it.content.startsWith("http", ignoreCase = true)
        }
        val best = progressive.minWithOrNull(
            compareBy<AudioStream> { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE },
        )
        return best?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("This song has no audio-only stream available")
    }

    private fun extractBest(videoId: String): ResolvedAudio {
        ensureInitialized()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        val progressive = info.audioStreams.filter { it.content.startsWith("http", ignoreCase = true) }
        if (progressive.isEmpty()) throw IllegalStateException("This song has no audio-only stream available")
        val opus = progressive.firstOrNull { it.format?.name?.contains("OPUS", ignoreCase = true) == true }
        val chosen = opus ?: progressive.first()
        val url = chosen.content.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("This song has no audio-only stream available")
        val isOpus = opus != null
        return ResolvedAudio(
            url = url,
            format = if (isOpus) "Opus" else "AAC",
            extension = if (isOpus) "opus" else "m4a",
        )
    }

    /**
     * Opens (or reuses) the TLS connection to the signed audio host without downloading the song.
     * A 2-byte ranged GET returns to OkHttp's keep-alive pool, and OkHttpDataSource in
     * [com.auralis.app.playback.PlaybackService] picks that same pooled connection up.
     */
    private fun warmConnection(url: String) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body ?: return@use
                // Only drain a true partial response; never download a whole file if Range is ignored.
                if (response.code == 206) {
                    val buffer = ByteArray(2)
                    var read = 0
                    while (read < buffer.size) {
                        val count = body.byteStream().read(buffer, read, buffer.size - read)
                        if (count <= 0) break
                        read += count
                    }
                }
            }
        }.onFailure { AppLog.d(TAG, "Audio host warm-up skipped: ${it.message}") }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    private data class CachedStream(val url: String, val expiresAt: Long)

    private companion object {
        const val TAG = "YouTube"
        const val WATCH_URL = "https://www.youtube.com/watch?v="
        const val USER_AGENT = YOUTUBE_USER_AGENT
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 600L
        const val CACHE_TTL_MS = 55 * 60 * 1000L
        val lock = Any()

        @Volatile
        var initialized = false
    }
}

/** A downloadable audio stream with its best-effort container format. */
data class ResolvedAudio(val url: String, val format: String, val extension: String)
