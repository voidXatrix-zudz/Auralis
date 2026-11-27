package com.auralis.app.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.BuildConfig
import com.auralis.app.data.db.LyricsDao
import com.auralis.app.data.db.LyricsEntity
import com.auralis.app.data.db.ProviderCacheDao
import com.auralis.app.data.db.ProviderCacheEntity
import com.auralis.app.data.db.SourceDao
import com.auralis.app.data.lyrics.providers.BetterLyricsPortatoProvider
import com.auralis.app.data.lyrics.providers.GeniusProvider
import com.auralis.app.data.lyrics.providers.LrcRedProvider
import com.auralis.app.data.lyrics.providers.MusixmatchProvider
import com.auralis.app.data.lyrics.providers.PaxSenixMusixmatchProvider
import com.auralis.app.data.lyrics.providers.PaxSenixProvider
import com.auralis.app.data.lyrics.providers.PaxSenixSpotifyProvider
import com.auralis.app.data.lyrics.providers.UnisonProvider
import com.auralis.app.data.lyrics.providers.YouTubeCaptionsProvider
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.LyricsSync
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Minimum text similarity for two providers' lyrics to count as the same song's lyrics. */
internal const val LYRICS_AGREEMENT_THRESHOLD = 0.48

/**
 * Whether [candidate] may replace [shown]: only a strictly better sync tier (PLAIN < LINE < WORD).
 * A lower-scored candidate must also match the text already on screen, so a different song's lyrics
 * can never replace it. Equal tiers never swap, so the lyrics do not jump during playback.
 */
internal fun shouldUpgradeLyrics(shown: Lyrics?, candidate: Lyrics): Boolean {
    if (shown == null) return true
    if (candidate.sync.ordinal <= shown.sync.ordinal) return false
    if ((candidate.matchScore ?: 0) >= (shown.matchScore ?: 0)) return true
    return LyricsPrecision.lyricSimilarity(shown.raw, candidate.raw) >= LYRICS_AGREEMENT_THRESHOLD
}

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

    /**
     * Builds the provider set for one lookup. Providers are cheap wrappers around [providerHttp], so
     * rebuilding per lookup costs nothing and lets a key entered in Settings take effect immediately.
     */
    private fun buildProviders(current: AppSettings?): Map<String, LyricsProvider> = listOf(
        LrclibProvider(),
        LrcRedProvider(providerHttp),
        BiniLyricsProvider(providerHttp),
        BetterLyricsProvider(providerHttp, current?.betterLyricsApiKey.orEmpty()),
        BetterLyricsPortatoProvider(providerHttp, current?.betterLyricsApiKey.orEmpty()),
        LyricsPlusProvider(providerHttp),
        UnisonProvider(providerHttp),
        KuGouProvider(providerHttp),
        SimpMusicProvider(providerHttp),
        NetEaseProvider(providerHttp),
        YouTubeMusicLyricsProvider(providerHttp),
        YouTubeCaptionsProvider(providerHttp),
        MusixmatchProvider(providerHttp, current?.musixmatchToken.orEmpty()),
        GeniusProvider(providerHttp, current?.geniusApiKey.orEmpty()),
        PaxSenixProvider(),
        PaxSenixSpotifyProvider(),
        PaxSenixMusixmatchProvider(),
        LyricsOvhProvider(providerHttp),
    ).associateBy { it.id }

    /** Non-blocking lookup used by the screen to show prefetched/cached lyrics instantly. */
    fun cached(trackId: String): Lyrics? = parsedCache.get(trackId)

    /** Commits a still-current lookup result to the in-memory winner cache. */
    fun rememberParsedLyrics(trackId: String, lyrics: Lyrics) {
        parsedCache.put(trackId, lyrics)
    }

    /** Commits a manual provider result after the caller confirms it is still the latest choice. */
    suspend fun rememberSelectedProviderResult(trackId: String, providerId: String, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        runCatching {
            providerCacheDao?.upsert(
                ProviderCacheEntity(KIND_CHOICE, trackId, CHOICE_PROVIDER, providerId, true, System.currentTimeMillis()),
            )
        }
        parsedCache.put(trackId, lyrics)
        Unit
    }

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
        ignoreLocalLyrics: Boolean = false,
        forceProviderFetch: Boolean = false,
        cacheParsedResults: Boolean = true,
        onUpdate: (suspend (Lyrics) -> Unit)? = null,
    ): Lyrics? = withContext(Dispatchers.IO) {
        if (!ignoreMemoryCache) parsedCache.get(track.id)?.let { cached ->
            // Preserve explicitly saved manual text, but an old automatic plain-text winner no
            // longer qualifies now that automatic lyrics require timestamps.
            if (cached.source == LyricsSource.USER || hasTiming(cached)) return@withContext cached
            parsedCache.remove(track.id)
        }
        val stored = runCatching { lyricsDao.forTrack(track.id) }.getOrDefault(emptyList())

        // 1. Manual lyrics always win and are never replaced by automatic discovery.
        if (!ignoreLocalLyrics) {
            stored.firstOrNull { it.source == LyricsSource.USER.name }?.let {
                return@withContext parseAndCache(track.id, it)
            }
        }

        // 2. Embedded lyrics are preferred over sidecar files. A cached copy avoids re-reading the
        //    audio file on every visit, while a stale/missing cache falls back to the real tag.
        if (!ignoreLocalLyrics) {
            stored.firstOrNull { it.source == LyricsSource.EMBEDDED.name }?.let {
                val parsed = parseAndCache(track.id, it)
                if (hasTiming(parsed)) return@withContext parsed
            }
        }
        // Remote (streamed) songs have no local file, so only the DB cache and LRCLIB apply.
        if (!ignoreLocalLyrics && allowDiscovery && !track.isRemote) {
            val embedded = EmbeddedLyrics.read(context, track)
            if (embedded != null) {
                cache(track, LyricsSource.EMBEDDED, embedded.text)
                val parsed = parseRaw(track.id, embedded.text, LyricsSource.EMBEDDED)
                if (hasTiming(parsed)) return@withContext parsed
            }
        }

        // 3. Sidecar LRC.
        if (!ignoreLocalLyrics) {
            stored.firstOrNull { it.source == LyricsSource.LRC.name }?.let {
                val parsed = parseAndCache(track.id, it)
                if (hasTiming(parsed)) return@withContext parsed
            }
        }
        if (!allowDiscovery && !ignoreLocalLyrics) return@withContext null
        val discovered = if (ignoreLocalLyrics || track.isRemote) null else discover(track)
        if (discovered != null) {
            cache(track, LyricsSource.LRC, discovered.first)
            val parsed = parseRaw(track.id, discovered.first, LyricsSource.LRC)
            if (hasTiming(parsed)) return@withContext parsed
        }

        // 4. Online providers are queried in parallel. Word-synced results outrank line-synced
        //    results; plain provider text is not a lyrics result. A high-confidence word-synced
        //    answer can be shown immediately, while line sync waits for the word search window.
        if (!allowNetwork) return@withContext null
        val current = runCatching { settings?.settings?.first() }.getOrNull()
        val enabled = current?.lyricsProvidersEnabled ?: AppSettings().lyricsProvidersEnabled
        val providerMap = buildProviders(current)
        loadProviderHealth(providerMap.keys)
        val preferredProvider = runCatching {
            providerCacheDao?.get(KIND_CHOICE, track.id, CHOICE_PROVIDER)?.payload
        }.getOrNull()
        // Query all sources; the aggregator ranks word timing above line timing above plain text.
        val requestedOrder = (current?.lyricsProviderOrder ?: AppSettings.DEFAULT_LYRICS_PROVIDER_ORDER)
            .filter { it in enabled }
            .filterNot { it in skipProviders }
        val order = requestedOrder.sortedWith(compareBy<String> { if (it == preferredProvider) 0 else 1 }
                // Start word-capable sources first. Lookups still run concurrently for speed;
                // selection prefers actual word timing, while cross-provider text agreement can
                // still reject a word-timed outlier before line-level results are considered.
                .thenBy { if (providerMap[it]?.supportsWordSync == true) 0 else 1 }
                .thenBy { if (providerMap[it]?.alwaysLast == true) 1 else 0 }
                .thenBy { requestedOrder.indexOf(it) }
                .thenByDescending { providerMap[it]?.priority ?: 100 }
                .thenByDescending { providerHealth[it]?.successRate ?: 0.5 }
                .thenBy { providerHealth[it]?.averageLatencyMs ?: Long.MAX_VALUE })
        return@withContext loadOnlineParallel(track, order, requestedOrder, onUpdate, providerMap, forceProviderFetch, cacheParsedResults)
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
            ignoreLocalLyrics = true,
            forceProviderFetch = true,
            cacheParsedResults = false,
        )
        result
    }

    /** Manual provider selection: only this source is queried, and a successful choice is remembered per track. */
    suspend fun fetchFromProvider(track: Track, providerId: String): Lyrics? = withContext(Dispatchers.IO) {
        val current = runCatching { settings?.settings?.first() }.getOrNull()
        val enabled = current?.lyricsProvidersEnabled ?: AppSettings().lyricsProvidersEnabled
        if (providerId !in enabled) return@withContext null
        val providers = buildProviders(current)
        val query = LyricsQuery(
            okayTitle(track), track.artist, track.album.takeIf(String::isNotBlank),
            track.durationMs.takeIf { it > 0 },
            if (track.isRemote) track.remoteId ?: track.id.removePrefix("yt:") else null,
        )
        // The ViewModel commits the selected result only after its request-generation check. This
        // prevents a cancelled older choice from replacing the parsed cache for the current song.
        val result = searchProvider(track, providerId, query, providers, cacheParsed = false)
        result
    }

    /**
     * Explicit "Retry": forgets every cached provider answer for this track (positive and negative)
     * and looks the song up again. Used by the empty lyrics state.
     */
    suspend fun retry(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        runCatching { providerCacheDao?.deleteKey(KIND_LYRICS, providerCacheKey(track)) }
        parsedCache.remove(track.id)
        load(track, ignoreMemoryCache = true)
    }

    /** The query fields shared by every provider lookup. */
    /** A provider outcome on the results channel ([lyrics] is null when it found or returned nothing). */
    private class LyricsHit(val lyrics: Lyrics?)

    /**
     * Queries every enabled provider in parallel and shows the best answer in stages.
     *
     * Priority is word-synced > line-synced > unsynced. All providers start at once; each has a
     * [PROVIDER_TIMEOUT_MS] budget and the whole search stops after [BACKGROUND_BUDGET_MS].
     *  - A word-synced result scoring at least [WORD_FAST_SCORE] is shown immediately.
     *  - Otherwise the best line-synced (or lower-scored word-synced) result is shown once
     *    [WORD_GRACE_MS] has passed, or as soon as every provider has answered.
     *  - Unsynced text is shown only when nothing timed exists, after [PLAIN_FALLBACK_MS] or when every
     *    provider has answered.
     *  - The search keeps running after the first result and only ever upgrades it
     *    (see [shouldUpgradeLyrics]); it never downgrades or swaps equal tiers.
     *
     * [userOrder] is the user's provider order (a tie-break); [order] is the start order. Returns the
     * best result that was shown, or null when nothing acceptable was found.
     */
    private suspend fun loadOnlineParallel(
        track: Track,
        order: List<String>,
        userOrder: List<String>,
        onUpdate: (suspend (Lyrics) -> Unit)?,
        providers: Map<String, LyricsProvider>,
        forceProviderFetch: Boolean = false,
        cacheParsedResults: Boolean = true,
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
                launch {
                    // cacheParsed = false: only the result that is actually shown is put in parsedCache.
                    hits.send(
                        LyricsHit(
                            searchProvider(
                                track,
                                providerId,
                                query,
                                providers,
                                forceFetch = forceProviderFetch,
                                cacheParsed = false,
                            ),
                        ),
                    )
                }
            }
            launch {
                jobs.joinAll()
                hits.close()
            }

            fun userRank(lyrics: Lyrics): Int =
                userOrder.indexOf(lyrics.provider ?: "").let { if (it < 0) Int.MAX_VALUE / 2 else it }

            fun pickBest(pool: List<Lyrics>): Lyrics? {
                if (pool.isEmpty()) return null
                val agreement = pool.associateWith { candidate ->
                    pool.count { other ->
                        other !== candidate &&
                            LyricsPrecision.lyricSimilarity(candidate.raw, other.raw) >= LYRICS_AGREEMENT_THRESHOLD
                    }
                }
                return pool.maxWithOrNull(
                    compareBy<Lyrics> { it.sync.ordinal }
                        .thenBy { it.matchScore ?: 0 }
                        .thenBy { agreement[it] ?: 0 }
                        .thenBy { -userRank(it) }
                        .thenBy { providerHealth[it.provider ?: ""]?.successRate ?: 0.5 },
                )
            }

            val startedAt = System.currentTimeMillis()
            val candidates = mutableListOf<Lyrics>()
            var shown: Lyrics? = null
            var allDone = false

            suspend fun show(lyrics: Lyrics) {
                shown = lyrics
                if (cacheParsedResults && hasTiming(lyrics)) parsedCache.put(track.id, lyrics)
                onUpdate?.invoke(lyrics)
            }

            fun readyToShow(best: Lyrics, elapsed: Long): Boolean = when {
                best.sync == LyricsSync.WORD && (best.matchScore ?: 0) >= WORD_FAST_SCORE -> true
                best.sync != LyricsSync.PLAIN -> elapsed >= WORD_GRACE_MS || allDone
                else -> elapsed >= PLAIN_FALLBACK_MS || allDone
            }

            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                val remaining = BACKGROUND_BUDGET_MS - elapsed
                if (remaining <= 0L) break
                // Wake for the next provider answer, or for the next stage deadline while nothing is shown.
                val wait = when {
                    shown != null || candidates.isEmpty() -> remaining
                    candidates.any { it.sync != LyricsSync.PLAIN } -> WORD_GRACE_MS - elapsed
                    else -> PLAIN_FALLBACK_MS - elapsed
                }.coerceIn(1L, remaining)

                val received = withTimeoutOrNull(wait) { hits.receiveCatching() }
                if (received != null) {
                    if (received.isClosed) {
                        allDone = true
                    } else {
                        received.getOrNull()?.lyrics?.let { candidates += it }
                    }
                }

                val now = System.currentTimeMillis() - startedAt
                val current = shown
                if (current == null) {
                    val best = pickBest(candidates)
                    if (best != null && readyToShow(best, now)) show(best)
                } else {
                    val upgrade = pickBest(candidates.filter { shouldUpgradeLyrics(current, it) })
                    if (upgrade != null) show(upgrade)
                }
                if (allDone) break
            }
            // Stop any provider still running; the shown result (if any) is the answer.
            coroutineContext.cancelChildren()
            shown
        }
    }

    /** Resolves one provider: cached row first, else a bounded search; both cache their outcome. */
    private suspend fun searchProvider(
        track: Track,
        providerId: String,
        query: LyricsQuery,
        providers: Map<String, LyricsProvider>,
        forceFetch: Boolean = false,
        cacheParsed: Boolean = true,
    ): Lyrics? {
        val cacheKey = providerCacheKey(track)
        val cachedRow = if (forceFetch) null else runCatching { providerCacheDao?.get(KIND_LYRICS, cacheKey, providerId) }.getOrNull()
        if (cachedRow != null) {
            if (!cachedRow.found) {
                if (System.currentTimeMillis() - cachedRow.updatedAt < NEGATIVE_TTL_MS) return null
            } else {
                return parseWithProvider(track, decodeProviderCache(cachedRow.payload).raw, providerId, cacheParsed)
            }
        }
        val provider = providers[providerId] ?: return null
        val started = System.nanoTime()
        for (variant in LyricsMatcher.variants(query.title, query.artist)) {
            val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                runProvider(providerId) {
                    val candidate = provider.searchCandidates(
                        LyricsQuery(variant.title, variant.artist, query.album, query.durationMs, query.videoId),
                    ).firstOrNull()
                    if (candidate == null) null else provider.fetch(candidate)
                }
            } ?: continue
            val raw = result.raw ?: continue
            val accepted = parseWithProvider(track, raw, providerId, cacheParsed)
            if (accepted != null) {
                cacheProvider(cacheKey, providerId, raw, found = true, score = accepted.matchScore)
                recordProviderHealth(providerId, true, (System.nanoTime() - started) / 1_000_000)
                return accepted
            }
            val rejected = LyricsPrecision.check(track, raw)
            if (BuildConfig.DEBUG) AppLog.d(TAG, "Rejected $providerId lyrics: ${rejected.reason} score=${rejected.score}")
        }
        cacheProvider(cacheKey, providerId, "", found = false)
        recordProviderHealth(providerId, false, (System.nanoTime() - started) / 1_000_000)
        return null
    }

    /** Runs a provider call, logging (never surfacing) failures but honouring cancellation. */
    private suspend fun <T> runProvider(providerId: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Log.w(TAG, "Lyrics provider $providerId failed", error)
        AppLog.w(TAG, "Lyrics provider $providerId failed: ${error.message ?: error::class.simpleName}")
        null
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
        runCatching { providerCacheDao?.deleteKey(KIND_LYRICS, providerCacheKey(track)) }
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
                val collection = MediaStore.Files.getContentUri("external")
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
    private suspend fun parseRaw(trackId: String, raw: String, source: LyricsSource, cacheParsed: Boolean = true): Lyrics {
        val parsed = withContext(Dispatchers.Default) {
            // Malformed lyrics must degrade to plain text, never crash the screen.
            runCatching { LrcParser.parse(raw, source) }.getOrElse {
                Lyrics(source, raw, null, raw.trim().takeIf { text -> text.isNotBlank() })
            }
        }
        if (cacheParsed && (source == LyricsSource.USER || hasTiming(parsed))) parsedCache.put(trackId, parsed)
        return parsed
    }

    /** Parses online lyrics and tags the result with the provider name and a match confidence. */
    private suspend fun parseWithProvider(track: Track, raw: String, provider: String, cacheParsed: Boolean = true): Lyrics? {
        val cleanRaw = LyricsPrecision.sanitize(raw)
        val precision = LyricsPrecision.check(track, cleanRaw)
        if (!precision.accepted) return null
        val base = parseRaw(track.id, cleanRaw, LyricsSource.LRCLIB, cacheParsed).copy(provider = provider)
        val header = lrcHeaders(cleanRaw)
        val confidence = confidenceFor(track, header)
        // Slowed/sped-up versions are accepted only when the synced timing still matches the track;
        // otherwise the page warns that the lyrics may be out of sync.
        val outOfSync = LyricsMatcher.isSlowedOrSped(track.title) &&
            base.synced != null && !headerDurationMatches(track, header)
        val parsed = base.copy(confidence = precision.score / 100.0, outOfSync = outOfSync, matchScore = precision.score)
        if (cacheParsed && hasTiming(parsed)) parsedCache.put(track.id, parsed)
        if (BuildConfig.DEBUG) AppLog.d(TAG, "Accepted $provider lyrics score=${precision.score} sync=${base.sync}")
        return parsed
    }

    private fun hasTiming(lyrics: Lyrics): Boolean =
        lyrics.sync != LyricsSync.PLAIN && !lyrics.synced.isNullOrEmpty()

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

    /** Per-song lyrics timing offset in ms. Stored in the shared provider cache. */
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

    private fun providerCacheKey(track: Track): String = listOf(
        track.id,
        LyricsPrecision.normalize(track.artist),
        LyricsPrecision.normalize(okayTitle(track)),
        track.durationMs.coerceAtLeast(0).toString(),
    ).joinToString("|")

    private suspend fun cacheProvider(trackId: String, provider: String, payload: String, found: Boolean, score: Int? = null) {
        runCatching {
            providerCacheDao?.upsert(
                ProviderCacheEntity(
                    kind = KIND_LYRICS,
                    trackId = trackId,
                    provider = provider,
                    payload = if (found) encodeProviderCache(payload, score) else "",
                    found = found,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not cache $provider lyrics for $trackId", it) }
    }

    private data class CachedLyrics(val raw: String, val score: Int?)

    private fun encodeProviderCache(raw: String, score: Int?): String =
        "$CACHE_PREFIX${score ?: 0}|${android.util.Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)}"

    private fun decodeProviderCache(payload: String): CachedLyrics {
        if (!payload.startsWith(CACHE_PREFIX)) return CachedLyrics(payload, null)
        val encoded = payload.removePrefix(CACHE_PREFIX).substringAfter('|', "")
        val score = payload.removePrefix(CACHE_PREFIX).substringBefore('|').toIntOrNull()
        val raw = runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
        return CachedLyrics(raw, score)
    }

    private companion object {
        const val TAG = "AuralisLyrics"
        const val KIND_LYRICS = "lyrics"
        const val CACHE_PREFIX = "auralis-lyrics-v1|"
        const val KIND_CHOICE = "lyrics_provider_choice"
        const val CHOICE_PROVIDER = "selected"
        const val KIND_HEALTH = "lyrics_provider_health"
        const val HEALTH_KEY = "_global"
        const val MAX_LYRICS_BYTES = 512 * 1024
        const val MIN_MATCH_SCORE = 50
        /** A "none found" marker is re-queried after this long. */
        const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
        /** Per-provider lookup budget. */
        const val PROVIDER_TIMEOUT_MS = 7_000L
        /** Whole search incl. background upgrades; a result is normally shown long before this. */
        const val BACKGROUND_BUDGET_MS = 12_000L
        /** A word-synced result at or above this match score is shown at once (header-less results score ~76). */
        const val WORD_FAST_SCORE = 70
        /** How long line-synced results wait for a word-synced one before being shown. */
        const val WORD_GRACE_MS = 1_500L
        /** Unsynced text is shown only after this long (or when every provider has answered). */
        const val PLAIN_FALLBACK_MS = 4_000L

        /** Per-song lyrics timing offset, stored in the shared provider cache. */
        const val KIND_OFFSET = "lyrics_offset"
        const val PROVIDER_OFFSET = "offset"
        /** The user may nudge timing by at most ±5 s. */
        const val MAX_OFFSET_MS = 5_000
        /** How far an LRC `[length:]` header may differ from the track duration and still count as synced. */
        const val DURATION_TOLERANCE_MS = 5_000L
    }

    private data class ProviderHealth(var successes: Int = 0, var failures: Int = 0, var totalLatencyMs: Long = 0) {
        val successRate: Double get() = successes.toDouble() / (successes + failures).coerceAtLeast(1)
        val averageLatencyMs: Long get() = totalLatencyMs / (successes + failures).coerceAtLeast(1)
    }

    private val providerHealth = ConcurrentHashMap<String, ProviderHealth>()

    private suspend fun recordProviderHealth(id: String, success: Boolean, elapsedMs: Long) {
        val health = providerHealth.computeIfAbsent(id) { ProviderHealth() }
        synchronized(health) {
            if (success) health.successes++ else health.failures++
            health.totalLatencyMs += elapsedMs.coerceAtLeast(0)
        }
        runCatching {
            providerCacheDao?.upsert(
                ProviderCacheEntity(
                    kind = KIND_HEALTH,
                    trackId = HEALTH_KEY,
                    provider = id,
                    payload = "${health.successes},${health.failures},${health.totalLatencyMs}",
                    found = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun loadProviderHealth(ids: Set<String>) {
        ids.forEach { id ->
            if (providerHealth.containsKey(id)) return@forEach
            val fields = runCatching { providerCacheDao?.get(KIND_HEALTH, HEALTH_KEY, id)?.payload?.split(',') }.getOrNull()
            if (fields?.size == 3) {
                val row = ProviderHealth(fields[0].toIntOrNull() ?: 0, fields[1].toIntOrNull() ?: 0, fields[2].toLongOrNull() ?: 0)
                providerHealth.putIfAbsent(id, row)
            }
        }
    }

}
