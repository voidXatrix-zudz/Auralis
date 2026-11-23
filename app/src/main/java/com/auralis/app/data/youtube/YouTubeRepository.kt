package com.auralis.app.data.youtube

import android.util.LruCache
import com.auralis.app.data.db.YouTubeCacheDao
import com.auralis.app.data.db.YouTubeCacheEntity
import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.logging.AppLog
import com.auralis.app.logging.SessionLogSanitizer
import com.auralis.app.logging.YouTubeSessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * YouTube source: keyless InnerTube search/radio/suggestions + NewPipeExtractor stream URLs.
 *
 * Both pieces share one OkHttp client so timeouts/connection pooling are configured in one place
 * ([com.auralis.app.AppContainer]). Query results stay in a small in-memory LRU for the session;
 * signed-in account data is additionally cached in Room so a cold start can render instantly.
 */
class YouTubeRepository(
    http: OkHttpClient,
    private val authStore: YouTubeAuthStore? = null,
    settings: com.auralis.app.data.settings.SettingsRepository? = null,
    private val cache: YouTubeCacheDao? = null,
    /** Invisible-WebView refresh seam; null in tests and when the platform cannot host a WebView. */
    private val silentRefresh: (suspend () -> YouTubeAuth?)? = null,
    /** The embedded YouTube.js provider (QuickJS), or null when it could not be built. */
    private val youtubeJs: YouTubeJsProvider? = null,
    /** Whether the active network is metered; drives the per-network provider preference. */
    private val metered: () -> Boolean = { false },
    /**
     * The current player signature timestamp (`sts`), when it can be read. The history-reporting
     * `player` request needs it to be issued a tracking block; the container sources it from the
     * embedded player engine, and a null result simply omits it.
     */
    private val signatureTimestamp: suspend () -> Int? = { null },
    /** Test seam; production leaves this null and InnerTube uses OkHttp. */
    transport: InnerTubeTransport? = null,
) {
    private val innerTube = InnerTubeClient(
        http,
        auth = { authStore?.snapshot() },
        onAuthExpired = {
            // Only reached after a session-defining call, a refused account-menu verification, a
            // failed silent refresh and a refused retry. markExpired() is idempotent, so the
            // "Session expired" message is emitted at most once per expiry.
            val before = authStore?.snapshot()
            if (before?.signedIn == true) {
                authStore.markExpired("expired:verified")
                cachedHome = null
                _authExpired.tryEmit(Unit)
            }
        },
        // Google rotates session cookies on its replies (__Secure-1PSIDTS, *SIDCC, …). Persisting
        // them keeps the stored session usable after the app has been closed for a while.
        onSessionCookies = { merged ->
            authStore?.snapshot()?.let { current ->
                if (current.cookie != merged) {
                    authStore.save(
                        current.copy(cookie = merged, lastRefreshedAt = System.currentTimeMillis()),
                    )
                    YouTubeSessionLog.record(
                        "auth",
                        "cookies rotated names=[" +
                            SessionLogSanitizer.cookieNames(merged).joinToString(",") + "]",
                    )
                }
            }
        },
        refreshSession = { refreshAfterRefusal() },
        transport = transport,
        signatureTimestamp = signatureTimestamp,
    )

    /** Outbound history reporting; [innerTube] owns the request shape and the no-expiry rule. */
    val playbackHistory: PlaybackHistoryGateway = innerTube

    /**
     * Self-heal for a session flagged expired: account_menu, then silent refresh, then a 30-minute
     * backoff. Declared after [innerTube] because the client calls [SessionHealer.refreshNow] only
     * from a request, never during construction.
     */
    private val healer = SessionHealer(
        snapshot = { authStore?.snapshot() },
        persist = { refreshed -> authStore?.save(refreshed) },
        probe = { innerTube.probeStoredSession() },
        refresh = { silentRefresh?.invoke() },
        lastFailureAt = { authStore?.lastHealFailureAt() ?: 0L },
        recordFailureAt = { at -> authStore?.recordHealFailure(at) },
        clearFailure = { authStore?.clearHealFailure() },
    )

    /** One refresh at a time, whichever trigger asked (startup, foreground, Settings, 401 path). */
    private val sessionRefreshLock = Mutex()

    /** Blocking bridge for [InnerTubeClient] (runs on its IO thread, never on the UI thread). */
    private fun refreshAfterRefusal(): Boolean = runBlocking {
        sessionRefreshLock.withLock { healer.refreshNow("401 verified refusal") }
    }
    private val resolver = StreamResolver(
        http,
        settingsRepo = settings,
        youtubeJs = youtubeJs,
        metered = metered,
    )
    private val settingsStore = settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Mirrors the "YouTube.js source" toggle so the search fallback can honour it cheaply. */
    @Volatile private var youtubeJsEnabled: Boolean = true

    init {
        settingsStore?.let { store ->
            scope.launch {
                runCatching { store.settings.collect { youtubeJsEnabled = it.youtubeJsEnabled } }
            }
        }
    }
    private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Emits once each time InnerTube rejects the stored session. AppViewModel listens and drops the
     * Home feed back to state 2 with the "Session expired" message.
     */
    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authExpired = _authExpired.asSharedFlow()

    /** True while the session is present and usable; cache keys are split on this. */
    private val signedIn: Boolean get() = authStore?.snapshot()?.signedIn == true

    /** The user's "Songs only (strict)" choice; read per call so a settings change applies at once. */
    private suspend fun songsOnly(): Boolean =
        runCatching { settingsStore?.settings?.first()?.youtubeSongsOnly == true }.getOrDefault(false)

    /**
     * Applies the shared songs-only rule to a list that may come from Room (old cache) or from the
     * network. Filtering happens on read, so a cached response is filtered with the current setting
     * rather than the setting that was active when it was written.
     */
    private suspend fun musicOnly(songs: List<YtSong>, label: String): List<YtSong> {
        val (kept, hidden) = filterMusicTracksCounting(songs, strict = songsOnly())
        if (hidden > 0) AppLog.i(TAG, "$label: hid $hidden non-song item(s) of ${songs.size}")
        return kept
    }

    @Volatile
    private var cachedHome: YouTubeHomeFeed? = null
    @Volatile
    private var cachedHomeSignedIn: Boolean? = null

    private val searchCache = LruCache<String, List<YtSong>>(SEARCH_CACHE_ENTRIES)
    private val radioCache = LruCache<String, List<YtSong>>(RADIO_CACHE_ENTRIES)
    private val suggestionCache = LruCache<String, List<String>>(SEARCH_CACHE_ENTRIES)
    private val cardCache = LruCache<String, List<YtCard>>(SEARCH_CACHE_ENTRIES)
    private val browseCardCache = LruCache<String, List<YtCard>>(SEARCH_CACHE_ENTRIES)
    private val browseCache = LruCache<String, List<YtSong>>(SEARCH_CACHE_ENTRIES)
    private val moodCache = LruCache<String, List<YtSong>>(SEARCH_CACHE_ENTRIES)
    private val artistImageCache = LruCache<String, String>(SEARCH_CACHE_ENTRIES)
    private val radioTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * In-flight call de-duplication. Home requests the charts page and the same fallback queries
     * from several rows at once; this makes concurrent identical calls share one network round-trip
     * (the LRUs above only catch sequential repeats).
     */
    private val inflightLock = Mutex()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<Any>>()

    private suspend fun <T : Any> once(key: String, load: suspend () -> T): T {
        inflightLock.withLock { inflight[key] }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.await() as T
        }
        val deferred = scope.async { load() as Any }
        val winner = inflightLock.withLock { inflight.putIfAbsent(key, deferred) }
        if (winner != null) {
            deferred.cancel()
            @Suppress("UNCHECKED_CAST")
            return winner.await() as T
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            deferred.await() as T
        } finally {
            inflightLock.withLock { inflight.remove(key) }
        }
    }

    /** Opens the shared HTTP/2 connection before the user types; safe to call more than once. */
    fun warmUp() {
        scope.launch { innerTube.warmUp() }
        // Build the YouTube.js engine in the background only when the feature is on, so a disabled
        // toggle never pays the QuickJS/bundle start-up cost.
        if (youtubeJsEnabled) youtubeJs?.warmUp()
    }

    /**
     * Songs-only search. A repeat query is served from the LRU without touching the network.
     * [gl] localises the results (e.g. "IN" for Trending in India, "US" for Global Top Hits), and is
     * part of the cache key so the same query in two regions does not collide.
     */
    suspend fun search(query: String, gl: String = "US"): List<YtSong> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val key = "$gl|${trimmed.lowercase()}"
        searchCache.get(key)?.let {
            AppLog.i(TAG, "Search cache hit (${it.size} songs)")
            return it
        }
        return once("search:$key") {
            searchCache.get(key)?.let { return@once it }
            var songs = innerTube.searchSongs(trimmed, gl)
            if (songs.isEmpty() && youtubeJsEnabled) {
                // InnerTube came back empty (a songs-only query it did not understand, or a soft
                // block); try the YouTube.js engine once before giving up.
                val viaJs = runCatching { youtubeJs?.searchSongs(trimmed) }.getOrNull()
                if (!viaJs.isNullOrEmpty()) {
                    AppLog.i(TAG, "Search fell back to YouTube.js (${viaJs.size} songs)")
                    songs = viaJs
                }
            }
            searchCache.put(key, songs)
            songs
        }
    }

    /** Mixed search cards (artists/albums/playlists) for the Home discovery rows; session-cached. */
    suspend fun searchCards(query: String, gl: String = "US"): List<YtCard> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val key = "$gl|${trimmed.lowercase()}"
        cardCache.get(key)?.let { return it }
        return once("cards:$key") {
            cardCache.get(key)?.let { return@once it }
            val cards = innerTube.searchCards(trimmed, gl)
            if (cards.isNotEmpty()) cardCache.put(key, cards)
            cards
        }
    }

    /** Cards from a browse page (artist/album/playlist entities), session-cached. */
    suspend fun browseCards(browseId: String): List<YtCard> {
        browseCardCache.get(browseId)?.let { return it }
        return once("browsecards:$browseId") {
            browseCardCache.get(browseId)?.let { return@once it }
            val cards = innerTube.browseCards(browseId)
            if (cards.isNotEmpty()) browseCardCache.put(browseId, cards)
            cards
        }
    }

    /** The artist page header image (Part L); session-cached, null when the page has none. */
    suspend fun artistImage(browseId: String): String? {
        artistImageCache.get(browseId)?.let { return it.ifBlank { null } }
        val image = once("artistimage:$browseId") { innerTube.artistImage(browseId).orEmpty() }
        artistImageCache.put(browseId, image)
        return image.ifBlank { null }
    }

    /** InnerTube "Up next" automix for a video id. The returned page also stores its continuation. */
    suspend fun radio(videoId: String): List<YtSong> {
        radioCache.get(videoId)?.let {
            AppLog.d(TAG, "Radio cache hit (${it.size} songs)")
            return it
        }
        val page = innerTube.radio(videoId)
        page.continuation?.let { radioTokens[videoId] = it }
        val songs = musicOnly(page.songs, "Radio")
        if (songs.isNotEmpty()) radioCache.put(videoId, songs)
        return songs
    }

    /**
     * More automix songs for [videoId], for refilling a queue that is running dry.
     *
     * Uses the continuation token from the previous page so new songs arrive instead of the same
     * cached first page. If there is no token (or it fails), reseeds from [fallbackVideoId] — the
     * last song already queued — and filters everything in [seen] by video id.
     */
    suspend fun radioNext(
        videoId: String,
        fallbackVideoId: String?,
        seen: Set<String>,
    ): List<YtSong> {
        val token = radioTokens[videoId]
        val page = if (token != null) {
            runCatching { innerTube.radioContinuation(token) }
                .getOrElse { innerTube.radio(fallbackVideoId ?: videoId) }
        } else {
            innerTube.radio(fallbackVideoId ?: videoId)
        }
        page.continuation?.let { radioTokens[videoId] = it } ?: radioTokens.remove(videoId)
        val fresh = musicOnly(page.songs, "Radio refill")
            .filter { it.videoId !in seen }
            .distinctBy { it.videoId }
        AppLog.i(
            TAG,
            "Radio refill via ${if (token != null) "continuation" else "reseed"}: ${fresh.size} new songs",
        )
        return fresh
    }

    /** Autocomplete suggestions, used only to correct a zero-result search. */
    suspend fun suggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val key = trimmed.lowercase()
        suggestionCache.get(key)?.let { return it }
        val values = innerTube.suggestions(trimmed)
        if (values.isNotEmpty()) suggestionCache.put(key, values)
        return values
    }

    /** Best audio URL for one video: cached ~1 hour, in-flight de-duplicated, retried once. */
    suspend fun resolveStream(videoId: String): String = resolver.resolve(videoId)

    /** Fills missing title/artist/duration/artwork for a queue entry; null when unavailable. */
    suspend fun metadata(videoId: String): YtSong? =
        runCatching { innerTube.songMetadata(videoId) }.getOrNull()

    /** Best downloadable audio (Opus, else AAC) for the download worker. */
    suspend fun resolveBestAudio(videoId: String): ResolvedAudio = resolver.resolveBest(videoId)

    /** Blocking bridge for Media3's background data-source resolver (never called on the UI thread). */
    fun resolveStreamBlocking(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
        force: ResolverKind? = null,
        bypassCache: Boolean = false,
    ): String = resolver.resolveBlocking(videoId, quality, force, bypassCache)

    /** Warm a stream URL ahead of playback so the next transition opens instantly from cache. */
    fun prefetchStream(videoId: String, quality: StreamQuality = StreamQuality.BEST) {
        scope.launch { runCatching { resolver.resolve(videoId, quality) } }
    }

    // ---- Stage 1B: YouTube Music account / home / likes --------------------------------

    /**
     * Last feed is served until [invalidateHome]; signed-in state changes invalidate it.
     *
     * The signed-in feed is exactly the `FEmusic_home` shelves (the page also works anonymously),
     * with a songs-only search shelf as the last-resort fallback.
     */
    suspend fun homeFeed(): YouTubeHomeFeed {
        cachedHome?.takeIf { cachedHomeSignedIn == signedIn }?.let { return it }
        val shelves = when (val result = homeShelves()) {
            is YouTubeLoadResult.Success -> result.value.filter { it.items.isNotEmpty() }
            is YouTubeLoadResult.Failure -> emptyList()
        }
        val usable = shelves.ifEmpty {
            runCatching {
                listOf(
                    YtShelf(
                        "Top Picks for You",
                        search("Top hits").map { song ->
                            YtCard(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song.durationMs)
                        },
                    ),
                )
            }.getOrDefault(emptyList())
        }
        val top = usable.firstOrNull()?.items.orEmpty().take(12)
        val moods = usable
            .filter { MOOD_PATTERN.containsMatchIn(it.title.lowercase()) }
            .flatMap { it.items }
            .distinctBy { it.title }
            .take(12)
        val feed = YouTubeHomeFeed(
            topPicks = top,
            moods = moods,
            shelves = usable.drop(1),
            refreshedAt = System.currentTimeMillis(),
        )
        cachedHome = feed
        cachedHomeSignedIn = signedIn
        return feed
    }

    fun invalidateHome() {
        cachedHome = null
        cachedHomeSignedIn = null
    }

    /**
     * Re-validates a session that was previously flagged expired. One account/account_menu request
     * signs with the stored cookie regardless of the flag; a 2xx clears it, otherwise one silent
     * refresh gets a chance, and only when both fail does the flag stay (with a 30-minute backoff).
     * This rescues a session a transient refusal had marked dead, and it is the only path that
     * clears the flag.
     */
    suspend fun reviveSession(): Boolean =
        healSession(force = false) == SessionHealOutcome.RECOVERED

    /** Self-heal entry point for app start, Settings → YouTube, and the startup revive. */
    suspend fun healSession(force: Boolean = false): SessionHealOutcome {
        val outcome = sessionRefreshLock.withLock { healer.heal(force) }
        if (outcome == SessionHealOutcome.RECOVERED) {
            AppLog.i(TAG, "YouTube session self-healed")
            YouTubeSessionLog.record("heal", "session recovered")
        }
        return outcome
    }

    /**
     * Refresh trigger for app start/foreground and for turning the integration back on: only a
     * session older than six hours is refreshed, so a healthy session is never touched. Shares the
     * 30-minute backoff and the single-refresh lock with [healSession].
     */
    suspend fun refreshSessionIfStale(reason: String = "stale"): Boolean =
        sessionRefreshLock.withLock { healer.refreshIfStale(reason) }

    /** Used by the verified-401 path: refresh the cookie without the expired gate. */
    suspend fun refreshSessionNow(reason: String): Boolean =
        sessionRefreshLock.withLock { healer.refreshNow(reason) }

    /**
     * Ordered `FEmusic_home` shelves in YouTube's order, cached for [HOME_TTL_MS]; stale data is used
     * if offline. A failure is a [YouTubeLoadResult.Failure] so Home can offer a retry instead of
     * pretending the account has no recommendations.
     */
    suspend fun homeShelves(force: Boolean = false): YouTubeLoadResult<List<YtShelf>> = cachedResult(
        key = homeCacheKey(signedIn),
        serializer = YtShelf.serializer(),
        ttlMs = HOME_TTL_MS,
        force = force,
    ) { innerTube.homeShelves() }

    /**
     * Saved playlists (`FEmusic_liked_playlists`, landing page as fallback). Errors are returned as
     * [YouTubeLoadResult.Failure] so the Library can show a retry row instead of "no playlists".
     */
    suspend fun likedPlaylists(force: Boolean = false): YouTubeLoadResult<List<YtPlaylist>> = cachedResult(
        key = playlistCacheKey(),
        serializer = YtPlaylist.serializer(),
        ttlMs = LIBRARY_TTL_MS,
        force = force,
    ) { innerTube.likedPlaylists() }

    /**
     * The last successfully stored playlist list for the current session state, ignoring its TTL.
     * Read once at startup so the Library paints from Room before the first network call.
     */
    suspend fun cachedPlaylists(): List<YtPlaylist>? {
        val dao = cache ?: return null
        val entry = runCatching { dao.get(playlistCacheKey()) }.getOrNull() ?: return null
        return runCatching {
            cacheJson.decodeFromString(ListSerializer(YtPlaylist.serializer()), entry.payload)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun playlistCacheKey(): String =
        "yt_liked_playlists:${if (signedIn) "signed" else "anon"}"

    /** Saved albums and singles (`FEmusic_liked_albums`). */
    suspend fun likedAlbums(): List<YtAlbum> = cachedList(
        "yt_liked_albums:${if (signedIn) "signed" else "anon"}",
        YtAlbum.serializer(),
        LIBRARY_TTL_MS,
    ) { innerTube.likedAlbums() }

    /** Library/subscribed artists. */
    suspend fun likedArtists(): List<YtArtist> = cachedList(
        "yt_liked_artists:${if (signedIn) "signed" else "anon"}",
        YtArtist.serializer(),
        LIBRARY_TTL_MS,
    ) { innerTube.likedArtists() }

    /** Recently played songs (`FEmusic_history`). Old cached rows are filtered on read. */
    suspend fun historySongs(): List<YtSong> = musicOnly(
        cachedList(
            "yt_history:${if (signedIn) "signed" else "anon"}",
            YtSong.serializer(),
            HISTORY_TTL_MS,
        ) { innerTube.historySongs() },
        "History",
    )

    /** Songs saved to the account library (`FEmusic_liked_videos`), filtered on read. */
    suspend fun librarySongs(): List<YtSong> = musicOnly(
        cachedList(
            "yt_library_songs:${if (signedIn) "signed" else "anon"}",
            YtSong.serializer(),
            LIBRARY_TTL_MS,
        ) { innerTube.librarySongs() },
        "Library songs",
    )

    /**
     * Songs of one playlist/album in `VL<id>` form, cached per browse id *and* session state
     * (`yt_playlist:<id>:signed`). The raw response is cached; the songs-only rule is applied when
     * the list is read, so switching the filter immediately changes what the screen shows.
     */
    suspend fun playlistSongs(playlistId: String, force: Boolean = false): YouTubeLoadResult<List<YtSong>> {
        val browseId = normalizePlaylistBrowseId(playlistId)
        val key = playlistCacheKey(browseId)
        val dao = cache
        val cached = if (force) null else runCatching { dao?.get(key) }.getOrNull()
        if (cached != null && System.currentTimeMillis() - cached.refreshedAt < LIBRARY_TTL_MS) {
            decodeSongs(cached.payload)?.let {
                return YouTubeLoadResult.Success(musicOnly(it, "Playlist"))
            }
        }
        val fresh = innerTube.playlistSongsResult(browseId)
        return when (fresh) {
            is YouTubeLoadResult.Failure -> {
                // Offline or refused: keep the last good page rather than blanking the screen.
                val stale = cached?.let { decodeSongs(it.payload) }
                if (stale != null) YouTubeLoadResult.Success(musicOnly(stale, "Playlist (cached)")) else fresh
            }
            is YouTubeLoadResult.Success -> {
                if (fresh.value.isNotEmpty()) {
                    runCatching {
                        dao?.put(
                            YouTubeCacheEntity(
                                cacheKey = key,
                                payload = cacheJson.encodeToString(ListSerializer(YtSong.serializer()), fresh.value),
                                refreshedAt = System.currentTimeMillis(),
                            ),
                        )
                    }.onFailure { AppLog.d(TAG, "YouTube cache write skipped: ${it.message}") }
                }
                YouTubeLoadResult.Success(musicOnly(fresh.value, "Playlist"))
            }
        }
    }

    /** The last stored page for one playlist, ignoring its TTL; used to paint the screen instantly. */
    suspend fun cachedPlaylistSongs(playlistId: String): List<YtSong> {
        val entry = runCatching { cache?.get(playlistCacheKey(normalizePlaylistBrowseId(playlistId))) }.getOrNull()
            ?: return emptyList()
        return musicOnly(decodeSongs(entry.payload).orEmpty(), "Playlist (cached)")
    }

    private fun playlistCacheKey(browseId: String): String = playlistCacheKeyFor(browseId, signedIn)

    private fun decodeSongs(payload: String): List<YtSong>? = runCatching {
        cacheJson.decodeFromString(ListSerializer(YtSong.serializer()), payload)
    }.getOrNull()

    /** More cards for a Home shelf past the first page. */
    suspend fun homeContinuation(token: String): List<YtShelf> =
        runCatching { innerTube.homeShelvesContinuation(token) }.getOrDefault(emptyList())

    /** Clears every cached YouTube response (Disconnect) without touching the local library. */
    suspend fun clearCachedData() {
        invalidateHome()
        searchCache.evictAll()
        radioCache.evictAll()
        suggestionCache.evictAll()
        cardCache.evictAll()
        browseCardCache.evictAll()
        browseCache.evictAll()
        moodCache.evictAll()
        artistImageCache.evictAll()
        radioTokens.clear()
        runCatching { cache?.clear() }
    }

    /**
     * One Room-backed list cache. A fresh row is returned without touching the network; a failed
     * refresh falls back to the stale row, so Home keeps rendering while offline.
     */
    private suspend fun <T> cachedList(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        force: Boolean = false,
        load: suspend () -> List<T>,
    ): List<T> {
        val dao = cache
        val listSerializer = ListSerializer(serializer)
        val entry = if (force || dao == null) null else runCatching { dao.get(key) }.getOrNull()
        if (entry != null && System.currentTimeMillis() - entry.refreshedAt < ttlMs) {
            runCatching { cacheJson.decodeFromString(listSerializer, entry.payload) }
                .getOrNull()?.let { return it }
        }
        val fresh = runCatching { load() }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) {
            runCatching {
                dao?.put(
                    YouTubeCacheEntity(
                        cacheKey = key,
                        payload = cacheJson.encodeToString(listSerializer, fresh),
                        refreshedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { AppLog.d(TAG, "YouTube cache write skipped: ${it.message}") }
            return fresh
        }
        // Network failed (or returned nothing): keep showing the last good list.
        if (entry != null) {
            runCatching { cacheJson.decodeFromString(listSerializer, entry.payload) }
                .getOrNull()?.let { return it }
        }
        return emptyList()
    }

    /**
     * Cache for account lists that must distinguish "failed" from "empty". A successful non-empty
     * page is stored; an empty or failed result is never written, so an error can never erase a
     * good cached list. A fresh cached row is returned without touching the network.
     */
    private suspend fun <T> cachedResult(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        force: Boolean = false,
        load: suspend () -> YouTubeLoadResult<List<T>>,
    ): YouTubeLoadResult<List<T>> {
        val dao = cache
        val listSerializer = ListSerializer(serializer)
        val entry = if (force || dao == null) null else runCatching { dao.get(key) }.getOrNull()
        if (entry != null && System.currentTimeMillis() - entry.refreshedAt < ttlMs) {
            runCatching { cacheJson.decodeFromString(listSerializer, entry.payload) }
                .getOrNull()?.let { return YouTubeLoadResult.Success(it) }
        }
        return when (val fresh = load()) {
            is YouTubeLoadResult.Failure -> fresh
            is YouTubeLoadResult.Success -> {
                if (fresh.value.isNotEmpty()) {
                    runCatching {
                        dao?.put(
                            YouTubeCacheEntity(
                                cacheKey = key,
                                payload = cacheJson.encodeToString(listSerializer, fresh.value),
                                refreshedAt = System.currentTimeMillis(),
                            ),
                        )
                    }.onFailure { AppLog.d(TAG, "YouTube cache write skipped: ${it.message}") }
                }
                fresh
            }
        }
    }

    suspend fun browseSongs(browseId: String): List<YtSong> {
        browseCache.get(browseId)?.let { return it }
        return once("browse:$browseId") {
            browseCache.get(browseId)?.let { return@once it }
            val songs = innerTube.browseSongs(browseId)
            if (songs.isNotEmpty()) browseCache.put(browseId, songs)
            songs
        }
    }

    /**
     * Streaming-only songs for one Explore mood. Prefers the real YouTube Music mood/genre shelf
     * (the browse id already exposed by the home feed's mood cards) and falls back to a songs-only
     * InnerTube search seeded into the existing automix radio. Session-cached per mood; never reads
     * the local library or downloads.
     */
    suspend fun moodSongs(mood: String): List<YtSong> {
        val title = mood.trim()
        if (title.isBlank()) return emptyList()
        val key = "mood:${title.lowercase()}"
        moodCache.get(key)?.let { return it }
        return once(key) {
            moodCache.get(key)?.let { return@once it }
            val songs = withContext(Dispatchers.IO) {
                val browseId = runCatching {
                    homeFeed().moods.firstOrNull { card ->
                        card.browseId != null && card.title.contains(title, ignoreCase = true)
                    }?.browseId
                }.getOrNull()
                val browsed = browseId?.let { id ->
                    runCatching { innerTube.browseSongs(id) }.getOrDefault(emptyList())
                }.orEmpty()
                if (browsed.size >= MIN_MOOD_BROWSE) {
                    browsed.distinctBy { it.videoId }
                } else {
                    val searched = runCatching { innerTube.searchSongs("$title songs") }
                        .getOrDefault(emptyList())
                    val similar = searched.firstOrNull()?.videoId?.let { videoId ->
                        runCatching { innerTube.radio(videoId).songs }.getOrDefault(emptyList())
                    }.orEmpty()
                    (browsed + searched + similar)
                        .filter { it.videoId.isNotBlank() }
                        .distinctBy { it.videoId }
                }
            }.take(MOOD_SONG_LIMIT)
                .let { musicOnly(it, "Mood") }
            if (songs.isNotEmpty()) moodCache.put(key, songs)
            songs
        }
    }

    suspend fun accountInfo(): Pair<String, String>? = innerTube.accountInfo()

    suspend fun likedSongs(): List<YtSong> = musicOnly(innerTube.likedSongs(), "Liked songs (VLLM)")

    /**
     * One VLLM read split into what should be kept and the ids that must leave the local mirror.
     * A non-song the user liked while the filter was off (or on an older build) is removed on the
     * next sync; every song that passes is left alone, so a hand-hearted song is never dropped.
     */
    suspend fun likedSongsForSync(): Pair<List<YtSong>, List<String>> {
        val raw = innerTube.likedSongs()
        val strict = songsOnly()
        val kept = raw.filter { isMusicTrack(it, strict) }
        val hidden = raw.filterNot { isMusicTrack(it, strict) }.map { it.videoId }
        if (hidden.isNotEmpty()) {
            AppLog.i(TAG, "Liked songs: hid ${hidden.size} non-song item(s) of ${raw.size}")
        }
        return kept to hidden
    }

    /**
     * One liked-songs read for the live-sync engine, preserving success/failure so the poller can
     * tell a rejected session from an offline device. [fastPage] reads only the newest page.
     */
    suspend fun likedSongsSyncResult(
        fastPage: Boolean = false,
    ): YouTubeLoadResult<Pair<List<YtSong>, List<String>>> {
        val raw = if (fastPage) innerTube.likedSongsFirstPageResult() else innerTube.likedSongsResult()
        return when (raw) {
            is YouTubeLoadResult.Failure -> raw
            is YouTubeLoadResult.Success -> {
                val strict = songsOnly()
                val kept = raw.value.filter { isMusicTrack(it, strict) }
                val hidden = raw.value.filterNot { isMusicTrack(it, strict) }.map { it.videoId }
                YouTubeLoadResult.Success(kept to hidden)
            }
        }
    }

    /** Same result and ordering as [likedPlaylists]; kept for the existing Library call site. */
    suspend fun libraryPlaylists(): YouTubeLoadResult<List<YtPlaylist>> = likedPlaylists()

    /** Account name/avatar plus the session check the library sequence depends on. */
    suspend fun accountMenu(): YouTubeLoadResult<Pair<String, String>> = innerTube.accountMenu()

    /** Settings diagnostics: account menu, saved playlists and liked songs, in order. */
    suspend fun connectionTest(): List<YouTubeEndpointCheck> = innerTube.connectionTest()

    suspend fun setLiked(videoId: String, liked: Boolean): Boolean = innerTube.setLiked(videoId, liked)

    // ---- Account playlist mutations + playback history (Part E) -----------------------------

    suspend fun createPlaylist(title: String): String? = innerTube.createPlaylist(title)

    suspend fun renamePlaylist(playlistId: String, title: String): Boolean =
        innerTube.renamePlaylist(playlistId, title)

    suspend fun deletePlaylist(playlistId: String): Boolean = innerTube.deletePlaylist(playlistId)

    suspend fun addPlaylistVideo(playlistId: String, videoId: String): Boolean {
        val ok = innerTube.addPlaylistVideo(playlistId, videoId)
        if (ok) browseCache.remove("VL$playlistId")
        return ok
    }

    suspend fun removePlaylistVideo(playlistId: String, videoId: String): Boolean {
        val ok = innerTube.removePlaylistVideo(playlistId, videoId)
        if (ok) browseCache.remove("VL$playlistId")
        return ok
    }

    private companion object {
        const val TAG = "YouTube"
        const val SEARCH_CACHE_ENTRIES = 24
        const val RADIO_CACHE_ENTRIES = 20
        /** A mood browse shelf with fewer songs than this is topped up by search + radio. */
        const val MIN_MOOD_BROWSE = 4
        /** Keep Explore pages snappy: at most this many songs per mood. */
        const val MOOD_SONG_LIMIT = 60
        /** Home is personalised, so it is refreshed more often than the library lists. */
        const val HOME_TTL_MS = 15L * 60L * 1000L
        const val HISTORY_TTL_MS = 5L * 60L * 1000L
        const val LIBRARY_TTL_MS = 60L * 60L * 1000L
        val MOOD_PATTERN = Regex("mood|genre|feel|vibe|chill|energy")
    }
}

/** Signed-in and anonymous Home feeds must never share a cache row. */
internal fun homeCacheKey(signedIn: Boolean): String =
    "home_shelves:${if (signedIn) "signed" else "anon"}"

/** One playlist's cache row: browse id without `VL`, split by session state. */
internal fun playlistCacheKeyFor(browseId: String, signedIn: Boolean): String =
    "yt_playlist:${browseId.removePrefix("VL")}:${if (signedIn) "signed" else "anon"}"
