package com.auralis.app.data.youtube

import com.auralis.app.data.settings.ResolverStat
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.domain.model.StreamSourceMode
import com.auralis.app.logging.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Live status of the stream resolution stack, surfaced in the Logs screen diagnostics so a bug
 * report can tell at a glance which resolver is in use and whether NewPipe is usable on the device.
 */
object StreamResolverStatus {
    @Volatile var lastResolver: String = "—"
    @Volatile var lastResolveMs: Long = 0L
    @Volatile var newPipeAvailable: Boolean = true
    /** YouTube.js engine state ("ready", "unavailable: ...", "not started"). */
    @Volatile var youTubeJs: String = "not started"
}

/** Which of the three stream resolvers produced (or is being asked for) a URL (Part M). */
enum class ResolverKind(val label: String) {
    NEWPIPE("NewPipeExtractor"),
    INNERTUBE("InnerTube"),
    YOUTUBEJS("YouTube.js");

    /** The resolver the playback fallback prefers next; YouTube.js falls back to NewPipe. */
    val other: ResolverKind
        get() = when (this) {
            NEWPIPE -> INNERTUBE
            INNERTUBE -> NEWPIPE
            YOUTUBEJS -> NEWPIPE
        }
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
 * Resolves a YouTube video id to a directly playable audio URL by racing every enabled provider.
 *
 * In [StreamSourceMode.AUTO] all three providers (NewPipeExtractor, InnerTube, and the embedded
 * YouTube.js behind QuickJS) start within a few milliseconds of each other; the first one to produce
 * a non-empty URL wins and the rest are cancelled immediately, so the user waits for the fastest
 * provider, not the slowest. The provider that won most recently on the current network type (Wi-Fi
 * vs mobile) is started first, but the others still launch so the race stays real. A per-provider
 * result slower than [SOFT_WINDOW_MS] is marked "(slow)" in the log but can still win; the whole race
 * is bounded by [OVERALL_TIMEOUT_MS]. [StreamSourceMode.NEWPIPE], [StreamSourceMode.INNERTUBE] and
 * [StreamSourceMode.YOUTUBEJS] pin a single provider. Every failure is caught (only
 * [CancellationException] is rethrown) so a malformed response or a runtime linkage error can never
 * escape on ExoPlayer's loader/playback threads and kill the app.
 *
 * URLs are signed and expire, so they are cached in memory for [CACHE_TTL_MS]. The cache key includes
 * the [StreamQuality] so a network change re-resolves at the new quality next song.
 */
class StreamResolver(
    private val http: OkHttpClient,
    private val innerTubePlayer: InnerTubePlayer = InnerTubePlayer(http),
    private val settingsRepo: SettingsRepository? = null,
    /** The YouTube.js provider; null when the engine could not be built. */
    private val youtubeJs: StreamProvider? = null,
    /** Whether the active network is metered, for the per-network winner memory. */
    private val metered: () -> Boolean = { false },
) {
    private val downloader = NewPipeDownloader(http)
    private val cache = ConcurrentHashMap<String, CachedStream>()
    private val inFlight = HashMap<String, Deferred<String>>()
    private val inFlightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User's "Stream source" choice (Part H), kept in sync from DataStore. */
    @Volatile private var sourceMode: StreamSourceMode = StreamSourceMode.AUTO

    /** Whether YouTube.js may take part in the AUTO race. */
    @Volatile private var youtubeJsEnabled: Boolean = true

    /** Rolling stats; NewPipe is preferred until another provider's record is clearly better. */
    private val statLock = Any()
    private var newPipeStat = ResolverStat()
    private var innerTubeStat = ResolverStat()
    private var youTubeJsStat = ResolverStat()
    @Volatile private var preferNewPipe = true

    /** Last winning provider per network type ("metered"/"unmetered"); drives race start order. */
    private val networkWinners = ConcurrentHashMap<String, ResolverKind>()

    init {
        val repo = settingsRepo
        if (repo != null) {
            scope.launch {
                repo.settings.collect {
                    sourceMode = it.streamSource
                    youtubeJsEnabled = it.youtubeJsEnabled
                }
            }
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
                // Instant repeat tap: no provider needs to run, so log the reason honestly.
                AppLog.i(TAG, "Race: cache hit in 0 ms ✔ (no resolver needed)")
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

    /** The provider order for this request: pinned, forced, or the AUTO race preference. */
    private suspend fun candidates(force: ResolverKind?): List<ResolverKind> {
        if (force != null) return listOf(force)
        when (sourceMode) {
            StreamSourceMode.NEWPIPE -> return listOf(ResolverKind.NEWPIPE)
            StreamSourceMode.INNERTUBE -> return listOf(ResolverKind.INNERTUBE)
            StreamSourceMode.YOUTUBEJS -> return listOf(ResolverKind.YOUTUBEJS)
            StreamSourceMode.AUTO -> Unit
        }
        val pool = ArrayList<ResolverKind>(3)
        if (youtubeJsEnabled && youtubeJs?.isAvailable() == true) pool.add(ResolverKind.YOUTUBEJS)
        if (StreamResolverStatus.newPipeAvailable) pool.add(ResolverKind.NEWPIPE)
        pool.add(ResolverKind.INNERTUBE)
        val preferred = networkWinners[netKey()]
            ?: if (preferNewPipe) ResolverKind.NEWPIPE else ResolverKind.INNERTUBE
        return pool.sortedBy { if (it == preferred) 0 else 1 }
    }

    private fun netKey(): String = if (metered()) "metered" else "unmetered"

    private suspend fun extractWithRetry(
        videoId: String,
        quality: StreamQuality,
        force: ResolverKind?,
    ): String = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val key = cacheKey(videoId, quality)
        val resolution = race(videoId, quality, candidates(force), force)
        cache[key] = CachedStream(resolution.url, System.currentTimeMillis() + cacheTtlFor(resolution.url))
        // Keep the audio host warm: PlaybackService uses the same OkHttp client, so this 2-byte range
        // request leaves a pooled connection ready for the first real read.
        scope.launch { warmConnection(resolution.url) }
        val elapsed = System.currentTimeMillis() - startedAt
        StreamResolverStatus.lastResolver = resolution.kind.label
        StreamResolverStatus.lastResolveMs = elapsed
        StreamResolverStatus.youTubeJs = when {
            youtubeJs == null -> "not configured"
            youtubeJs !is YouTubeJsProvider -> "configured"
            youtubeJs.isWarm() -> "ready"
            else -> "warming up"
        }
        resolution.url
    }

    private data class Resolution(val kind: ResolverKind, val url: String)

    /** One provider's race outcome as rendered in the log line. */
    private class Trace {
        @Volatile var ms: Long = -1L
        @Volatile var note: String? = null

        fun done(elapsed: Long) { ms = elapsed }

        fun failed(reason: String) { note = reason }

        fun notStarted(reason: String) { if (ms < 0 && note == null) note = "not started ($reason)" }

        fun render(winner: Boolean): String = when {
            ms >= 0 -> "%.1fs%s%s".format(
                ms / 1000.0,
                if (ms > SOFT_WINDOW_MS && !winner) " (slow)" else "",
                if (winner) " ✔" else "",
            )
            note != null -> note!!
            else -> "not started"
        }
    }

    /**
     * Runs the candidates. A single candidate (pinned/forced) is a plain attempt; several are raced:
     * each starts on its own coroutine (the preferred one first, the rest a few ms later), the first
     * non-empty result wins, and the losers are cancelled. Throws the last error if nothing succeeds
     * before [OVERALL_TIMEOUT_MS].
     */
    private suspend fun race(
        videoId: String,
        quality: StreamQuality,
        candidates: List<ResolverKind>,
        force: ResolverKind?,
    ): Resolution = coroutineScope {
        if (candidates.size == 1) {
            val kind = candidates.first()
            val t0 = System.currentTimeMillis()
            return@coroutineScope try {
                val url = attempt(kind, videoId, quality)
                val trace = Trace().apply { done(System.currentTimeMillis() - t0) }
                AppLog.i(TAG, raceLine(candidates, mapOf(kind to trace), winner = kind))
                Resolution(kind, url)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.w(TAG, "${kind.shortLabel()} only provider failed: ${shortReason(error)}")
                throw error
            }
        }

        val traces = LinkedHashMap<ResolverKind, Trace>()
        candidates.forEach { traces[it] = Trace() }
        val outcome = CompletableDeferred<Resolution>()
        val lastError = AtomicReference<Throwable?>(null)
        val remaining = AtomicInteger(candidates.size)
        val jobs = ArrayList<Job>(candidates.size)

        candidates.forEachIndexed { index, kind ->
            jobs += launch {
                // The preferred provider goes first; the others follow a beat later but still launch.
                if (index > 0) delay(STAGGER_MS * index)
                if (outcome.isCompleted) {
                    traces[kind]!!.notStarted("other resolver won")
                    return@launch
                }
                val t0 = System.currentTimeMillis()
                try {
                    val url = attempt(kind, videoId, quality)
                    traces[kind]!!.done(System.currentTimeMillis() - t0)
                    outcome.complete(Resolution(kind, url))
                } catch (cancel: CancellationException) {
                    traces[kind]!!.notStarted("other resolver won")
                    throw cancel
                } catch (error: Throwable) {
                    traces[kind]!!.failed("failed:${shortReason(error)}")
                    lastError.compareAndSet(null, error)
                    // All providers have now failed: surface the error immediately instead of
                    // letting the user stare at a spinner for the rest of the 6 s budget.
                    if (remaining.decrementAndGet() == 0) {
                        outcome.completeExceptionally(
                            lastError.get() ?: IllegalStateException("Every stream resolver failed"),
                        )
                    }
                }
            }
        }

        try {
            val winner = withTimeout(OVERALL_TIMEOUT_MS) { outcome.await() }
            traces.values.forEach { if (it.ms < 0 && it.note == null) it.notStarted("other resolver won") }
            rememberWinner(winner.kind)
            AppLog.i(TAG, raceLine(candidates, traces, winner.kind))
            winner
        } catch (timeout: TimeoutCancellationException) {
            AppLog.w(TAG, "Stream resolution timed out after $OVERALL_TIMEOUT_MS ms")
            traces.values.forEach { if (it.ms < 0 && it.note == null) it.notStarted("timed out") }
            AppLog.w(TAG, raceLine(candidates, traces, winner = null))
            throw (lastError.get() ?: IllegalStateException("Stream resolution timed out"))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Every provider failed before the overall deadline; log the race and let the caller
            // show the normal source error.
            traces.values.forEach { if (it.ms < 0 && it.note == null) it.notStarted("failed") }
            AppLog.w(TAG, raceLine(candidates, traces, winner = null))
            throw error
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /** `Race: NewPipe 1.1s ✔ | InnerTube 1.6s | YouTube.js 2.3s` (or per-provider notes). */
    private fun raceLine(
        candidates: List<ResolverKind>,
        traces: Map<ResolverKind, Trace>,
        winner: ResolverKind?,
    ): String {
        val parts = RACE_ORDER.map { kind ->
            val trace = traces[kind]
            if (trace != null) {
                "${kind.shortLabel()} ${trace.render(winner == kind)}"
            } else {
                "${kind.shortLabel()} ${absentNote(kind, candidates)}"
            }
        }
        val suffix = if (winner == null) ", winner none (timeout)" else ""
        return "Race: " + parts.joinToString(" | ") + suffix
    }

    /** Why a provider that is not in [candidates] did not run. */
    private fun absentNote(kind: ResolverKind, candidates: List<ResolverKind>): String = when {
        kind !in candidates && sourceMode == StreamSourceMode.AUTO && kind == ResolverKind.YOUTUBEJS && !youtubeJsEnabled ->
            "not started (YouTube.js off)"
        kind == ResolverKind.YOUTUBEJS && youtubeJs == null -> "not started (engine unavailable)"
        kind == ResolverKind.NEWPIPE && !StreamResolverStatus.newPipeAvailable -> "not started (unavailable)"
        sourceMode == StreamSourceMode.NEWPIPE -> "not started (source pinned to NewPipe)"
        sourceMode == StreamSourceMode.INNERTUBE -> "not started (source pinned to InnerTube)"
        sourceMode == StreamSourceMode.YOUTUBEJS -> "not started (source pinned to YouTube.js)"
        else -> "not started"
    }

    private fun shortReason(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        return message.replace('\n', ' ').take(48)
    }

    private fun ResolverKind.shortLabel(): String = when (this) {
        ResolverKind.NEWPIPE -> "NewPipe"
        ResolverKind.INNERTUBE -> "InnerTube"
        ResolverKind.YOUTUBEJS -> "YouTube.js"
    }

    /** One provider attempt, recorded against its rolling stats. */
    private suspend fun attempt(kind: ResolverKind, videoId: String, quality: StreamQuality): String =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val url = when (kind) {
                    ResolverKind.NEWPIPE -> extract(videoId, quality)
                    ResolverKind.INNERTUBE -> innerTubeAudioUrl(videoId, quality)
                        ?: throw IllegalStateException("InnerTube returned no audio stream")
                    ResolverKind.YOUTUBEJS -> youtubeJs?.resolveAudio(videoId, quality)
                        ?: throw IllegalStateException("YouTube.js returned no audio stream")
                }
                record(kind, System.currentTimeMillis() - t0, succeeded = true)
                url
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
            when (kind) {
                ResolverKind.NEWPIPE -> newPipeStat = newPipeStat.updated(latencyMs, succeeded)
                ResolverKind.INNERTUBE -> innerTubeStat = innerTubeStat.updated(latencyMs, succeeded)
                ResolverKind.YOUTUBEJS -> youTubeJsStat = youTubeJsStat.updated(latencyMs, succeeded)
            }
            preferNewPipe = if (newPipeStat.total == 0 && innerTubeStat.total == 0) {
                true
            } else {
                newPipeStat.beats(innerTubeStat)
            }
        }
        // Only the two Java resolvers have a persisted record; YouTube.js is session-local.
        if (kind != ResolverKind.YOUTUBEJS) {
            settingsRepo?.let { repo ->
                scope.launch {
                    runCatching { repo.recordResolverResult(kind == ResolverKind.NEWPIPE, latencyMs, succeeded) }
                }
            }
        }
    }

    /** Remembers which provider won on the current network type; it starts first next time. */
    private fun rememberWinner(kind: ResolverKind) {
        networkWinners[netKey()] = kind
    }

    /**
     * Signed googlevideo URLs carry an `expire` (unix seconds) deadline. Capping the cache at that
     * deadline minus a minute stops us from handing ExoPlayer a URL that is about to 403.
     */
    private fun cacheTtlFor(url: String): Long {
        val expire = URL_EXPIRE.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return CACHE_TTL_MS
        val remaining = expire * 1000L - System.currentTimeMillis() - SAFETY_MARGIN_MS
        return remaining.coerceIn(30_000L, CACHE_TTL_MS)
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
                val body = response.body
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

        /** The providers in fixed log order. */
        val RACE_ORDER = listOf(ResolverKind.NEWPIPE, ResolverKind.INNERTUBE, ResolverKind.YOUTUBEJS)

        /** Gap between the preferred provider's start and the others', so they still race. */
        const val STAGGER_MS = 25L

        /** A provider slower than this is marked "(slow)" but can still win. */
        const val SOFT_WINDOW_MS = 1_500L

        /** Give up on the whole race after this long and let the caller skip. */
        const val OVERALL_TIMEOUT_MS = 6_000L
        const val CACHE_TTL_MS = 55 * 60 * 1000L
        const val SAFETY_MARGIN_MS = 60_000L
        val URL_EXPIRE = Regex("[?&]expire=(\\d+)")
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
