package com.auralis.app.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.data.db.LyricsDao
import com.auralis.app.data.db.LyricsEntity
import com.auralis.app.data.db.ProviderCacheDao
import com.auralis.app.data.db.ProviderCacheEntity
import com.auralis.app.data.db.SourceDao
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File

/**
 * Resolves lyrics for a track using a strict priority:
 * 1. lyrics the user pasted or imported (never overwritten automatically),
 * 2. lyrics embedded in the audio file,
 * 3. a local sidecar `.lrc` file,
 * 4. cached LRCLIB lyrics, then a live LRCLIB lookup (metadata only, any song).
 *
 * Everything is cached in Room keyed by (trackId, source), so adding embedded lyrics cannot delete
 * a manual version. Discovery and network failures are non-fatal and never block playback.
 */
class LyricsRepository(
    private val context: Context,
    private val lyricsDao: LyricsDao,
    private val sourceDao: SourceDao,
    private val providerCacheDao: ProviderCacheDao? = null,
    private val settings: SettingsRepository? = null,
    http: OkHttpClient = OkHttpClient(),
) {
    /** Parsed lyrics kept in memory (current track + prefetched queue neighbours). */
    private val parsedCache = LruCache<String, Lyrics>(24)

    /** Enabled by id; built once. Provider order/enabled state is read per lookup from settings. */
    private val providerHttp = ProviderHttp(http)
    private val providers: Map<String, LyricsProvider> = listOf(
        LrclibProvider(),
        SimpMusicProvider(providerHttp),
        NetEaseProvider(providerHttp),
        KuGouProvider(providerHttp),
        BetterLyricsProvider(providerHttp),
        YouTubeMusicLyricsProvider(providerHttp),
        LyricsOvhProvider(providerHttp),
    ).associateBy { it.id }

    /** Non-blocking lookup used by the screen to show prefetched/cached lyrics instantly. */
    fun cached(trackId: String): Lyrics? = parsedCache.get(trackId)

    /** Warms the next queue item: resolve, parse off the main thread and cache the result. */
    suspend fun prefetch(track: Track) {
        runCatching { load(track) }
    }

    suspend fun load(
        track: Track,
        allowDiscovery: Boolean = true,
        allowNetwork: Boolean = true,
        skipProviders: Set<String> = emptySet(),
        ignoreMemoryCache: Boolean = false,
        onUpdate: (suspend (Lyrics) -> Unit)? = null,
    ): Lyrics? = withContext(Dispatchers.IO) {
        if (!ignoreMemoryCache) parsedCache.get(track.id)?.let { return@withContext it }
        val stored = runCatching { lyricsDao.forTrack(track.id) }.getOrDefault(emptyList())

        // 1. Manual lyrics always win and are never replaced by automatic discovery.
        stored.firstOrNull { it.source == LyricsSource.USER.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }

        // 2. Embedded lyrics are preferred over sidecar files. A cached copy avoids re-reading the
        //    audio file on every visit, while a stale/missing cache falls back to the real tag.
        stored.firstOrNull { it.source == LyricsSource.EMBEDDED.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }
        // Remote (streamed) songs have no local file, so only the DB cache and LRCLIB apply.
        if (allowDiscovery && !track.isRemote) {
            val embedded = EmbeddedLyrics.read(context, track)
            if (embedded != null) {
                cache(track, LyricsSource.EMBEDDED, embedded.text)
                return@withContext parseRaw(track.id, embedded.text, LyricsSource.EMBEDDED)
            }
        }

        // 3. Sidecar LRC.
        stored.firstOrNull { it.source == LyricsSource.LRC.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }
        if (!allowDiscovery) return@withContext null
        val discovered = if (track.isRemote) null else discover(track)
        if (discovered != null) {
            cache(track, LyricsSource.LRC, discovered.first)
            return@withContext parseRaw(track.id, discovered.first, LyricsSource.LRC)
        }

        // 4. Online providers, queried in parallel (Part Q): every enabled provider starts at once,
        //    each with a 6 s budget and an overall 6 s cap. The first acceptable synced result is
        //    shown immediately and silently upgraded within a 2 s window; otherwise the best
        //    unsynced result wins. A positive cache row is reused, a fresh negative row skips a
        //    provider, and a failing provider is only logged (never negative-cached).
        if (!allowNetwork) return@withContext null
        val current = runCatching { settings?.settings?.first() }.getOrNull()
        val order = (current?.lyricsProviderOrder ?: com.auralis.app.domain.model.AppSettings.DEFAULT_LYRICS_PROVIDER_ORDER)
            .filter { it in (current?.lyricsProvidersEnabled ?: com.auralis.app.domain.model.AppSettings().lyricsProvidersEnabled) }
            .filterNot { it in skipProviders }
        val preferSynced = current?.preferSyncedLyrics ?: true
        return@withContext loadOnlineParallel(track, order, preferSynced, onUpdate)
    }

    /**
     * "Try another source": ignores the in-memory winner and skips [currentProvider], returning the
     * next provider that has anything, or null when the user has exhausted the enabled list.
     */
    suspend fun tryAnother(track: Track, currentProvider: String?): Lyrics? = withContext(Dispatchers.IO) {
        val skip = setOfNotNull(currentProvider)
        val result = load(
            track = track,
            allowDiscovery = false,
            allowNetwork = true,
            skipProviders = skip,
            ignoreMemoryCache = true,
        )
        if (result != null) parsedCache.put(track.id, result)
        result
    }

    /**
     * Explicit "Retry": forgets every cached provider answer for this track (positive and negative)
     * and looks the song up again. Used by the empty lyrics state.
     */
    suspend fun retry(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        runCatching { providerCacheDao?.deleteKey(KIND_LYRICS, track.id) }
        parsedCache.remove(track.id)
        load(track, ignoreMemoryCache = true)
    }

    /** The query fields shared by every provider lookup. */
    private data class LyricsQuery(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val videoId: String?,
    )

    /** A parsed result with its ranking score. */
    private data class ScoredLyrics(val lyrics: Lyrics, val score: Double)

    /** A provider outcome on the results channel ([lyrics] is null when it found or returned nothing). */
    private class LyricsHit(val lyrics: Lyrics?)

    /**
     * Queries every enabled [order] provider in parallel and picks the best answer (Part Q).
     *
     * One coroutine per provider starts at once (never for a disabled one); each has a
     * [PROVIDER_TIMEOUT_MS] budget and the whole lookup is capped at [OVERALL_BUDGET_MS]. Results are
     * scored by confidence (title similarity, artist overlap, duration agreement) plus a strong
     * synced-over-plain bonus. The first synced result is emitted through [onUpdate] immediately and
     * silently upgraded if a higher-scored one arrives within [UPGRADE_WINDOW_MS]; when nothing is
     * synced the best unsynced result wins. Cached positives are reused and fresh negatives skip a
     * provider; provider failures are only logged.
     */
    private suspend fun loadOnlineParallel(
        track: Track,
        order: List<String>,
        preferSynced: Boolean,
        onUpdate: (suspend (Lyrics) -> Unit)?,
    ): Lyrics? {
        if (order.isEmpty()) return null
        val query = LyricsQuery(
            title = okayTitle(track),
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            durationMs = track.durationMs.takeIf { it > 0 },
            videoId = if (track.isRemote) (track.remoteId ?: track.id.removePrefix("yt:")) else null,
        )
        return coroutineScope {
            val hits = Channel<LyricsHit>(Channel.UNLIMITED)
            val jobs = order.map { providerId ->
                launch { hits.send(LyricsHit(searchProvider(track, providerId, query))) }
            }
            launch {
                jobs.joinAll()
                hits.close()
            }

            var best: ScoredLyrics? = null
            var bestSynced: ScoredLyrics? = null
            var shown: ScoredLyrics? = null
            var windowStartedAt = 0L
            val deadline = System.currentTimeMillis() + OVERALL_BUDGET_MS
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val hit = withTimeoutOrNull(remaining) { hits.receiveCatching().getOrNull() } ?: break
                val lyrics = hit.lyrics ?: continue
                val scored = ScoredLyrics(lyrics, scoreLyrics(lyrics))
                if (best == null || scored.score > best!!.score) best = scored

                val synced = lyrics.synced != null
                if (synced) {
                    if (bestSynced == null || scored.score > bestSynced!!.score) bestSynced = scored
                }
                val withinUpgrade = windowStartedAt > 0L &&
                    System.currentTimeMillis() - windowStartedAt <= UPGRADE_WINDOW_MS
                val emit = when {
                    shown == null -> synced || !preferSynced
                    synced && withinUpgrade -> scored.score > (shown?.score ?: 0.0)
                    !synced && !preferSynced && withinUpgrade -> scored.score > (shown?.score ?: 0.0)
                    else -> false
                }
                if (emit) {
                    onUpdate?.invoke(lyrics)
                    shown = scored
                    if (windowStartedAt == 0L) windowStartedAt = System.currentTimeMillis()
                }
            }

            val winner = (if (preferSynced) bestSynced ?: best else best)?.lyrics
            if (winner != null && winner !== shown?.lyrics) onUpdate?.invoke(winner)
            winner
        }
    }

    /** Resolves one provider: cached row first, else a bounded search; both cache their outcome. */
    private suspend fun searchProvider(track: Track, providerId: String, query: LyricsQuery): Lyrics? {
        val cachedRow = runCatching { providerCacheDao?.get(KIND_LYRICS, track.id, providerId) }.getOrNull()
        if (cachedRow != null) {
            if (!cachedRow.found) {
                if (System.currentTimeMillis() - cachedRow.updatedAt < NEGATIVE_TTL_MS) return null
            } else {
                return parseWithProvider(track, cachedRow.payload, providerId)
            }
        }
        val provider = providers[providerId] ?: return null
        val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runProvider { provider.search(query.title, query.artist, query.album, query.durationMs, query.videoId) }
        } ?: return null
        val raw = result.raw
        if (raw == null) {
            cacheProvider(track.id, providerId, "", found = false)
            return null
        }
        cacheProvider(track.id, providerId, raw, found = true)
        return parseWithProvider(track, raw, providerId)
    }

    /** Runs a provider call, logging (never surfacing) failures but honouring cancellation. */
    private suspend fun <T> runProvider(block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Log.w(TAG, "Lyrics provider failed", error)
        null
    }

    /** Confidence plus a strong synced-over-plain bonus, minus an out-of-sync penalty. */
    private fun scoreLyrics(lyrics: Lyrics): Double {
        val confidence = lyrics.confidence ?: 0.6
        val syncedBonus = if (lyrics.synced != null) 1.0 else 0.0
        val outOfSyncPenalty = if (lyrics.outOfSync) 0.25 else 0.0
        return confidence + syncedBonus - outOfSyncPenalty
    }

    /** Deletes every cached provider winner and "none found" marker (Settings: clear cache). */
    suspend fun clearProviderCache() = withContext(Dispatchers.IO) {
        runCatching { providerCacheDao?.clearKind(KIND_LYRICS) }
        parsedCache.evictAll()
    }

    /** Clears only the negative ("none found") markers so a settings change re-queries everything. */
    suspend fun clearNegativeCache() = withContext(Dispatchers.IO) {
        runCatching { providerCacheDao?.clearNegative() }
    }

    suspend fun import(track: Track, uri: Uri): Lyrics = withContext(Dispatchers.IO) {
        val raw = readText(uri) ?: throw IllegalArgumentException("The selected file could not be read as text")
        if (raw.isBlank()) throw IllegalArgumentException("The selected file is empty")
        saveText(track, raw, LyricsSource.USER)
    }

    suspend fun saveText(track: Track, raw: String, source: LyricsSource = LyricsSource.USER): Lyrics =
        withContext(Dispatchers.IO) {
            require(raw.isNotBlank()) { "Lyrics are empty" }
            cache(track, source, raw)
            parseRaw(track.id, raw, source)
        }

    suspend fun remove(track: Track, source: LyricsSource): Boolean = withContext(Dispatchers.IO) {
        val rows = lyricsDao.forTrack(track.id)
        if (rows.none { it.source == source.name }) return@withContext false
        lyricsDao.delete(track.id, source.name)
        parsedCache.remove(track.id)
        true
    }

    /** Drops cached automatic lyrics so the next load re-reads the file / folder / LRCLIB. */
    suspend fun invalidateAutomatic(track: Track) = withContext(Dispatchers.IO) {
        runCatching { lyricsDao.delete(track.id, LyricsSource.EMBEDDED.name) }
        runCatching { lyricsDao.delete(track.id, LyricsSource.LRC.name) }
        runCatching { lyricsDao.delete(track.id, LyricsSource.LRCLIB.name) }
        runCatching { providerCacheDao?.deleteKey(KIND_LYRICS, track.id) }
        parsedCache.remove(track.id)
    }

    private suspend fun cache(track: Track, source: LyricsSource, raw: String) {
        runCatching {
            lyricsDao.upsert(
                LyricsEntity(
                    trackId = track.id,
                    source = source.name,
                    raw = raw,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not cache ${source.name} lyrics for ${track.id}", it) }
    }

    private suspend fun discover(track: Track): Pair<String, String>? {
        val candidates = candidateNames(track)
        val preferred = candidates.map { it.lowercase() }.toSet()
        val source = runCatching { sourceDao.getById(track.sourceId) }.getOrNull()

        // SAF tree: walk the recorded relative path from the granted tree.
        source?.treeUri?.let { tree ->
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            if (root != null && root.exists()) {
                var directory: DocumentFile = root
                val parent = track.relativePath.substringBeforeLast('/', "")
                if (parent.isNotBlank()) {
                    for (segment in parent.split('/')) {
                        val child = directory.findFile(segment) ?: return@let
                        if (!child.isDirectory) return@let
                        directory = child
                    }
                }
                // Exact candidates first (cheap).
                for (name in candidates) {
                    val candidate = directory.findFile(name) ?: continue
                    if (candidate.isFile) readText(candidate.uri)?.let { return it to name }
                }
                // Fuzzy match against every .lrc in the folder using track metadata and LRC headers.
                val files = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
                    .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
                var best: Triple<Int, String, DocumentFile>? = null
                for (file in files) {
                    val name = file.name ?: continue
                    val raw = readText(file.uri) ?: continue
                    val score = score(name, raw, track, preferred)
                    if (best == null || score > best.first) best = Triple(score, name, file)
                    if (score >= 100) return raw to name
                    if (best != null && best.first >= 100) break
                }
                if (best != null && best.first >= MIN_MATCH_SCORE) {
                    val winner = best
                    val raw = readText(winner.third.uri)
                    if (raw != null) return raw to winner.second
                }
            }
        }

        // MediaStore: exact sibling lookup, then a fuzzy scan of the same relative folder.
        val directoryPath = track.relativePath.substringBeforeLast('/', "")
        if (directoryPath.isNotBlank()) {
            val relative = directoryPath.trim('/').plus("/")
            runCatching {
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND lower(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.lrc'",
                    arrayOf(relative),
                    null,
                )?.use { cursor ->
                    var best: Triple<Int, Long, String>? = null
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val uri = ContentUris.withAppendedId(collection, id)
                        val raw = readText(uri) ?: continue
                        val score = score(name, raw, track, preferred)
                        if (best == null || score > best.first) best = Triple(score, id, name)
                        if (score >= 100) return raw to name
                    }
                    if (best != null && best.first >= MIN_MATCH_SCORE) {
                        val winner = best
                        val uri = ContentUris.withAppendedId(collection, winner.second)
                        val raw = readText(uri)
                        if (raw != null) return raw to winner.third
                    }
                }
            }.onFailure { Log.d(TAG, "MediaStore lyric lookup unavailable for ${track.displayName}", it) }
        }

        // Last resort for devices exposing legacy storage paths.
        val legacyDirectory = File(
            android.os.Environment.getExternalStorageDirectory(),
            directoryPath.trim('/'),
        )
        if (legacyDirectory.isDirectory) {
            val files = legacyDirectory.listFiles { file -> file.isFile && file.name.endsWith(".lrc", true) }
                .orEmpty()
            val exact = candidates.firstNotNullOfOrNull { name -> files.firstOrNull { it.name.equals(name, true) } }
            if (exact != null && exact.length() <= MAX_LYRICS_BYTES) {
                runCatching { exact.readText() }.getOrNull()?.let { return it to exact.name }
            }
            var best: Pair<Int, File>? = null
            for (file in files) {
                if (file.length() > MAX_LYRICS_BYTES) continue
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                val score = score(file.name, raw, track, preferred)
                if (best == null || score > best.first) best = score to file
                if (score >= 100) return raw to file.name
            }
            if (best != null && best.first >= MIN_MATCH_SCORE) {
                runCatching { best.second.readText() }.getOrNull()?.let { return it to best.second.name }
            }
        }
        return null
    }

    private fun candidateNames(track: Track): List<String> {
        val fileBase = track.displayName.substringBeforeLast('.', track.displayName)
        val title = track.title.trim().ifBlank { fileBase }
        val artist = track.artist.trim()
        val names = linkedSetOf("$fileBase.lrc", "$fileBase.LRC")
        if (title.isNotBlank() && title != fileBase) names += "$title.lrc"
        if (artist.isNotBlank() && title.isNotBlank()) {
            names += "$artist - $title.lrc"
            names += "$title - $artist.lrc"
        }
        // A few tag-style variants used by downloaders.
        names += "${track.artist} - ${track.title}.lrc".replace("  ", " ")
        return names.filter { it.isNotBlank() && !it.startsWith(" - ") }
    }

    /**
     * Scores a sidecar file by filename and embedded `[ti:]`/`[ar:]`/`[al:]` header fields. 100 is an
     * exact title match; partial token matches are accepted above [MIN_MATCH_SCORE].
     */
    private fun score(name: String, raw: String, track: Track, preferred: Set<String>): Int {
        val fileBase = normalize(name.substringBeforeLast('.', name))
        if (fileBase in preferred) return 100
        val header = headerTags(raw)
        val title = normalize(track.title.ifBlank { track.displayName.substringBeforeLast('.') })
        val artist = normalize(track.artist)
        val album = normalize(track.album)
        val tagTitle = normalize(header["ti"].orEmpty())
        val tagArtist = normalize(header["ar"].orEmpty())
        val tagAlbum = normalize(header["al"].orEmpty())
        if (title.isNotBlank() && (fileBase == title || tagTitle == title)) {
            return if (artist.isBlank() || fileBase.contains(artist) || tagArtist == artist) 100 else 90
        }
        if (tagTitle.isNotBlank() && tagTitle == title) return 100
        var score = 0
        if (title.isNotBlank() && (fileBase.contains(title) || tagTitle.contains(title))) score += 60
        if (title.isNotBlank() && tagTitle.contains(title)) score += 50
        if (artist.isNotBlank() && (fileBase.contains(artist) || tagArtist == artist)) score += 30
        // Album/relative-path agreement breaks ties between similarly named sidecars.
        if (album.isNotBlank() && (tagAlbum == album || normalize(track.relativePath).contains(album))) score += 15
        val titleTokens = title.split(' ').filter { it.length > 2 }
        if (titleTokens.isNotEmpty()) {
            val hits = titleTokens.count { fileBase.contains(it) || tagTitle.contains(it) }
            score += (hits * 40) / titleTokens.size
        }
        return score
    }

    private fun headerTags(raw: String): Map<String, String> {
        val field = Regex("^\\[(ti|ar|al):\\s*(.*?)]$", RegexOption.IGNORE_CASE)
        val result = HashMap<String, String>(4)
        for (line in raw.lineSequence().take(40)) {
            val match = field.find(line.trim()) ?: continue
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return result
    }

    /** Lowercase, strip extension-like punctuation and collapse whitespace for comparison. */
    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[\\[\\](){}_.,'’]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val data = stream.readBounded(MAX_LYRICS_BYTES)
            val text = data.toString(Charsets.UTF_8)
            text.takeIf { it.isNotBlank() }
        }
    }.onFailure { Log.w(TAG, "Could not read lyrics from $uri", it) }.getOrNull()

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    private suspend fun parseAndCache(trackId: String, entity: LyricsEntity): Lyrics {
        val source = runCatching { LyricsSource.valueOf(entity.source) }.getOrDefault(LyricsSource.USER)
        return parseRaw(trackId, entity.raw, source)
    }

    /** LRC parsing is CPU work: always run it on Dispatchers.Default, never on the main thread. */
    private suspend fun parseRaw(trackId: String, raw: String, source: LyricsSource): Lyrics {
        val parsed = withContext(Dispatchers.Default) {
            // Malformed lyrics must degrade to plain text, never crash the screen.
            runCatching { LrcParser.parse(raw, source) }.getOrElse {
                Lyrics(source, raw, null, raw.trim().takeIf { text -> text.isNotBlank() })
            }
        }
        parsedCache.put(trackId, parsed)
        return parsed
    }

    /** Parses online lyrics and tags the result with the provider name and a match confidence. */
    private suspend fun parseWithProvider(track: Track, raw: String, provider: String): Lyrics {
        val base = parseRaw(track.id, raw, LyricsSource.LRCLIB).copy(provider = provider)
        val header = lrcHeaders(raw)
        val confidence = confidenceFor(track, header)
        // Slowed/sped-up versions are accepted only when the synced timing still matches the track;
        // otherwise the page warns that the lyrics may be out of sync.
        val outOfSync = LyricsMatcher.isSlowedOrSped(track.title) &&
            base.synced != null && !headerDurationMatches(track, header)
        val parsed = base.copy(confidence = confidence, outOfSync = outOfSync)
        parsedCache.put(track.id, parsed)
        return parsed
    }

    /** Reads `[ti:]`/`[ar:]`/`[al:]`/`[length:]` LRC header fields for match scoring. */
    private fun lrcHeaders(raw: String): Map<String, String> {
        val field = Regex("^\\[(ti|ar|al|length):\\s*(.*?)]$", RegexOption.IGNORE_CASE)
        val result = HashMap<String, String>(4)
        for (line in raw.lineSequence().take(40)) {
            val match = field.find(line.trim()) ?: continue
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return result
    }

    private fun confidenceFor(track: Track, header: Map<String, String>): Double {
        val tagTitle = header["ti"]
        val tagArtist = header["ar"]
        val duration = durationAgreement(track, header)
        if (tagTitle.isNullOrBlank() && tagArtist.isNullOrBlank()) {
            // No header metadata: lean on the track/lyrics length agreement (0.6 when unknown).
            return (0.55 + duration * 0.15).coerceIn(0.0, 1.0)
        }
        val titleScore = if (tagTitle.isNullOrBlank()) 0.6
        else LyricsMatcher.similarity(track.title, tagTitle)
        val artistScore = if (tagArtist.isNullOrBlank()) 0.6
        else LyricsMatcher.similarity(LyricsMatcher.primaryArtist(track.artist), LyricsMatcher.primaryArtist(tagArtist))
        return (titleScore * 0.5 + artistScore * 0.25 + duration * 0.25).coerceIn(0.0, 1.0)
    }

    /** 0..1 agreement between the track duration and an LRC `[length:]` header (0.6 when unknown). */
    private fun durationAgreement(track: Track, header: Map<String, String>): Double {
        val seconds = parseLength(header["length"]) ?: return 0.6
        if (track.durationMs <= 0) return 0.6
        val diff = kotlin.math.abs(track.durationMs - seconds * 1000L)
        return when {
            diff <= 3_000L -> 1.0
            diff <= 10_000L -> 0.5
            else -> 0.2
        }
    }

    /** True when an LRC `[length:]` header is within tolerance of the track duration. */
    private fun headerDurationMatches(track: Track, header: Map<String, String>): Boolean {
        val seconds = parseLength(header["length"]) ?: return false
        if (track.durationMs <= 0) return false
        return kotlin.math.abs(track.durationMs - seconds * 1000L) <= DURATION_TOLERANCE_MS
    }

    private fun parseLength(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(':')
        return runCatching {
            when (parts.size) {
                1 -> parts[0].toDoubleOrNull()
                2 -> parts[0].toLong() * 60 + parts[1].toDouble()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
                else -> null
            }?.toLong()
        }.getOrNull()
    }

    /** Per-song lyrics timing offset in ms (Part J). Stored in the shared provider cache. */
    suspend fun offsetMs(trackId: String): Int = withContext(Dispatchers.IO) {
        runCatching { providerCacheDao?.get(KIND_OFFSET, trackId, PROVIDER_OFFSET)?.payload?.toIntOrNull() }.getOrNull() ?: 0
    }

    suspend fun setOffsetMs(trackId: String, ms: Int) = withContext(Dispatchers.IO) {
        val safe = ms.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        runCatching {
            providerCacheDao?.upsert(
                ProviderCacheEntity(
                    kind = KIND_OFFSET,
                    trackId = trackId,
                    provider = PROVIDER_OFFSET,
                    payload = safe.toString(),
                    found = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        Unit
    }

    private fun okayTitle(track: Track): String = metadataTitle(track)

    private suspend fun cacheProvider(trackId: String, provider: String, payload: String, found: Boolean) {
        runCatching {
            providerCacheDao?.upsert(
                ProviderCacheEntity(
                    kind = KIND_LYRICS,
                    trackId = trackId,
                    provider = provider,
                    payload = payload,
                    found = found,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not cache $provider lyrics for $trackId", it) }
    }

    private companion object {
        const val TAG = "AuralisLyrics"
        const val KIND_LYRICS = "lyrics"
        const val MAX_LYRICS_BYTES = 512 * 1024
        const val MIN_MATCH_SCORE = 50
        /** A "none found" marker is re-queried after this long. */
        const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Per-provider lookup budget (Part Q). */
        const val PROVIDER_TIMEOUT_MS = 6_000L
        /** Budget for the whole parallel lookup. */
        const val OVERALL_BUDGET_MS = 6_000L
        /** How long a shown synced result may be silently upgraded by a better one. */
        const val UPGRADE_WINDOW_MS = 2_000L

        /** Per-song lyrics timing offset (Part J), stored in the shared provider cache. */
        const val KIND_OFFSET = "lyrics_offset"
        const val PROVIDER_OFFSET = "offset"
        /** The user may nudge timing by at most ±5 s. */
        const val MAX_OFFSET_MS = 5_000
        /** How far an LRC `[length:]` header may differ from the track duration and still count as synced. */
        const val DURATION_TOLERANCE_MS = 5_000L
    }
}
