package com.auralis.app.data.youtube

import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.logging.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 * Live status of the stream resolution stack, surfaced in the Logs screen diagnostics so a bug
 * report can tell at a glance which resolver is in use and whether NewPipe is usable on the device.
 */
object StreamResolverStatus {
    @Volatile var lastResolver: String = "—"
    @Volatile var lastResolveMs: Long = 0L
    @Volatile var newPipeAvailable: Boolean = true
}

/**
 * Thrown internally when NewPipeExtractor cannot run on this device (a [LinkageError] such as the
 * Android 12 `URLDecoder.decode(String, Charset)` [NoSuchMethodError]); the resolver then switches
 * to InnerTube for the rest of the session.
 */
private class NewPipeUnavailableException(cause: Throwable) : RuntimeException(cause)

/**
 * Picks the stream closest to a [StreamQuality] target: the highest bitrate at or below the target,
 * or the highest available when nothing is at or below it ("Best" therefore means highest).
 */
internal fun <T> pickByQuality(items: List<T>, quality: StreamQuality, bitrateOf: (T) -> Int): T? {
    if (items.isEmpty()) return null
    if (quality == StreamQuality.BEST) return items.maxByOrNull { bitrateOf(it).coerceAtLeast(0) }
    val sorted = items.sortedBy { bitrateOf(it).coerceAtLeast(0) }
    val atOrBelow = sorted.filter { bitrateOf(it) in 1..quality.approxKbps }
    return atOrBelow.maxByOrNull { bitrateOf(it) } ?: sorted.first()
}

/**
 * Resolves a YouTube video id to a directly playable audio URL.
 *
 * Primary path: NewPipeExtractor. Fallback path: [InnerTubePlayer] (ANDROID_VR/IOS/ANDROID_MUSIC),
 * used when NewPipe throws a [LinkageError]/[NoSuchMethodError] — older runtimes missing Java 10+
 * methods it relies on. Every failure is caught (only [CancellationException] is rethrown) so a
 * malformed response or a runtime linkage error can never escape on ExoPlayer's loader/playback
 * threads and kill the app.
 *
 * URLs are signed and expire, so they are cached in memory for [CACHE_TTL_MS]. The cache key
 * includes the [StreamQuality] so a network change re-resolves at the new quality next song.
 */
class StreamResolver(
    private val http: OkHttpClient,
    private val innerTubePlayer: InnerTubePlayer = InnerTubePlayer(http),
) {
    private val downloader = NewPipeDownloader(http)
    private val cache = ConcurrentHashMap<String, CachedStream>()
    private val inFlight = HashMap<String, Deferred<String>>()
    private val inFlightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(videoId: String, quality: StreamQuality = StreamQuality.BEST): String =
        withContext(Dispatchers.IO) {
            val key = cacheKey(videoId, quality)
            cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return@withContext it.url
            }
            inFlightFor(videoId, quality).await()
        }

    /** Best downloadable audio: Opus when available, otherwise AAC/M4A. */
    suspend fun resolveBest(videoId: String): ResolvedAudio = withContext(Dispatchers.IO) {
        extractBest(videoId)
    }

    /** Blocking bridge for [androidx.media3.datasource.ResolvingDataSource] on its loader thread. */
    fun resolveBlocking(videoId: String, quality: StreamQuality = StreamQuality.BEST): String =
        runBlocking { resolve(videoId, quality) }

    private fun cacheKey(videoId: String, quality: StreamQuality): String = "$videoId@${quality.name}"

    private fun inFlightFor(videoId: String, quality: StreamQuality): Deferred<String> = synchronized(inFlightLock) {
        val key = cacheKey(videoId, quality)
        inFlight[key]?.let { return it }
        val job = scope.async { extractWithRetry(videoId, quality) }
        inFlight[key] = job
        job.invokeOnCompletion {
            synchronized(inFlightLock) {
                if (inFlight[key] === job) inFlight.remove(key)
            }
        }
        job
    }

    private suspend fun extractWithRetry(videoId: String, quality: StreamQuality): String = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var lastError: Throwable? = null
        val key = cacheKey(videoId, quality)

        repeat(ATTEMPTS) { attempt ->
            try {
                val url = extract(videoId, quality)
                cache[key] = CachedStream(url, System.currentTimeMillis() + CACHE_TTL_MS)
                // Keep the audio host warm: PlaybackService uses the same OkHttp client, so this
                // 2-byte range request leaves a pooled connection ready for the first real read.
                scope.launch { warmConnection(url) }
                val elapsed = System.currentTimeMillis() - startedAt
                StreamResolverStatus.lastResolver = "NewPipeExtractor"
                StreamResolverStatus.lastResolveMs = elapsed
                AppLog.i(TAG, "resolved via NewPipeExtractor in $elapsed ms (attempt ${attempt + 1})")
                return@withContext url
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (unavailable: NewPipeUnavailableException) {
                // NewPipe is unusable for this session; do not burn the retry budget on it.
                lastError = unavailable.cause
                AppLog.w(TAG, "NewPipeExtractor unavailable; falling back to InnerTube", unavailable.cause)
            } catch (error: Throwable) {
                lastError = error
                AppLog.w(TAG, "Stream resolution attempt ${attempt + 1} failed", error)
                if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }

        // Fallback: resolve through InnerTube directly.
        try {
            val url = innerTubeAudioUrl(videoId, quality)
            if (url != null) {
                cache[key] = CachedStream(url, System.currentTimeMillis() + CACHE_TTL_MS)
                scope.launch { warmConnection(url) }
                val elapsed = System.currentTimeMillis() - startedAt
                StreamResolverStatus.lastResolver = "InnerTube"
                StreamResolverStatus.lastResolveMs = elapsed
                AppLog.i(TAG, "resolved via InnerTube in $elapsed ms")
                return@withContext url
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            lastError = error
        }

        AppLog.e(TAG, "Could not resolve a playable stream", lastError)
        throw lastError ?: IllegalStateException("No playable audio stream for this song")
    }

    /** NewPipe extraction. Runs off the caller's thread; throws [NewPipeUnavailableException] on linkage errors. */
    private fun extract(videoId: String, quality: StreamQuality): String {
        ensureInitialized()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        } catch (linkage: LinkageError) {
            StreamResolverStatus.newPipeAvailable = false
            throw NewPipeUnavailableException(linkage)
        }
        val progressive = info.audioStreams.filter {
            // Progressive HTTP URLs only; DASH/manifest entries cannot be handed to Media3 as-is.
            it.content.startsWith("http", ignoreCase = true)
        }
        val chosen = pickByQuality(progressive, quality) { it.averageBitrate }
        return chosen?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("This song has no audio-only stream available")
    }

    /** InnerTube fallback: Opus preferred, closest to the requested quality. */
    private suspend fun innerTubeAudioUrl(videoId: String, quality: StreamQuality): String? {
        val formats = innerTubePlayer.audioFormats(videoId)
        if (formats.isEmpty()) return null
        val opus = formats.filter { it.isOpus }
        val pool = opus.ifEmpty { formats }
        return pickByQuality(pool, quality) { it.bitrate }?.url
    }

    private fun extractBest(videoId: String): ResolvedAudio {
        if (StreamResolverStatus.newPipeAvailable) {
            try {
                ensureInitialized()
                val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
                val progressive = info.audioStreams.filter { it.content.startsWith("http", ignoreCase = true) }
                val opus = progressive.firstOrNull { it.format?.name?.contains("OPUS", ignoreCase = true) == true }
                val chosen = opus ?: progressive.firstOrNull()
                val url = chosen?.content?.takeIf { it.isNotBlank() }
                if (url != null) {
                    return ResolvedAudio(
                        url = url,
                        format = if (opus != null) "Opus" else "AAC",
                        extension = if (opus != null) "opus" else "m4a",
                    )
                }
            } catch (linkage: LinkageError) {
                StreamResolverStatus.newPipeAvailable = false
                AppLog.w(TAG, "NewPipeExtractor unavailable for download; using InnerTube", linkage)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.w(TAG, "NewPipe download resolution failed; trying InnerTube", error)
            }
        }
        // InnerTube: Opus itags are 249/250/251; AAC is 139/140.
        val formats = runBlocking { innerTubePlayer.audioFormats(videoId) }
        val opus = formats.filter { it.isOpus }.maxByOrNull { it.bitrate }
        if (opus != null) return ResolvedAudio(opus.url, "Opus", "opus")
        val aac = formats.maxByOrNull { it.bitrate }
            ?: throw IllegalStateException("This song has no audio-only stream available")
        return ResolvedAudio(aac.url, "AAC", "m4a")
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
