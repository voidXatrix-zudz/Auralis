package com.auralis.app.data.youtube

import com.auralis.app.data.settings.ResolverStat
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.domain.model.StreamSourceMode
import com.auralis.app.logging.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/** Which of the two stream resolvers produced (or is being asked for) a URL (Part M). */
enum class ResolverKind(val label: String) {
    NEWPIPE("NewPipeExtractor"),
    INNERTUBE("InnerTube");

    /** The competing resolver used by the playback fallback. */
    val other: ResolverKind get() = if (this == NEWPIPE) INNERTUBE else NEWPIPE
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
 * Resolves a YouTube video id to a directly playable audio URL by racing the two resolvers.
 *
 * In [StreamSourceMode.AUTO] the resolver with the better recent record (lower average latency and a
 * higher success rate, persisted in DataStore; NewPipe by default) starts first. If it fails, or has
 * not answered within [HEDGE_DELAY_MS], the other one starts at once and the first success wins; the
 * loser is cancelled. An overall [OVERALL_TIMEOUT_MS] budget bounds the race. [StreamSourceMode.NEWPIPE]
 * and [StreamSourceMode.INNERTUBE] pin the source. Every failure is caught (only [CancellationException]
 * is rethrown) so a malformed response or a runtime linkage error can never escape on ExoPlayer's
 * loader/playback threads and kill the app.
 *
 * URLs are signed and expire, so they are cached in memory for [CACHE_TTL_MS]. The cache key includes
 * the [StreamQuality] so a network change re-resolves at the new quality next song.
 */
class StreamResolver(
    private val http: OkHttpClient,
    private val innerTubePlayer: InnerTubePlayer = InnerTubePlayer(http),
    private val settingsRepo: SettingsRepository? = null,
) {
    private val downloader = NewPipeDownloader(http)
    private val cache = ConcurrentHashMap<String, CachedStream>()
    private val inFlight = HashMap<String, Deferred<String>>()
    private val inFlightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User's "Stream source" choice (Part H), kept in sync from DataStore. */
    @Volatile private var sourceMode: StreamSourceMode = StreamSourceMode.AUTO

    /** Rolling stats; NewPipe is preferred until the InnerTube record is clearly better. */
    private val statLock = Any()
    private var newPipeStat = ResolverStat()
    private var innerTubeStat = ResolverStat()
    @Volatile private var preferNewPipe = true

    init {
        val repo = settingsRepo
        if (repo != null) {
            scope.launch { repo.settings.collect { sourceMode = it.streamSource } }
            scope.launch {
                val np = runCatching { repo.resolverStat(newpipe = true) }.getOrDefault(ResolverStat())
                val it = runCatching { repo.resolverStat(newpipe = false) }.getOrDefault(ResolverStat())
                synchronized(statLock) {
                    newPipeStat = np
                    innerTubeStat = it
                    preferNewPipe = if (np.total == 0 && it.total == 0) true else np.beats(it)
                }
            }
        }
    }

    suspend fun resolve(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
        force: ResolverKind? = null,
        bypassCache: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val key = cacheKey(videoId, quality)
        if (bypassCache) {
            // Playback fallback: the cached URL just failed, so re-resolve (and replace) it.
            extractWithRetry(videoId, quality, force)
        } else {
            cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return@withContext it.url
            }
            inFlightFor(videoId, quality, force).await()
        }
    }

    /** Best downloadable audio: Opus when available, otherwise AAC/M4A. */
    suspend fun resolveBest(videoId: String): ResolvedAudio = withContext(Dispatchers.IO) {
        extractBest(videoId)
    }

    /** Blocking bridge for [androidx.media3.datasource.ResolvingDataSource] on its loader thread. */
    fun resolveBlocking(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
        force: ResolverKind? = null,
        bypassCache: Boolean = false,
    ): String = runBlocking { resolve(videoId, quality, force, bypassCache) }

    private fun cacheKey(videoId: String, quality: StreamQuality): String = "$videoId@${quality.name}"

    private fun inFlightFor(videoId: String, quality: StreamQuality, force: ResolverKind?): Deferred<String> = synchronized(inFlightLock) {
        val key = inFlightKey(videoId, quality, force)
        inFlight[key]?.let { return it }
        val job = scope.async { extractWithRetry(videoId, quality, force) }
        inFlight[key] = job
        job.invokeOnCompletion {
            synchronized(inFlightLock) {
                if (inFlight[key] === job) inFlight.remove(key)
            }
        }
        job
    }

    private fun inFlightKey(videoId: String, quality: StreamQuality, force: ResolverKind?): String =
        "$videoId@${quality.name}@${force?.name ?: "auto"}"

    /** The resolver order for this request: pinned, forced, or the AUTO race preference. */
    private fun candidates(force: ResolverKind?): List<ResolverKind> = when {
        force != null -> listOf(force)
        sourceMode == StreamSourceMode.NEWPIPE -> listOf(ResolverKind.NEWPIPE)
        sourceMode == StreamSourceMode.INNERTUBE -> listOf(ResolverKind.INNERTUBE)
        !StreamResolverStatus.newPipeAvailable -> listOf(ResolverKind.INNERTUBE, ResolverKind.NEWPIPE)
        preferNewPipe -> listOf(ResolverKind.NEWPIPE, ResolverKind.INNERTUBE)
        else -> listOf(ResolverKind.INNERTUBE, ResolverKind.NEWPIPE)
    }

    private suspend fun extractWithRetry(
        videoId: String,
        quality: StreamQuality,
        force: ResolverKind?,
    ): String = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val key = cacheKey(videoId, quality)
        val resolution = race(videoId, quality, candidates(force))
        cache[key] = CachedStream(resolution.url, System.currentTimeMillis() + CACHE_TTL_MS)
        // Keep the audio host warm: PlaybackService uses the same OkHttp client, so this 2-byte range
        // request leaves a pooled connection ready for the first real read.
        scope.launch { warmConnection(resolution.url) }
        val elapsed = System.currentTimeMillis() - startedAt
        StreamResolverStatus.lastResolver = resolution.kind.label
        StreamResolverStatus.lastResolveMs = elapsed
        AppLog.i(TAG, "resolved via ${resolution.kind.label} in $elapsed ms")
        resolution.url
    }

    private data class Resolution(val kind: ResolverKind, val url: String)

    /**
     * Runs the resolver candidates. A single candidate (pinned/forced) is a plain attempt; two are
     * raced: the second starts when the first fails or after [HEDGE_DELAY_MS], and the first success
     * wins. Throws the last error if neither succeeds before [OVERALL_TIMEOUT_MS].
     */
    private suspend fun race(
        videoId: String,
        quality: StreamQuality,
        candidates: List<ResolverKind>,
    ): Resolution = coroutineScope {
        if (candidates.size == 1) {
            return@coroutineScope attempt(candidates.first(), videoId, quality)
        }
        val first = candidates[0]
        val second = candidates[1]
        val firstFailed = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Resolution>()
        val lastError = AtomicReference<Throwable?>(null)

        val firstJob = launch {
            try {
                outcome.complete(attempt(first, videoId, quality))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError.set(error)
                firstFailed.complete(Unit)
            }
        }
        val secondJob = launch {
            // Hedge: start the other resolver as soon as the first fails, or after the delay.
            if (!firstFailed.isCompleted) withTimeoutOrNull(HEDGE_DELAY_MS) { firstFailed.await() }
            if (outcome.isCompleted) return@launch
            try {
                outcome.complete(attempt(second, videoId, quality))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError.compareAndSet(null, error)
            }
        }
        try {
            withTimeout(OVERALL_TIMEOUT_MS) { outcome.await() }
        } catch (timeout: TimeoutCancellationException) {
            AppLog.w(TAG, "Stream resolution timed out after $OVERALL_TIMEOUT_MS ms")
            throw (lastError.get() ?: IllegalStateException("Stream resolution timed out"))
        } finally {
            firstJob.cancel()
            secondJob.cancel()
        }
    }

    /** One resolver attempt, recorded against its rolling stats. */
    private suspend fun attempt(kind: ResolverKind, videoId: String, quality: StreamQuality): Resolution =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val url = when (kind) {
                    ResolverKind.NEWPIPE -> extract(videoId, quality)
                    ResolverKind.INNERTUBE -> innerTubeAudioUrl(videoId, quality)
                        ?: throw IllegalStateException("InnerTube returned no audio stream")
                }
                record(kind, System.currentTimeMillis() - t0, succeeded = true)
                Resolution(kind, url)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                record(kind, System.currentTimeMillis() - t0, succeeded = false)
                AppLog.w(TAG, "${kind.label} resolve failed", error)
                throw error
            }
        }

    /** Folds an outcome into the in-memory preference and persists it to DataStore. */
    private fun record(kind: ResolverKind, latencyMs: Long, succeeded: Boolean) {
        synchronized(statLock) {
            if (kind == ResolverKind.NEWPIPE) {
                newPipeStat = newPipeStat.updated(latencyMs, succeeded)
            } else {
                innerTubeStat = innerTubeStat.updated(latencyMs, succeeded)
            }
            preferNewPipe = if (newPipeStat.total == 0 && innerTubeStat.total == 0) {
                true
            } else {
                newPipeStat.beats(innerTubeStat)
            }
        }
        settingsRepo?.let { repo ->
            scope.launch {
                runCatching { repo.recordResolverResult(kind == ResolverKind.NEWPIPE, latencyMs, succeeded) }
            }
        }
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
        // Collect every audio URL we can, tagged by container, then prefer the least-throttled one.
        val opusUrls = ArrayList<String>()
        val aacUrls = ArrayList<String>()
        if (StreamResolverStatus.newPipeAvailable) {
            try {
                ensureInitialized()
                val info = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
                info.audioStreams
                    .filter { it.content.startsWith("http", ignoreCase = true) }
                    .forEach { stream ->
                        if (stream.format?.name?.contains("OPUS", ignoreCase = true) == true) {
                            opusUrls += stream.content
                        } else {
                            aacUrls += stream.content
                        }
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
        // InnerTube formats come from non-web clients (ANDROID_VR/IOS/ANDROID_MUSIC), which YouTube
        // throttles far less; keep them for the front of the candidate list.
        val formats = runBlocking { innerTubePlayer.audioFormats(videoId) }
        val innerTubeUrls = formats.mapTo(HashSet()) { it.url }
        formats.forEach { if (it.isOpus) opusUrls += it.url else aacUrls += it.url }

        val useOpus = opusUrls.isNotEmpty()
        val pool = (if (useOpus) opusUrls else aacUrls).distinct()
        if (pool.isEmpty()) throw IllegalStateException("This song has no audio-only stream available")
        // ratebypass=yes disables throttling outright; otherwise a non-web InnerTube URL is preferred.
        val ordered = pool.sortedBy { url ->
            when {
                url.contains("ratebypass=yes", ignoreCase = true) -> 0
                url in innerTubeUrls -> 1
                else -> 2
            }
        }
        return ResolvedAudio(
            url = ordered.first(),
            format = if (useOpus) "Opus" else "AAC",
            extension = if (useOpus) "opus" else "m4a",
            candidates = ordered,
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
        /** Start the competing resolver if the first has not answered in this long. */
        const val HEDGE_DELAY_MS = 2_000L
        /** Give up on the whole race after this long and let the caller skip. */
        const val OVERALL_TIMEOUT_MS = 8_000L
        const val CACHE_TTL_MS = 55 * 60 * 1000L
        val lock = Any()

        @Volatile
        var initialized = false
    }
}

/** A downloadable audio stream with its best-effort container format. */
data class ResolvedAudio(
    val url: String,
    val format: String,
    val extension: String,
    /** Additional playable URLs for the same audio, best (least throttled) first. */
    val candidates: List<String> = emptyList(),
)
