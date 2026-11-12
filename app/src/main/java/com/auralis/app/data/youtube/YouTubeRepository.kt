package com.auralis.app.data.youtube

import android.util.LruCache
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * YouTube source: keyless InnerTube search/radio/suggestions + NewPipeExtractor stream URLs.
 *
 * Both pieces share one OkHttp client so timeouts/connection pooling are configured in one place
 * ([com.auralis.app.AppContainer]). Query results stay in a small in-memory LRU for the session;
 * nothing here is written to the library database.
 */
class YouTubeRepository(
    http: OkHttpClient,
    private val authStore: YouTubeAuthStore? = null,
) {
    private val innerTube = InnerTubeClient(http, auth = { authStore?.snapshot() })
    private val resolver = StreamResolver(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            val songs = innerTube.searchSongs(trimmed, gl)
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

    /** InnerTube "Up next" automix for a video id. The returned page also stores its continuation. */
    suspend fun radio(videoId: String): List<YtSong> {
        radioCache.get(videoId)?.let {
            AppLog.d(TAG, "Radio cache hit (${it.size} songs)")
            return it
        }
        val page = innerTube.radio(videoId)
        page.continuation?.let { radioTokens[videoId] = it }
        if (page.songs.isNotEmpty()) radioCache.put(videoId, page.songs)
        return page.songs
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
        val fresh = page.songs.filter { it.videoId !in seen }.distinctBy { it.videoId }
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

    /** Best downloadable audio (Opus, else AAC) for the download worker. */
    suspend fun resolveBestAudio(videoId: String): ResolvedAudio = resolver.resolveBest(videoId)

    /** Blocking bridge for Media3's background data-source resolver (never called on the UI thread). */
    fun resolveStreamBlocking(videoId: String): String = resolver.resolveBlocking(videoId)

    /** Warm a stream URL ahead of playback so the next transition opens instantly from cache. */
    fun prefetchStream(videoId: String) {
        scope.launch { runCatching { resolver.resolve(videoId) } }
    }

    // ---- Stage 1B: YouTube Music account / home / likes --------------------------------

    /** Last feed is served until [invalidateHome]; signed-in state changes invalidate it. */
    suspend fun homeFeed(): YouTubeHomeFeed {
        val signedIn = authStore?.snapshot()?.signedIn == true
        cachedHome?.takeIf { cachedHomeSignedIn == signedIn }?.let { return it }
        val feed = innerTube.homeFeed()
        cachedHome = feed
        cachedHomeSignedIn = signedIn
        return feed
    }

    fun invalidateHome() {
        cachedHome = null
        cachedHomeSignedIn = null
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

    suspend fun accountInfo(): Pair<String, String>? = innerTube.accountInfo()

    suspend fun likedSongs(): List<YtSong> = innerTube.likedSongs()

    suspend fun libraryPlaylists(): List<YtPlaylist> = innerTube.libraryPlaylists()

    suspend fun setLiked(videoId: String, liked: Boolean): Boolean = innerTube.setLiked(videoId, liked)

    private companion object {
        const val TAG = "YouTube"
        const val SEARCH_CACHE_ENTRIES = 24
        const val RADIO_CACHE_ENTRIES = 20
    }
}
