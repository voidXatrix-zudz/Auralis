package com.auralis.app.data.download

import com.auralis.app.logging.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** A piece request was rejected because the signed stream URL expired (HTTP 403/410). */
class UrlExpiredException(val code: Int) : RuntimeException("Stream URL expired (HTTP $code)")

/** How a byte range is requested: a `Range` header, or a `&range=start-end` query parameter. */
enum class RangeMode { HEADER, PARAM }

/** What a completed download produced (Part P). */
data class DownloadOutcome(val bytes: Long, val elapsedMs: Long, val chunked: Boolean)

/** A range request the host honoured, plus the total size learned from it. */
private data class RangeProbe(val mode: RangeMode, val total: Long)

/**
 * Fast ranged downloader (Part P).
 *
 * A single long YouTube request is throttled to a crawl, so this splits the file into ~1 MB pieces
 * and fetches up to [parallelism] at once over the shared, connection-pooled OkHttp client
 * (a fresh request per piece, so HTTP/2 multiplexes them). Pieces are written at their exact offset
 * with [FileChannel] positional writes, so no lock is needed between writers, and progress is
 * reported from the total bytes done.
 *
 * Size comes from a `range`/`Content-Range` probe (or the `clen` URL parameter the resolver leaves
 * in place). When the host ignores byte ranges the download degrades gracefully to one plain stream.
 * A failed piece is retried up to [maxAttempts] times; a 403/410 re-resolves the stream through the
 * caller-supplied [download] `reResolve` block and continues with the pieces still missing. The
 * partial file plus its tiny `.state` sidecar allow a cancelled/failed download to resume.
 */
class ChunkedDownloader(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val maxReResolves: Int = MAX_RE_RESOLVES,
) {
    /**
     * Downloads [urls] (best/least-throttled first) into [part]. [onProgress] receives (done, total)
     * throttled to ~5 Hz; [reResolve] returns a fresh candidate list when the signed URLs expire.
     */
    suspend fun download(
        urls: List<String>,
        part: File,
        expectedSize: Long = -1L,
        onProgress: suspend (Long, Long) -> Unit,
        reResolve: suspend () -> List<String>,
    ): DownloadOutcome {
        val started = System.currentTimeMillis()
        val urlRef = AtomicReference(urls.filter { it.isNotBlank() }.distinct())
        if (urlRef.get().isEmpty()) throw IllegalStateException("No stream URL to download")

        val probe = probeRange(urlRef.get().first(), expectedSize)
        if (probe == null || probe.total <= 0L) {
            val bytes = streamSingle(urlRef, part, expectedSize, onProgress, reResolve)
            return DownloadOutcome(bytes, System.currentTimeMillis() - started, chunked = false)
        }

        val total = probe.total
        val chunkCount = ((total + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val resumeFlags = loadState(part, total, chunkCount)
        if (resumeFlags == null) runCatching { part.delete() }
        val doneFlags = resumeFlags ?: BooleanArray(chunkCount)

        val pending = ConcurrentLinkedQueue<Int>()
        for (index in 0 until chunkCount) if (!doneFlags[index]) pending += index
        val doneBytes = AtomicLong(completedBytes(doneFlags, total, chunkSize))
        val doneSet = HashSet<Int>()
        for (index in 0 until chunkCount) if (doneFlags[index]) doneSet += index
        val doneLock = Any()
        val lastNotify = AtomicLong(0L)

        val resolveLock = Mutex()
        var reResolves = 0
        suspend fun refreshUrls() {
            resolveLock.withLock {
                if (reResolves >= maxReResolves) return@withLock
                reResolves++
                val fresh = runCatching { reResolve() }.getOrNull().orEmpty().filter { it.isNotBlank() }
                if (fresh.isNotEmpty()) {
                    urlRef.set(fresh.distinct())
                    AppLog.i(TAG, "Re-resolved stream URL ($reResolves/$maxReResolves)")
                }
            }
        }

        suspend fun reportProgress(force: Boolean) {
            val now = System.currentTimeMillis()
            val previous = lastNotify.get()
            if (!force && now - previous < PROGRESS_INTERVAL_MS) return
            if (!lastNotify.compareAndSet(previous, now) && !force) return
            runCatching { onProgress(doneBytes.get().coerceIn(0L, total), total) }
        }

        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() < total) raf.setLength(total)
            val channel = raf.channel

            suspend fun fetchChunk(index: Int) {
                val start = index.toLong() * chunkSize
                val end = minOf(start + chunkSize, total) - 1
                var attempt = 0
                while (true) {
                    attempt++
                    coroutineContext.ensureActive()
                    var attemptBytes = 0L
                    val onBytes: suspend (Long) -> Unit = { n ->
                        attemptBytes += n
                        doneBytes.addAndGet(n)
                        reportProgress(force = false)
                    }
                    try {
                        fetchInto(channel, urlRef.get(), probe.mode, start, end, onBytes)
                        synchronized(doneLock) { doneSet += index }
                        saveState(part, total, synchronized(doneLock) { doneSet.toSet() })
                        return
                    } catch (cancel: CancellationException) {
                        doneBytes.addAndGet(-attemptBytes)
                        throw cancel
                    } catch (expired: UrlExpiredException) {
                        doneBytes.addAndGet(-attemptBytes)
                        refreshUrls()
                        if (attempt > maxAttempts) {
                            throw IllegalStateException("Stream URL kept expiring (HTTP ${expired.code})", expired)
                        }
                        delay(RETRY_DELAY_MS)
                    } catch (error: Throwable) {
                        doneBytes.addAndGet(-attemptBytes)
                        if (attempt >= maxAttempts) throw error
                        AppLog.d(TAG, "Piece $index attempt $attempt failed: ${error.message}")
                        delay(RETRY_DELAY_MS)
                    }
                }
            }

            coroutineScope {
                List(parallelism) { launch { while (true) { val index = pending.poll() ?: break; fetchChunk(index) } } }.joinAll()
            }
        }

        reportProgress(force = true)
        return DownloadOutcome(total, System.currentTimeMillis() - started, chunked = true)
    }

    /** Tries the `Range` header, then the `&range=` parameter, and returns whichever is honoured. */
    private fun probeRange(url: String, expectedSize: Long): RangeProbe? =
        probeWith(url, RangeMode.HEADER, expectedSize) ?: probeWith(url, RangeMode.PARAM, expectedSize)

    private fun probeWith(url: String, mode: RangeMode, expectedSize: Long): RangeProbe? = runCatching {
        http.newCall(rangeRequest(url, mode, 0L, 0L)).execute().use { response ->
            if (response.code != 206) return@use null
            val total = contentRangeTotal(response.header("Content-Range"))
                ?: clenOf(url)
                ?: expectedSize.takeIf { it > 0 }
            if (total != null && total > 0L) RangeProbe(mode, total) else null
        }
    }.getOrNull()

    /** Fetches [start]..[end] (inclusive) from the first working candidate and writes it at [start]. */
    private suspend fun fetchInto(
        channel: FileChannel,
        urls: List<String>,
        mode: RangeMode,
        start: Long,
        end: Long,
        onBytes: suspend (Long) -> Unit,
    ) {
        var last: Throwable? = null
        for (url in urls) {
            try {
                val bytes = readRange(url, mode, start, end, onBytes)
                writeAt(channel, start, bytes)
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                last = error
                // An expired signature affects every candidate alike: hand it up to re-resolve.
                if (error is UrlExpiredException) throw error
                AppLog.d(TAG, "Candidate failed: ${error.message}")
            }
        }
        throw (last ?: IllegalStateException("No stream URL available"))
    }

    private suspend fun readRange(
        url: String,
        mode: RangeMode,
        start: Long,
        end: Long,
        onBytes: suspend (Long) -> Unit,
    ): ByteArray {
        http.newCall(rangeRequest(url, mode, start, end)).execute().use { response ->
            if (response.code == 403 || response.code == 410) throw UrlExpiredException(response.code)
            if (response.code != 206) throw IllegalStateException("Range request rejected (HTTP ${response.code})")
            val body = response.body ?: throw IllegalStateException("Empty audio response")
            val expected = (end - start + 1).toInt()
            val out = ByteArray(expected)
            val buffer = ByteArray(READ_BUFFER)
            var offset = 0
            body.byteStream().use { input ->
                while (offset < expected) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size, expected - offset))
                    if (read <= 0) break
                    System.arraycopy(buffer, 0, out, offset, read)
                    offset += read
                    onBytes(read.toLong())
                }
            }
            if (offset < expected) throw IllegalStateException("Short read: $offset/$expected bytes")
            return out
        }
    }

    /** Fallback for hosts that ignore byte ranges: one streaming request (no resume), retried. */
    private suspend fun streamSingle(
        urlRef: AtomicReference<List<String>>,
        part: File,
        expectedSize: Long,
        onProgress: suspend (Long, Long) -> Unit,
        reResolve: suspend () -> List<String>,
    ): Long {
        var attempt = 0
        while (true) {
            attempt++
            coroutineContext.ensureActive()
            try {
                val url = urlRef.get().firstOrNull() ?: throw IllegalStateException("No stream URL")
                http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                    if (response.code == 403 || response.code == 410) throw UrlExpiredException(response.code)
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Empty audio response")
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                    var copied = 0L
                    var lastNotify = 0L
                    part.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(READ_BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                    lastNotify = now
                                    runCatching { onProgress(copied, total) }
                                }
                            }
                        }
                    }
                    if (total > 0) runCatching { onProgress(copied, total) }
                    return copied
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (expired: UrlExpiredException) {
                if (attempt > maxAttempts) throw expired
                val fresh = runCatching { reResolve() }.getOrNull().orEmpty().filter { it.isNotBlank() }
                if (fresh.isNotEmpty()) urlRef.set(fresh.distinct())
                delay(RETRY_DELAY_MS)
            } catch (error: Throwable) {
                if (attempt >= maxAttempts) throw error
                AppLog.d(TAG, "Stream attempt $attempt failed: ${error.message}")
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun rangeRequest(url: String, mode: RangeMode, start: Long, end: Long): Request {
        val builder = Request.Builder().header("User-Agent", userAgent)
        return if (mode == RangeMode.PARAM) {
            builder.url(withRangeParam(url, start, end)).build()
        } else {
            builder.url(url).header("Range", "bytes=$start-$end").build()
        }
    }

    private fun writeAt(channel: FileChannel, position: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        var written = 0L
        while (buffer.hasRemaining()) {
            val count = channel.write(buffer, position + written)
            if (count <= 0) throw IllegalStateException("Failed to write piece to output file")
            written += count
        }
    }

    private fun stateFile(part: File): File = File(part.parentFile ?: File("."), part.name + ".state")

    /** Reads resume state; returns null when the partial file cannot be trusted for a resume. */
    private fun loadState(part: File, total: Long, chunkCount: Int): BooleanArray? {
        val state = stateFile(part)
        if (!part.exists() || part.length() <= 0L || !state.exists()) return null
        val lines = runCatching { state.readLines() }.getOrNull() ?: return null
        val storedTotal = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        if (storedTotal != total) return null
        val flags = BooleanArray(chunkCount)
        lines.getOrNull(1)?.split(',')?.forEach { token ->
            token.trim().toIntOrNull()?.let { if (it in 0 until chunkCount) flags[it] = true }
        }
        return flags
    }

    private fun saveState(part: File, total: Long, done: Set<Int>) {
        runCatching { stateFile(part).writeText("$total\n${done.sorted().joinToString(",")}") }
    }

    private fun completedBytes(flags: BooleanArray, total: Long, chunk: Long): Long {
        var bytes = 0L
        for (index in flags.indices) if (flags[index]) {
            val start = index.toLong() * chunk
            bytes += minOf(chunk, total - start).coerceAtLeast(0L)
        }
        return bytes
    }

    private fun contentRangeTotal(header: String?): Long? =
        header?.let { CONTENT_RANGE.find(it)?.groupValues?.get(3)?.toLongOrNull() }

    private fun clenOf(url: String): Long? = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun withRangeParam(url: String, start: Long, end: Long): String {
        val cleaned = url.replace(EXISTING_RANGE, "")
        return if (cleaned.contains('?')) "$cleaned&range=$start-$end" else "$cleaned?range=$start-$end"
    }

    private companion object {
        const val TAG = "Downloads"
        const val DEFAULT_CHUNK_SIZE = 1L shl 20
        const val DEFAULT_PARALLELISM = 4
        const val MAX_ATTEMPTS = 3
        const val MAX_RE_RESOLVES = 3
        const val READ_BUFFER = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 200L
        const val RETRY_DELAY_MS = 250L
        val CLEN = Regex("[?&]clen=(\\d+)")
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)")
        val EXISTING_RANGE = Regex("[?&]range=\\d+-\\d+")
    }
}
