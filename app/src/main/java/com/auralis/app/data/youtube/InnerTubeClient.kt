package com.auralis.app.data.youtube

import com.auralis.app.logging.AppLog
import com.auralis.app.logging.ThumbnailTally
import com.auralis.app.logging.YouTubeSessionLog
import com.auralis.app.logging.requestSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * A non-2xx answer from InnerTube. [code] is the HTTP status (0 when the request never completed);
 * [body] is kept only so a failed call can be diagnosed and is never logged verbatim.
 */
open class YouTubeRequestException(
    message: String,
    val code: Int = 0,
    val body: String = "",
) : IllegalStateException(message)

/** Thrown when a signed request was answered with 401/403; the UI may ask the user to sign in again. */
class YouTubeAuthException(
    message: String,
    code: Int = 0,
    body: String = "",
) : YouTubeRequestException(message, code, body)

/** Raw HTTP result. Lets unit tests drive the request path without a network. */
data class InnerTubeResponse(
    val code: Int,
    val body: String,
    /**
     * Raw `Set-Cookie` values. YouTube rotates session cookies (`__Secure-1PSIDTS`, `SIDCC`, …)
     * on its replies; the app persists them so a stored session does not go stale just because the
     * process was closed.
     */
    val setCookies: List<String> = emptyList(),
)

/** Transport seam; production uses OkHttp, tests answer from a table. */
fun interface InnerTubeTransport {
    fun execute(request: Request): InnerTubeResponse
}

/**
 * One page of the "Up next" automix plus the token that fetches the next page.
 * [continuation] is null once YouTube has no further pages for this radio.
 */
data class RadioPage(val songs: List<YtSong>, val continuation: String?)

/**
 * Tiny keyless InnerTube client for YouTube Music search.
 *
 * WEB_REMIX is the same public web client the music.youtube.com site uses, so no API key,
 * OAuth token or account is involved. Only the first result page is requested (Stage 1).
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    /** The stored session, or null for anonymous mode. An expired session is treated as absent. */
    private val auth: () -> YouTubeAuth? = { null },
    /**
     * Called once after a session-defining call was refused **and** a fresh account-menu check
     * refused too. Keeping the callback separate means the "expired" side effect can only happen
     * through the verified path below.
     */
    private val onAuthExpired: () -> Unit = {},
    /** Item cap for one playlist listing; a test shrinks it so the paging loop stays fast. */
    private val playlistCap: Int = MAX_PLAYLISTS,
    /**
     * Called with a merged cookie header when a signed reply rotated a session cookie. Never carries
     * a value to the log; the owner persists it into the stored session.
     */
    private val onSessionCookies: (String) -> Unit = {},
    /**
     * Blocking bridge to the invisible-WebView silent refresh. Returns true only when a fresh,
     * signed session was captured and persisted. Called solely after a 401/403 from a
     * session-defining call and a refused account-menu verification.
     */
    private val refreshSession: () -> Boolean = { false },
    /** Test seam; null means the real OkHttp client is used. */
    private val transport: InnerTubeTransport? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Set once the account menu confirmed a rejection. Reset by the next successful signed call,
     * so "Session expired, log in again" is emitted at most once per expiry.
     */
    @Volatile
    private var sessionExpiryNotified = false

    /** One verification/refresh at a time, so concurrent refusals cannot each start a WebView. */
    @Volatile
    private var sessionCheckInProgress = false

    /** Set after the `player` endpoint refuses history reporting; stops a retry on every song. */
    @Volatile
    private var playbackReportingDisabled = false

    /**
     * A visitor id minted by an anonymous response (`responseContext.visitorData`), used only when
     * the signed-in session did not carry one. Never logged.
     */
    @Volatile
    private var liveVisitorData: String? = null

    /** The live web client version captured at sign-in, or the constant until one exists. */
    private fun clientVersion(): String =
        auth()?.clientVersion?.takeIf { it.isNotBlank() } ?: CLIENT_VERSION

    private fun deviceLanguage(): String = java.util.Locale.getDefault().language.ifBlank { DEFAULT_HL }

    private fun deviceCountry(): String = java.util.Locale.getDefault().country
        .takeIf { it.length == 2 }?.uppercase() ?: DEFAULT_GL

    /**
     * Songs-only search. [gl] is the ISO country the results should be localised to; the same
     * query can return different charts in different regions, which is what powers "Trending in
     * India" (gl="IN") vs "Global Top Hits" (gl="US").
     */
    suspend fun searchSongs(query: String, gl: String = DEFAULT_GL): List<YtSong> = withContext(Dispatchers.IO) {
        val root = searchRoot(query, SONGS_ONLY_PARAMS, gl) ?: return@withContext emptyList()
        parseSongs(root).also { AppLog.d(TAG, "InnerTube search parsed ${it.size} songs") }
    }

    /**
     * Mixed search (songs, artists, albums, playlists) used by the Home discovery rows — chiefly the
     * "Popular Artists" row, where artist entries carry a channel [YtCard.browseId] instead of a
     * video id. The caller filters by subtitle/browseId shape.
     */
    suspend fun searchCards(query: String, gl: String = DEFAULT_GL): List<YtCard> = withContext(Dispatchers.IO) {
        val root = searchRoot(query, params = null, gl = gl) ?: return@withContext emptyList()
        parseSearchCards(root).also { AppLog.d(TAG, "InnerTube card search parsed ${it.size} cards") }
    }

    private fun searchRoot(query: String, params: String?, gl: String): JsonObject? {
        val payload = buildJsonObject {
            put("context", clientContext(gl))
            put("query", query)
            // Songs-only filter (music_shelf_renderer with songs, no videos/albums/playlists).
            if (params != null) put("params", params)
        }.toString()
        return runCatching { postJson(SEARCH_URL, payload) }
            .onFailure { AppLog.e(TAG, "InnerTube search failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * "Up next" radio for one video. Sending the RDAMVM playlist id is what makes InnerTube return
     * the full automix (50 items) instead of just the current song.
     */
    suspend fun radio(videoId: String): RadioPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
        }.toString()

        val body = postBody(NEXT_URL, payload)
        parseRadioPage(body).also { AppLog.d(TAG, "InnerTube radio parsed ${it.songs.size} songs") }
    }

    /**
     * Fetches the next automix page with the continuation token returned by the previous page.
     * The token is the only way to get new songs once the first queue page is exhausted.
     */
    suspend fun radioContinuation(token: String): RadioPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("continuation", token)
            put("isAudioOnly", true)
        }.toString()

        val body = postBody(NEXT_URL, payload)
        parseRadioContinuation(body).also {
            AppLog.d(TAG, "InnerTube radio continuation parsed ${it.songs.size} songs")
        }
    }

    /**
     * Query autocomplete used for a zero-result correction; only the text values are needed.
     *
     * Deliberately anonymous: a signed search suggestion call would record every intermediate
     * keystroke in the account's server-side search history.
     */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("context", clientContext(gl = null, signed = false))
            put("input", query)
        }.toString()
        runCatching { postBody(SUGGESTIONS_URL, payload, anonymous = true) }
            .map(::parseSuggestions)
            .getOrDefault(emptyList())
    }

    /** Opens the HTTP/2 connection early; called once from Application.onCreate. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(WARM_UP_URL).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { it.body?.close() }
            AppLog.i(TAG, "InnerTube connection warmed")
        }.onFailure { AppLog.w(TAG, "InnerTube warm-up skipped", it) }
    }

    // ---- Signed-in YouTube Music (home feed, account, likes, playlists) ---------------------

    /** Personalised when signed in, generic otherwise. Falls back to a search shelf if empty. */
    suspend fun homeFeed(): YouTubeHomeFeed = withContext(Dispatchers.IO) {
        val shelves = runCatching { parseHomeShelves(browseRoot("FEmusic_home")) }
            .getOrDefault(emptyList())
            .filter { it.items.isNotEmpty() }
        val usable = shelves.ifEmpty {
            runCatching {
                listOf(
                    YtShelf(
                        "Top Picks for You",
                        searchSongs("Top hits").map { song ->
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
        YouTubeHomeFeed(topPicks = top, moods = moods, shelves = usable.drop(1))
    }

    /**
     * Songs behind a shelf/playlist card (mood chip, album, playlist). Playlist ids may arrive
     * with or without the `VL` prefix; a bare id is normalised so navigation always works.
     */
    suspend fun browseSongs(browseId: String): List<YtSong> = withContext(Dispatchers.IO) {
        runCatching { pagedSongs(normalizeBrowseId(browseId)) }.getOrDefault(emptyList())
    }

    /** `VL`-prefixed playlist pages versus the `FEmusic_`/`UC`/`MPREb` browse ids. */
    private fun normalizeBrowseId(browseId: String): String {
        val id = browseId.trim()
        return when {
            id.startsWith("VL") -> id
            id.startsWith("FEmusic") || id.startsWith("UC") || id.startsWith("MPREb") -> id
            id.isBlank() -> id
            else -> "VL$id"
        }
    }

    /**
     * Every card on a browse page — both playable songs/videos and navigable artist/album/playlist
     * entities. Used by the Charts and Popular Artists rows: the charts landing page exposes chart
     * playlists (browseId "VL...") and a "Top artists" carousel whose entries carry a channel id.
     */
    suspend fun browseCards(browseId: String): List<YtCard> = withContext(Dispatchers.IO) {
        val out = ArrayList<YtCard>()
        runCatching { collectCards(browseRoot(browseId), out) }
        out.distinctBy { it.videoId ?: it.browseId ?: it.title }
    }

    /**
     * The artist page header image (Part L), used when an artist card carries a browse id but no
     * thumbnail. Reads the immersive/visual header renderer's largest thumbnail.
     */
    suspend fun artistImage(browseId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val root = browseRoot(browseId)
            val header = root["header"]?.asObject() ?: return@runCatching null
            val renderer = header["musicImmersiveHeaderRenderer"]?.asObject()
                ?: header["musicVisualHeaderRenderer"]?.asObject()
                ?: return@runCatching null
            renderer.thumbnailArray()?.lastOrNull()?.asObject()?.get("url")?.asString()
                ?.let(::normalizeArtworkUrl)
        }.getOrNull()
    }

    /**
     * Resolves the account name/avatar. This is also the session check: a 200 here proves the
     * stored cookie still works, which is why only this endpoint may expire the session (see
     * [confirmRefusal]).
     */
    suspend fun accountMenu(affectsSession: Boolean = true): YouTubeLoadResult<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject { put("context", clientContext()) }.toString()
                val root = postJson(ACCOUNT_MENU_URL, payload, affectsSession = affectsSession)
                // The name and photo live on `activeAccountHeaderRenderer`; the whole response is
                // the fallback so a layout that lifts them higher still resolves. Presence only.
                val header = findRenderer(root, "activeAccountHeaderRenderer")
                val scope = header ?: root
                val name = findString(scope, ACCOUNT_NAME_KEYS)?.takeIf { it.isNotBlank() }
                    ?: findString(scope, ACCOUNT_HANDLE_KEYS)?.takeIf { it.isNotBlank() }
                val avatar = findAvatar(scope)
                AppLog.i(
                    TAG,
                    "Account menu: header=${header != null}, name=${name != null}, avatar=${avatar != null}",
                )
                YouTubeLoadResult.Success(name.orEmpty() to avatar.orEmpty())
            } catch (error: Exception) {
                AppLog.w(TAG, "Account menu failed: ${error.message}")
                YouTubeLoadResult.Failure(reasonFor(error), codeOf(error))
            }
        }

    /** Display name and avatar, or null when the menu returned no usable name. */
    suspend fun accountInfo(): Pair<String, String>? =
        (accountMenu() as? YouTubeLoadResult.Success)?.value?.takeIf { it.first.isNotBlank() }

    /** The account's liked-songs playlist (LM). Songs only. */
    suspend fun likedSongs(): List<YtSong> =
        (likedSongsResult() as? YouTubeLoadResult.Success)?.value.orEmpty()

    /**
     * The first page of the liked-songs shelf only, for the fast live-sync lane. It deliberately
     * follows no continuations: the newest likes are at the top, so one cheap page is enough to
     * notice a change without paying for the whole library every four seconds.
     */
    suspend fun likedSongsFirstPageResult(): YouTubeLoadResult<List<YtSong>> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val root = browseRoot("VLLM", affectsSession = true)
            val out = LinkedHashMap<String, YtSong>()
            collectSongsDeep(root, out)
            YouTubeSessionLog.record(
                "load",
                "liked_songs_page HTTP 200 signed=${signedLabel()} songs=${out.size} " +
                    "${System.currentTimeMillis() - startedAt} ms",
            )
            YouTubeLoadResult.Success(out.values.toList())
        } catch (error: Exception) {
            AppLog.w(TAG, "Liked songs (first page) failed: ${error.message}")
            YouTubeSessionLog.record(
                "load",
                "liked_songs_page HTTP ${codeOf(error)} signed=${signedLabel()} " +
                    "failure=${reasonFor(error)} ${System.currentTimeMillis() - startedAt} ms",
            )
            YouTubeLoadResult.Failure(reasonFor(error), codeOf(error))
        }
    }

    /** Liked songs plus the reason a failure happened, for the Library and the connection test. */
    suspend fun likedSongsResult(affectsSession: Boolean = true): YouTubeLoadResult<List<YtSong>> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                val songs = pagedSongs("VLLM", affectsSession)
                val elapsed = System.currentTimeMillis() - startedAt
                YouTubeSessionLog.record(
                    "load",
                    "liked_songs HTTP 200 signed=${signedLabel()} songs=${songs.size} ${elapsed} ms",
                )
                YouTubeLoadResult.Success(songs)
            } catch (error: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                val reason = reasonFor(error)
                AppLog.w(TAG, "Liked songs failed: ${error.message}")
                YouTubeSessionLog.record(
                    "load",
                    "liked_songs HTTP ${codeOf(error)} signed=${signedLabel()} failure=$reason ${elapsed} ms",
                )
                YouTubeLoadResult.Failure(reason, codeOf(error))
            }
        }

    /**
     * Every saved playlist. `FEmusic_liked_playlists` is the real list and is fetched first;
     * `FEmusic_library_landing` is only a fallback when the first page is empty or refused.
     */
    suspend fun likedPlaylists(): YouTubeLoadResult<List<YtPlaylist>> = withContext(Dispatchers.IO) {
        fetchLikedPlaylists(affectsSession = true)
    }

    /** Kept for existing call sites; same result and same ordering as [likedPlaylists]. */
    suspend fun libraryPlaylists(): YouTubeLoadResult<List<YtPlaylist>> = likedPlaylists()

    /**
     * Re-checks the stored cookie without regard to the `expired` flag, so the app can tell a
     * session that was killed by a transient refusal from one Google has really revoked. Signs with
     * the stored session even when flagged, persists any rotated cookie, and returns the final HTTP
     * status (0 when the request could not complete).
     */
    suspend fun probeStoredSession(): Int = withContext(Dispatchers.IO) {
        val stored = auth() ?: return@withContext 0
        if (stored.cookie.isBlank() || stored.sapisid == null) return@withContext 0
        val payload = buildJsonObject { put("context", clientContext(signed = false)) }.toString()
        var signed = stored
        var status = 0
        repeat(2) { attempt ->
            val request = headerBuilder(ACCOUNT_MENU_URL, emptyMap(), anonymous = false, signWith = signed)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = runCatching { perform(request) }.getOrNull() ?: return@withContext 0
            persistRotatedCookies(request, response)
            status = response.code
            if (status != 401 && status != 403) return@withContext status
            // A refusal can carry the replacement rotating cookie; retry once with it.
            val rotated = auth()
            if (attempt == 0 && rotated != null && rotated.cookie != signed.cookie) signed = rotated
            else return@withContext status
        }
        status
    }

    private suspend fun fetchLikedPlaylists(affectsSession: Boolean): YouTubeLoadResult<List<YtPlaylist>> {
        val primary = pagedPlaylists(BROWSE_LIBRARY_PLAYLISTS, affectsSession)
        if (primary is YouTubeLoadResult.Success && primary.value.isNotEmpty()) return primary
        val fallback = pagedPlaylists(LIBRARY_LANDING, affectsSession)
        return when {
            fallback is YouTubeLoadResult.Success && fallback.value.isNotEmpty() -> fallback
            primary is YouTubeLoadResult.Failure -> primary
            else -> primary
        }
    }

    /**
     * Reads every page of a playlist listing. Continuations are followed until YouTube stops
     * supplying one, with both a page cap and an item cap so a looping token cannot hang the app.
     */
    private suspend fun pagedPlaylists(
        browseId: String,
        affectsSession: Boolean,
    ): YouTubeLoadResult<List<YtPlaylist>> {
        val out = LinkedHashMap<String, YtPlaylist>()
        val startedAt = System.currentTimeMillis()
        return try {
            var root: JsonObject? = browseRoot(browseId, affectsSession)
            var token: String? = firstContinuation(root)
            var pages = 0
            while (root != null && pages < MAX_PLAYLIST_PAGES) {
                collectPlaylistsDeep(root, out)
                pages++
                if (out.size >= playlistCap || token == null) break
                root = browseContinuation(token, affectsSession)
                token = firstContinuation(root)
            }
            AppLog.i(TAG, "Library playlists: ${out.size} parsed from $browseId ($pages page(s))")
            YouTubeSessionLog.record(
                "load",
                "library_playlists HTTP 200 signed=${signedLabel()} playlists=${out.size} " +
                    "pages=$pages ${System.currentTimeMillis() - startedAt} ms",
            )
            YouTubeLoadResult.Success(out.values.toList())
        } catch (error: Exception) {
            AppLog.w(TAG, "Library playlists failed from $browseId: ${error.message}")
            YouTubeSessionLog.record(
                "load",
                "library_playlists HTTP ${codeOf(error)} signed=${signedLabel()} " +
                    "failure=${reasonFor(error)} ${System.currentTimeMillis() - startedAt} ms",
            )
            YouTubeLoadResult.Failure(reasonFor(error), codeOf(error))
        }
    }

    /**
     * Diagnostics for Settings: account menu, saved playlists and liked songs, in that order.
     * Deliberately non-expiring (`affectsSession = false`) so pressing the button never changes
     * the session state; each call still writes one non-secret line to the app log.
     */
    suspend fun connectionTest(): List<YouTubeEndpointCheck> = withContext(Dispatchers.IO) {
        listOf(
            runCheck("account_menu") {
                when (val result = accountMenu(affectsSession = false)) {
                    is YouTubeLoadResult.Success -> if (result.value.first.isNotBlank()) 1 else 0
                    is YouTubeLoadResult.Failure ->
                        throw YouTubeRequestException(result.reason, result.code)
                }
            },
            runCheck("liked_playlists") {
                when (val result = fetchLikedPlaylists(affectsSession = false)) {
                    is YouTubeLoadResult.Success -> result.value.size
                    is YouTubeLoadResult.Failure ->
                        throw YouTubeRequestException(result.reason, result.code)
                }
            },
            runCheck("liked_songs") {
                when (val result = likedSongsResult(affectsSession = false)) {
                    is YouTubeLoadResult.Success -> result.value.size
                    is YouTubeLoadResult.Failure ->
                        throw YouTubeRequestException(result.reason, result.code)
                }
            },
        )
    }

    private suspend fun runCheck(
        label: String,
        block: suspend () -> Int,
    ): YouTubeEndpointCheck = try {
        YouTubeEndpointCheck(label, ok = true, code = 200, parsed = block())
    } catch (error: YouTubeRequestException) {
        YouTubeEndpointCheck(label, ok = false, code = error.code, parsed = 0)
    } catch (error: Exception) {
        AppLog.w(TAG, "Connection test '$label' failed: ${error.message}")
        YouTubeEndpointCheck(label, ok = false, code = 0, parsed = 0)
    }

    private fun codeOf(error: Throwable): Int = (error as? YouTubeRequestException)?.code ?: 0

    private fun reasonFor(error: Throwable): String = when (codeOf(error)) {
        401, 403 -> "YouTube refused the stored session"
        0 -> "Network error"
        else -> "HTTP ${codeOf(error)}"
    }

    /** Saved albums and singles (`FEmusic_liked_albums`). */
    suspend fun likedAlbums(): List<YtAlbum> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, YtAlbum>()
        runCatching { collectAlbumsDeep(browseRoot(BROWSE_LIBRARY_ALBUMS), out) }
        out.values.toList()
    }

    /** Library/subscribed artists (`FEmusic_library_corpus_track_artists`, then subscriptions). */
    suspend fun likedArtists(): List<YtArtist> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, YtArtist>()
        runCatching { collectArtistsDeep(browseRoot(BROWSE_LIBRARY_ARTISTS), out) }
        if (out.isEmpty()) runCatching { collectArtistsDeep(browseRoot(BROWSE_SUBSCRIPTIONS), out) }
        out.values.toList()
    }

    /**
     * Home shelves exactly as `FEmusic_home` returns them, in YouTube's order, following the page
     * continuation until roughly a screenful-to-two of shelves (8-12) has been collected.
     *
     * A failure is returned as [YouTubeLoadResult.Failure] so Home can say "couldn't load your
     * recommendations" instead of rendering an empty feed that looks like an empty account.
     */
    suspend fun homeShelves(): YouTubeLoadResult<List<YtShelf>> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val out = ArrayList<YtShelf>()
            var root: JsonObject? = browseRoot(BROWSE_HOME)
            var rendererNames: List<String> = emptyList()
            var token: String? = firstContinuation(root)
            var pages = 0
            while (root != null && pages < MAX_HOME_PAGES && out.size < MAX_HOME_SHELVES) {
                if (rendererNames.isEmpty()) rendererNames = rendererNamesIn(root)
                out += collectShelvesDeep(root)
                pages++
                if (out.size >= MAX_HOME_SHELVES || token == null) break
                root = runCatching { browseContinuation(token) }.getOrNull()
                token = firstContinuation(root)
            }
            val shelves = out
                .filter { it.items.isNotEmpty() }
                .distinctBy { it.title.lowercase() }
                .take(MAX_HOME_SHELVES)
            val elapsed = System.currentTimeMillis() - startedAt
            AppLog.i(
                TAG,
                "Home feed: ${shelves.size} shelves from $pages page(s) " +
                    "renderers=${rendererNames.joinToString(",")} " +
                    "(${shelves.joinToString { "'${it.title}'" }.take(240)})",
            )
            YouTubeSessionLog.record(
                "load",
                "home HTTP 200 signed=${signedLabel()} shelves=${shelves.size} pages=$pages ${elapsed} ms",
            )
            YouTubeLoadResult.Success(shelves)
        } catch (error: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            val reason = reasonFor(error)
            AppLog.w(TAG, "Home feed failed: ${error.message}")
            YouTubeSessionLog.record(
                "load",
                "home HTTP ${codeOf(error)} signed=${signedLabel()} failure=$reason ${elapsed} ms",
            )
            YouTubeLoadResult.Failure(reason, codeOf(error))
        }
    }

    /** `yes`/`no` only; derived without touching any secret. */
    private fun signedLabel(): String = if (auth()?.signedIn == true) "yes" else "no"

    /** More Home shelves past the first page, when YouTube supplied a continuation. */
    suspend fun homeShelvesContinuation(token: String): List<YtShelf> = withContext(Dispatchers.IO) {
        runCatching { collectShelvesDeep(browseContinuation(token)) }.getOrDefault(emptyList())
    }

    /** Recently played songs (`FEmusic_history`), fully paged up to the cap. */
    suspend fun historySongs(): List<YtSong> = withContext(Dispatchers.IO) {
        runCatching { pagedSongs(BROWSE_HISTORY) }.getOrDefault(emptyList())
    }

    /** Songs explicitly saved to the library (`FEmusic_liked_videos`). */
    suspend fun librarySongs(): List<YtSong> = withContext(Dispatchers.IO) {
        val songs = runCatching { pagedSongs(BROWSE_LIBRARY_SONGS) }.getOrDefault(emptyList())
        songs.ifEmpty { runCatching { pagedSongs(BROWSE_LIKED_SONGS) }.getOrDefault(emptyList()) }
    }

    /** Convenience for a playlist id that may or may not already carry the `VL` prefix. */
    suspend fun playlistSongs(playlistId: String): List<YtSong> =
        (playlistSongsResult(playlistId) as? YouTubeLoadResult.Success)?.value.orEmpty()

    /**
     * Playlist contents with a real failure state, so the detail screen can distinguish "the request
     * failed" from "this playlist genuinely has no playable songs".
     */
    suspend fun playlistSongsResult(playlistId: String): YouTubeLoadResult<List<YtSong>> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                val songs = pagedSongs(normalizePlaylistBrowseId(playlistId), limit = PLAYLIST_PAGE_LIMIT)
                YouTubeSessionLog.record(
                    "load",
                    "playlist HTTP 200 signed=${signedLabel()} songs=${songs.size} " +
                        "${System.currentTimeMillis() - startedAt} ms",
                )
                YouTubeLoadResult.Success(songs)
            } catch (error: Exception) {
                AppLog.w(TAG, "Playlist load failed: ${error.message}")
                YouTubeSessionLog.record(
                    "load",
                    "playlist HTTP ${codeOf(error)} signed=${signedLabel()} " +
                        "failure=${reasonFor(error)} ${System.currentTimeMillis() - startedAt} ms",
                )
                YouTubeLoadResult.Failure(reasonFor(error), codeOf(error))
            }
        }

    /**
     * One continuation page. A session-defining caller (the liked-songs read) passes
     * [affectsSession] through so the verified expiry path applies to page two as well.
     */
    private fun browseContinuation(token: String, affectsSession: Boolean = false): JsonObject {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("continuation", token)
        }.toString()
        return postJson(
            BROWSE_URL,
            payload,
            extraQuery = linkedMapOf("ctoken" to token, "continuation" to token, "type" to "next"),
            affectsSession = affectsSession,
        )
    }

    /** Follows browse continuations and returns every song, newest page first, capped at [limit]. */
    private fun pagedSongs(
        browseId: String,
        affectsSession: Boolean = false,
        limit: Int = PAGE_LIMIT,
    ): List<YtSong> {
        val out = LinkedHashMap<String, YtSong>()
        var root: JsonObject? = browseRoot(browseId, affectsSession)
        var token: String? = firstContinuation(root)
        while (root != null) {
            collectSongsDeep(root, out)
            if (out.size >= limit || token == null) break
            root = runCatching { browseContinuation(token, affectsSession) }.getOrNull()
            token = firstContinuation(root)
        }
        return out.values.toList()
    }

    /** Like/unlike for the signed-in account. Returns false when the call could not be made. */
    suspend fun setLiked(videoId: String, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (auth() == null) return@withContext false
        runCatching {
            val payload = buildJsonObject {
                put("context", clientContext())
                putJsonObject("target") { put("videoId", videoId) }
            }.toString()
            postJson(if (liked) LIKE_URL else UNLIKE_URL, payload)
            true
        }.getOrDefault(false)
    }

    // ---- Account playlist mutations (private by default) -----------------------------------

    /** Creates a private playlist titled [title] and returns its (VL-stripped) id, or null. */
    suspend fun createPlaylist(title: String): String? = withContext(Dispatchers.IO) {
        if (auth() == null) return@withContext null
        runCatching {
            val payload = buildJsonObject {
                put("context", clientContext())
                put("title", title)
                put("privacyStatus", "PRIVATE")
            }.toString()
            postJson(PLAYLIST_CREATE_URL, payload)["playlistId"]?.asString()
                ?.takeIf { it.isNotBlank() }?.removePrefix("VL")
        }.onFailure { AppLog.w(TAG, "Playlist create skipped", it) }.getOrNull()
    }

    /** Renames an existing account playlist. */
    suspend fun renamePlaylist(playlistId: String, title: String): Boolean =
        editPlaylist(playlistId, listOf(buildJsonObject {
            put("action", "ACTION_SET_PLAYLIST_NAME")
            put("playlistName", title)
        }))

    /** Adds a video to an existing account playlist. */
    suspend fun addPlaylistVideo(playlistId: String, videoId: String): Boolean =
        editPlaylist(playlistId, listOf(buildJsonObject {
            put("action", "ACTION_ADD_VIDEO")
            put("addedVideoId", videoId)
        }))

    /** Removes a video from an existing account playlist. */
    suspend fun removePlaylistVideo(playlistId: String, videoId: String): Boolean =
        editPlaylist(playlistId, listOf(buildJsonObject {
            put("action", "ACTION_REMOVE_VIDEO")
            put("removedVideoId", videoId)
        }))

    private suspend fun editPlaylist(playlistId: String, actions: List<JsonObject>): Boolean =
        withContext(Dispatchers.IO) {
            if (auth() == null) return@withContext false
            runCatching {
                val payload = buildJsonObject {
                    put("context", clientContext())
                    put("playlistId", playlistId.removePrefix("VL"))
                    put("actions", JsonArray(actions))
                }.toString()
                postJson(BROWSE_EDIT_PLAYLIST_URL, payload)
                true
            }.onFailure { AppLog.w(TAG, "Playlist edit skipped", it) }.getOrDefault(false)
        }

    /** Deletes an existing account playlist. */
    suspend fun deletePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        if (auth() == null) return@withContext false
        runCatching {
            val payload = buildJsonObject {
                put("context", clientContext())
                put("playlistId", playlistId.removePrefix("VL"))
            }.toString()
            postJson(PLAYLIST_DELETE_URL, payload)
            true
        }.onFailure { AppLog.w(TAG, "Playlist delete skipped", it) }.getOrDefault(false)
    }

    // ---- Playback history reporting (best-effort, never throws, never expires a session) ----

    /**
     * Tells YouTube a signed-in song really played, so it lands in the account history. The
     * tracking URLs come from the InnerTube `player` response and require a client-generated `cpn`.
     *
     * This call must never touch the session: WEB_REMIX answers `player` with 400/401/403 for
     * perfectly valid accounts, and treating that as an expiry logged the user out on every play.
     * A refusal is logged once per app run and history reporting then stays off until restart.
     */
    suspend fun reportPlayback(videoId: String, watchedSeconds: Long): Boolean = withContext(Dispatchers.IO) {
        if (auth() == null || playbackReportingDisabled) return@withContext false
        try {
            val payload = buildJsonObject {
                put("context", clientContext())
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString()
            val tracking = postJson(PLAYER_URL, payload)["playbackTracking"]?.asObject()
                ?: return@withContext false
            val playbackUrl = tracking["videostatsPlaybackUrl"]?.asObject()?.get("baseUrl")?.asString()
            val watchtimeUrl = tracking["videostatsWatchtimeUrl"]?.asObject()?.get("baseUrl")?.asString()
            val cpn = randomCpn()
            val seconds = watchedSeconds.coerceAtLeast(HISTORY_MIN_SECONDS)
            playbackUrl?.let { getBlank(trackingUrl(it, cpn, null)) }
            watchtimeUrl?.let { getBlank(trackingUrl(it, cpn, seconds)) }
            !playbackUrl.isNullOrBlank() || !watchtimeUrl.isNullOrBlank()
        } catch (error: Exception) {
            val code = codeOf(error)
            if (code == 400 || code == 401 || code == 403) {
                // One line for the whole run: the player endpoint is not usable with this client.
                if (!playbackReportingDisabled) {
                    playbackReportingDisabled = true
                    AppLog.w(TAG, "player tracking skipped (HTTP $code); history reporting off for this run")
                }
            } else {
                AppLog.d(TAG, "player tracking skipped (HTTP $code)")
            }
            false
        }
    }

    /**
     * Minimal metadata for one song from the InnerTube `player` response, used to fill a queue entry
     * that only had a video id (old History rows, sparse search cards). Best-effort and never throws;
     * like [reportPlayback] it must never expire the session.
     */
    suspend fun songMetadata(videoId: String): YtSong? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("context", clientContext())
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString()
            val details = postJson(PLAYER_URL, payload)["videoDetails"]?.asObject()
                ?: return@runCatching null
            val title = details["title"]?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val seconds = details["lengthSeconds"]?.asString()?.toLongOrNull() ?: 0L
            YtSong(
                videoId = videoId,
                title = title,
                artist = details["author"]?.asString().orEmpty(),
                album = "",
                durationMs = seconds * 1000L,
                thumbnailUrl = fallbackThumbnailUrl(videoId),
            )
        }.getOrNull()
    }

    private fun trackingUrl(base: String, cpn: String, cmtSeconds: Long?): String {
        val separator = if (base.contains('?')) '&' else '?'
        val withCpn = "$base${separator}cpn=$cpn&ver=2&fmt=1"
        return if (cmtSeconds != null) "$withCpn&cmt=$cmtSeconds" else withCpn
    }

    private fun getBlank(url: String) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent(anonymous = false))
                .header("X-Origin", ORIGIN)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
            (auth()?.visitorData?.takeIf { it.isNotBlank() } ?: liveVisitorData)
                ?.let { builder.header("X-Goog-Visitor-Id", it) }
            sign(builder, auth()?.takeIf { it.signedIn })
            http.newCall(builder.build()).execute().use { it.body?.close() }
        }
    }

    private fun randomCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        val random = java.security.SecureRandom()
        return buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun browseRoot(browseId: String, affectsSession: Boolean = false): JsonObject {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("browseId", browseId)
        }.toString()
        return postJson(BROWSE_URL, payload, affectsSession = affectsSession)
    }

    private fun postJson(
        url: String,
        payload: String,
        anonymous: Boolean = false,
        extraQuery: Map<String, String> = emptyMap(),
        /**
         * True only for the calls that define the session: account_menu and the library brows
         * (`FEmusic_liked_playlists`, `FEmusic_library_landing`, `VLLM`). Everything else — the
         * player, tracking, likes, radio, search, playlist edits, Home/mood/artist pages — must
         * never expire the session, because a refusal there says nothing about the cookie.
         */
        affectsSession: Boolean = false,
    ): JsonObject {
        val body = postBody(url, payload, anonymous, extraQuery, affectsSession)
        return runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw IllegalStateException("YouTube returned an unreadable response")
    }

    /** One POST with the shared WEB_REMIX headers; [anonymous] strips Cookie/Authorization/onBehalfOfUser. */
    private fun postBody(
        url: String,
        payload: String,
        anonymous: Boolean = false,
        extraQuery: Map<String, String> = emptyMap(),
        affectsSession: Boolean = false,
    ): String {
        val body = try {
            sendOnce(url, payload, anonymous, extraQuery).body
        } catch (rejected: YouTubeAuthException) {
            // `onBehalfOfUser` is the one signed field whose accepted shape differs between
            // personal and brand accounts, and Google answers a value it cannot tie to the
            // cookie with HTTP 401. Drop just that field and try once more before evaluating
            // the session, so a shape mismatch never forces a re-login.
            val stripped = stripOnBehalfOfUser(payload)
            if (!anonymous && stripped != payload) {
                try {
                    sendOnce(url, stripped, anonymous, extraQuery).body
                } catch (again: YouTubeAuthException) {
                    if (affectsSession) {
                        retryAfterHeal(url, payload, anonymous, extraQuery, rejected.code, again.code)
                            ?.let { return it }
                    }
                    throw again
                }
            } else {
                if (affectsSession) {
                    retryAfterHeal(url, payload, anonymous, extraQuery, rejected.code, rejected.code)
                        ?.let { return it }
                }
                throw rejected
            }
        }
        // If no visitor id was captured at sign-in, the first response usually carries one.
        if (auth()?.visitorData.isNullOrBlank()) {
            runCatching {
                (json.parseToJsonElement(body) as? JsonObject)
                    ?.get("responseContext")?.asObject()
                    ?.get("visitorData")?.asString()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { liveVisitorData = it }
        }
        return body
    }

    /**
     * The original call is retried exactly once after the verification/refresh attempt. A retry that
     * is still refused is the last condition of the expiry rule, so the session expires here — once.
     * Returns null when the caller should surface its original failure.
     */
    private fun retryAfterHeal(
        url: String,
        payload: String,
        anonymous: Boolean,
        extraQuery: Map<String, String>,
        rejectedCode: Int,
        retriedCode: Int,
    ): String? {
        if (!confirmRefusal(rejectedCode, retriedCode)) return null
        return try {
            AppLog.i(TAG, "Retrying ${endpointName(url)} once after session verification/refresh")
            YouTubeSessionLog.record("auth", "retry ${endpointName(url)} once")
            sendOnce(url, payload, anonymous, extraQuery).body
        } catch (failed: YouTubeAuthException) {
            expireSession("retry after verification/refresh refused (HTTP ${failed.code})")
            throw failed
        }
    }

    /**
     * A session-defining call was refused. One fresh account/account_menu request decides whether
     * the session is really gone:
     *
     * - account_menu answers anything but 401/403 -> transient, keep the session;
     * - account_menu refuses -> one silent WebView refresh, then the original call is retried once
     *   (and only a refused retry expires the session).
     *
     * Returns true when the original call may be retried.
     */
    private fun confirmRefusal(rejectedCode: Int, retriedCode: Int): Boolean {
        if (sessionExpiryNotified) return false
        if (auth()?.signedIn != true) return false
        // One verification at a time; concurrent refusals must not each probe and refresh.
        if (sessionCheckInProgress) return false
        sessionCheckInProgress = true
        try {
            val status = probeAccountMenu()
            if (status != 401 && status != 403) {
                AppLog.w(
                    TAG,
                    "Session check after HTTP $rejectedCode/$retriedCode: account menu answered " +
                        "HTTP $status, session kept",
                )
                YouTubeSessionLog.record("auth", "verify: account_menu HTTP $status -> session kept")
                return false
            }
            AppLog.w(
                TAG,
                "Session check after HTTP $rejectedCode/$retriedCode: account menu also refused " +
                    "(HTTP $status)",
            )
            YouTubeSessionLog.record("auth", "verify: account_menu HTTP $status -> silent refresh")
            val refreshed = runCatching { refreshSession() }.getOrDefault(false)
            if (!refreshed) {
                // The retry below is the rule's last step; expiry happens only if it is refused.
                YouTubeSessionLog.record("auth", "silent refresh failed -> one retry")
                return true
            }
            val after = probeAccountMenu()
            if (after != 401 && after != 403) {
                AppLog.i(TAG, "Silent refresh recovered the session (HTTP $after); retrying the call")
                YouTubeSessionLog.record("auth", "refresh OK; account_menu HTTP $after -> retry")
                return true
            }
            YouTubeSessionLog.record("auth", "refresh reported OK but account_menu HTTP $after")
            return true
        } finally {
            sessionCheckInProgress = false
        }
    }

    /** Marks the session expired exactly once, with the code path that caused it. */
    private fun expireSession(reason: String) {
        if (sessionExpiryNotified) return
        if (auth()?.signedIn != true) return
        sessionExpiryNotified = true
        AppLog.w(TAG, "Session expired: $reason")
        YouTubeSessionLog.record("session", "expired path=$reason")
        onAuthExpired()
    }

    /** `browse`/`account_menu`/`player` … for logs; never carries query values. */
    private fun endpointName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "request" }

    /**
     * One minimal signed account/account_menu request that never retries, never parses and never
     * expires anything. Returns the HTTP status, or 0 when the request did not complete.
     *
     * `onBehalfOfUser` is deliberately omitted: the failed call already retried without it, so
     * probing with it would only re-test a field Google may dislike rather than the cookie itself.
     */
    private fun probeAccountMenu(): Int = runCatching {
        val payload = buildJsonObject { put("context", clientContext(signed = false)) }.toString()
        val request = headerBuilder(ACCOUNT_MENU_URL, emptyMap(), anonymous = false)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        perform(request).code
    }.getOrDefault(0)

    private fun sendOnce(
        url: String,
        payload: String,
        anonymous: Boolean,
        extraQuery: Map<String, String>,
    ): InnerTubeResponse {
        val request = headerBuilder(url, extraQuery, anonymous)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            executeWithRetry(request).also { logSignedCall(request, payload, it) }
        } catch (error: YouTubeRequestException) {
            // The status and body are on the exception, so a refused signed call is still logged.
            logSignedCall(request, payload, InnerTubeResponse(error.code, error.body))
            if (error.code == 401 || error.code == 403) {
                val endpoint = endpointName(url)
                val signed = request.header("Cookie") != null
                val rule = if (signed) "session-defining: verify + silent refresh" else "anonymous: no expiry"
                AppLog.w(TAG, "HTTP ${error.code} $endpoint signed=${if (signed) "yes" else "no"} rule=$rule")
                YouTubeSessionLog.record(
                    "auth",
                    "HTTP ${error.code} $endpoint signed=${if (signed) "yes" else "no"} rule=$rule",
                )
            }
            throw error
        }
    }

    /**
     * One non-secret line per signed call, for the in-app Logs screen: endpoint, status, whether
     * `onBehalfOfUser` was sent, the cookie NAMES present, the top-level keys and Google's error
     * status/message. Cookie values, SAPISID, Authorization, visitorData and dataSyncId never
     * appear here.
     */
    private fun logSignedCall(request: Request, payload: String, response: InnerTubeResponse) {
        if (request.header("Cookie") == null) return
        val cookieNames = request.header("Cookie").orEmpty()
            .split(';')
            .mapNotNull { entry -> entry.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
            .joinToString(",")
        val parsed = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        val topLevel = parsed?.keys?.joinToString(",").orEmpty().take(160)
        val error = parsed?.get("error")?.asObject()
        val errorStatus = error?.get("status")?.asString().orEmpty()
        val errorMessage = error?.get("message")?.asString().orEmpty().take(120)
        val errorSuffix = if (errorStatus.isBlank() && errorMessage.isBlank()) {
            ""
        } else {
            " error=$errorStatus message=$errorMessage"
        }
        AppLog.i(
            TAG,
            "call ${request.url.encodedPath} HTTP ${response.code} signed=yes " +
                "onBehalfOfUser=${if (hasOnBehalfOfUser(payload)) "yes" else "no"} " +
                "cookies=[$cookieNames] keys=[$topLevel]$errorSuffix",
        )
    }

    /** True when the serialized request body carries an `onBehalfOfUser` field. */
    private fun hasOnBehalfOfUser(payload: String): Boolean =
        payload.contains("\"onBehalfOfUser\"")

    /** Removes `context.user.onBehalfOfUser`; unchanged when it is absent or unparsable. */
    private fun stripOnBehalfOfUser(payload: String): String {
        if (!hasOnBehalfOfUser(payload)) return payload
        return runCatching {
            val root = json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching payload
            val context = root["context"] as? JsonObject ?: return@runCatching payload
            val user = context["user"] as? JsonObject ?: return@runCatching payload
            val cleanedUser = JsonObject(user - "onBehalfOfUser")
            val cleanedContext = JsonObject(context + ("user" to cleanedUser))
            JsonObject(root + ("context" to cleanedContext)).toString()
        }.getOrDefault(payload)
    }

    private fun headerBuilder(
        url: String,
        extraQuery: Map<String, String>,
        anonymous: Boolean,
        /** Signs with this session instead of the live one; used to re-check a flagged session. */
        signWith: YouTubeAuth? = null,
    ): Request.Builder {
        val builder = Request.Builder()
            .url(appendQuery(url, extraQuery))
            .header("User-Agent", userAgent(anonymous, signWith))
            .header("Accept-Language", acceptLanguage())
            .header("X-Youtube-Client-Name", CLIENT_ID)
            .header("X-Youtube-Client-Version", clientVersion())
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
        val visitor = auth()?.visitorData?.takeIf { it.isNotBlank() } ?: liveVisitorData
        visitor?.let { builder.header("X-Goog-Visitor-Id", it) }
        if (!anonymous) sign(builder, signWith ?: auth()?.takeIf { it.signedIn })
        return builder
    }

    /**
     * Signed calls reuse the exact WebView user agent that created the session, because Google can
     * tie a session to the browser that signed in. Anonymous calls keep the desktop Chrome UA the
     * public client uses, and a session saved before this field existed also falls back to it.
     */
    private fun userAgent(anonymous: Boolean, signWith: YouTubeAuth? = null): String {
        if (anonymous) return USER_AGENT
        val session = signWith ?: auth()?.takeIf { it.signedIn }
        return session?.userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT
    }

    /** Adds the signed-in headers for [session]. No-op for anonymous requests and null sessions. */
    private fun sign(builder: Request.Builder, session: YouTubeAuth?) {
        val active = session ?: return
        val sapisid = active.sapisid ?: return
        val timestamp = System.currentTimeMillis() / 1000
        val hash = sapisidHash(sapisid, timestamp)
        // SESSION_INDEX is a zero-based account slot; anything else would name a jar entry
        // that does not exist.
        val authUser = active.authUser.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: "0"
        builder.header("Cookie", active.cookie)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$hash")
            .header("X-Goog-AuthUser", authUser)
        // X-Goog-PageId is the brand channel only; a personal account omits it.
        active.pageId?.takeIf { it.isNotBlank() }?.let { builder.header("X-Goog-PageId", it) }
    }

    /**
     * Performs one request. Transport is injectable so the expiry rules can be unit-tested with
     * no network; production goes through OkHttp exactly as before.
     */
    private fun perform(request: Request): InnerTubeResponse {
        transport?.let { return it.execute(request) }
        http.newCall(request).execute().use { response ->
            return InnerTubeResponse(
                response.code,
                response.body?.string().orEmpty(),
                response.headers.values("Set-Cookie"),
            )
        }
    }

    /**
     * Persists any session cookie YouTube rotated on this reply. Google reissues `__Secure-1PSIDTS`
     * and the `*SIDCC` cookies on its own schedule; a client that keeps replaying the sign-in
     * snapshot is eventually answered as a stranger, which looks like the app forgetting the login.
     * Cookie names only are logged.
     */
    private fun persistRotatedCookies(request: Request, response: InnerTubeResponse) {
        if (response.setCookies.isEmpty()) return
        val existing = request.header("Cookie") ?: return
        val merged = mergeCookieHeader(existing, response.setCookies) ?: return
        AppLog.i(TAG, "Session cookies refreshed: ${response.setCookies.mapNotNull { cookieName(it) }.joinToString(",")}")
        runCatching { onSessionCookies(merged) }
    }

    /**
     * Retries transport failures at most twice with doubling backoff; never retries 401/403.
     * Expiry is decided by [confirmRefusal], which knows whether the call defines the session.
     */
    private fun executeWithRetry(request: Request): InnerTubeResponse {
        var backoff = RETRY_BASE_MS
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = perform(request)
                // Persist rotated cookies before deciding anything: even a refusal may carry the
                // replacement for a stale rotating cookie.
                persistRotatedCookies(request, response)
                if (response.code == 401 || response.code == 403) {
                    // Only a real signed call can expire a session; an anonymous 401 is not one.
                    if (request.header("Cookie") != null && auth()?.signedIn == true) {
                        throw YouTubeAuthException(
                            "YouTube Music rejected the stored session",
                            response.code,
                            response.body,
                        )
                    }
                    throw YouTubeRequestException(
                        "YouTube request was refused (HTTP ${response.code})",
                        response.code,
                        response.body,
                    )
                }
                if (response.code !in 200..299) {
                    throw YouTubeRequestException(
                        "YouTube request failed (HTTP ${response.code})",
                        response.code,
                        response.body,
                    )
                }
                // A signed call that works proves the session is healthy again.
                if (request.header("Cookie") != null) sessionExpiryNotified = false
                return response
            } catch (error: java.io.IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw error
                AppLog.d(TAG, "YouTube call failed, retrying in ${backoff}ms: ${error.message}")
                try {
                    Thread.sleep(backoff)
                } catch (cancel: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                backoff *= 2
            }
        }
        throw YouTubeRequestException("YouTube request failed")
    }

    private fun appendQuery(url: String, extra: Map<String, String>): String {
        val params = linkedMapOf("prettyPrint" to "false", "hl" to deviceLanguage())
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        return if (url.contains('?')) "$url&$query" else "$url?$query"
    }

    private fun clientContext(
        gl: String? = deviceCountry(),
        hl: String = deviceLanguage(),
        signed: Boolean = true,
    ): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", CLIENT_NAME)
            put("clientVersion", clientVersion())
            put("hl", hl)
            gl?.takeIf { it.isNotBlank() }?.let { put("gl", it) }
            val visitor = auth()?.visitorData?.takeIf { it.isNotBlank() } ?: liveVisitorData
            visitor?.let { put("visitorData", it) }
        }
        putJsonObject("user") {
            put("lockedSafetyMode", false)
            // onBehalfOfUser may only ever carry the active page identity read from a page that
            // reported LOGGED_IN. A value that cannot be tied to the cookie gets HTTP 401, so it
            // is omitted when unsure and retried without it if Google still refuses.
            if (signed) {
                val session = auth()?.takeIf { it.signedIn }
                val behalf = normalizeDataSyncId(session?.dataSyncId)
                if (behalf != null) put("onBehalfOfUser", behalf)
            }
        }
        putJsonObject("request") { put("useSsl", true) }
    }

    private fun acceptLanguage(): String {
        val locale = java.util.Locale.getDefault()
        val language = locale.language.ifBlank { DEFAULT_HL }
        return if (locale.country.isNotBlank()) "$language-${locale.country},$language;q=0.9" else language
    }

    /**
     * Parses a `FEmusic_home` response. Internal so a unit test can pin the real response shape;
     * the counts are logged because an HTTP 200 that yields no shelves is otherwise invisible.
     */
    internal fun parseHomeShelves(root: JsonObject): List<YtShelf> {
        val shelves = collectShelvesDeep(root)
        if (shelves.isEmpty()) {
            val contents = root["contents"]?.asObject()?.keys?.joinToString(",").orEmpty()
            AppLog.w(TAG, "Home feed had no usable shelves (contents=$contents)")
        } else {
            AppLog.i(TAG, "Home feed parsed ${shelves.size} shelves, ${shelves.sumOf { it.items.size }} cards")
        }
        return shelves
    }

    private fun shelfTitle(renderer: JsonObject): String {
        textOf(renderer["title"])?.takeIf { it.isNotBlank() }?.let { return it }
        val header = renderer["header"]?.asObject()
        for (key in SHELF_HEADER_KEYS) {
            header?.get(key)?.asObject()?.get("title")
                ?.let { textOf(it)?.takeIf(String::isNotBlank)?.let { title -> return title } }
        }
        return "YouTube Music"
    }

    /** Every shelf renderer on a browse page, in page order, skipping empty ones. */
    private fun collectShelvesDeep(root: JsonObject): List<YtShelf> {
        val out = ArrayList<YtShelf>()
        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    val renderer = SHELF_RENDERER_KEYS.firstNotNullOfOrNull { key -> element[key]?.asObject() }
                    if (renderer != null) {
                        val items = ArrayList<YtCard>()
                        collectCardsDeep(renderer["contents"], items)
                        if (items.isNotEmpty()) {
                            out += YtShelf(
                                title = shelfTitle(renderer),
                                items = items.distinctBy { it.videoId ?: it.browseId ?: it.title },
                                continuation = firstContinuation(renderer),
                            )
                        }
                        return
                    }
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out
    }

    private fun collectCardsDeep(element: JsonElement?, out: MutableList<YtCard>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { parseCard(it)?.let(out::add) }
                element["musicResponsiveListItemRenderer"]?.asObject()?.let { parseCard(it)?.let(out::add) }
                element.values.forEach { collectCardsDeep(it, out) }
            }
            is JsonArray -> element.forEach { collectCardsDeep(it, out) }
            else -> Unit
        }
    }

    private fun collectSongsDeep(element: JsonElement?, out: MutableMap<String, YtSong>) {
        when (element) {
            is JsonObject -> {
                element["musicResponsiveListItemRenderer"]?.asObject()?.let { renderer ->
                    parseRenderer(renderer)?.let { out.putIfAbsent(it.videoId, it) }
                }
                element["playlistPanelVideoRenderer"]?.asObject()?.let { renderer ->
                    parsePanelSong(renderer)?.let { out.putIfAbsent(it.videoId, it) }
                }
                element.values.forEach { collectSongsDeep(it, out) }
            }
            is JsonArray -> element.forEach { collectSongsDeep(it, out) }
            else -> Unit
        }
    }

    /**
     * Playlists on library pages. Accepts both grid cards (`musicTwoRowItemRenderer`) and list
     * rows (`musicResponsiveListItemRenderer`) and keeps every tile whose destination is a
     * playlist (`VL...`) or the liked-songs auto playlist (`LM`). The "New playlist" action tile
     * has no playlist destination and is skipped naturally.
     */
    private fun collectPlaylistsDeep(element: JsonElement?, out: MutableMap<String, YtPlaylist>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { readPlaylistTile(it, out) }
                element["musicResponsiveListItemRenderer"]?.asObject()?.let { readPlaylistTile(it, out) }
                element.values.forEach { collectPlaylistsDeep(it, out) }
            }
            is JsonArray -> element.forEach { collectPlaylistsDeep(it, out) }
            else -> Unit
        }
    }

    /** One playlist tile, from either renderer shape. Ids are stored without the `VL` prefix. */
    private fun readPlaylistTile(renderer: JsonObject, out: MutableMap<String, YtPlaylist>) {
        val browseId = browseIdOf(renderer) ?: return
        val id = when {
            // "LM" is the liked-songs auto playlist; VLLM is the same list addressed by browse id.
            browseId == "LM" || browseId == "VLLM" -> "LM"
            browseId.startsWith("VL") -> browseId.removePrefix("VL")
            else -> return
        }
        if (id.isBlank() || out.containsKey(id)) return
        val title = textOf(renderer["title"])?.takeIf { it.isNotBlank() }
            ?: renderer.flexText(0)?.takeIf { it.isNotBlank() }
            ?: return
        if (title.equals("New playlist", ignoreCase = true)) return
        val subtitle = textOf(renderer["subtitle"]).orEmpty()
            .ifBlank { renderer.flexText(1).orEmpty() }
        val tokens = subtitle.split('•').map { it.trim() }.filter { it.isNotEmpty() }
        val count = tokens.firstNotNullOfOrNull { token ->
            TRACK_COUNT_PATTERN.find(token)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 0
        out[id] = YtPlaylist(
            id = id,
            title = title,
            thumbnailUrl = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                ?.get("url")?.asString()?.let(::normalizeArtworkUrl),
            trackCount = count,
            owner = tokens.firstOrNull { !TRACK_COUNT_PATTERN.containsMatchIn(it) }.orEmpty(),
        )
    }

    /** Parses one playlist page without touching the network; used by the paging loop and tests. */
    internal fun parsePlaylistPage(root: JsonObject): List<YtPlaylist> {
        val out = LinkedHashMap<String, YtPlaylist>()
        collectPlaylistsDeep(root, out)
        return out.values.toList()
    }

    /** Saved albums (`MPREb…`) on the library albums page. */
    private fun collectAlbumsDeep(element: JsonElement?, out: MutableMap<String, YtAlbum>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { renderer ->
                    val id = browseIdOf(renderer)
                    val title = textOf(renderer["title"])
                    if (id != null && id.startsWith("MPREb") && !title.isNullOrBlank() && !out.containsKey(id)) {
                        val subtitle = textOf(renderer["subtitle"]).orEmpty()
                        val tokens = subtitle.split('•').map { it.trim() }.filter { it.isNotEmpty() }
                        val releaseType = tokens.firstOrNull { it in RELEASE_TYPES }.orEmpty()
                        val year = tokens.lastOrNull { YEAR_PATTERN.matches(it) }.orEmpty()
                        val artist = tokens.firstOrNull {
                            it !in RELEASE_TYPES && !YEAR_PATTERN.matches(it)
                        }.orEmpty()
                        out[id] = YtAlbum(
                            browseId = id,
                            title = title,
                            artist = artist,
                            year = year,
                            thumbnailUrl = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                                ?.get("url")?.asString()?.let(::normalizeArtworkUrl),
                            releaseType = releaseType,
                        )
                    }
                }
                element.values.forEach { collectAlbumsDeep(it, out) }
            }
            is JsonArray -> element.forEach { collectAlbumsDeep(it, out) }
            else -> Unit
        }
    }

    /** Library/subscribed artists (`UC…` channels) on the library artists page. */
    private fun collectArtistsDeep(element: JsonElement?, out: MutableMap<String, YtArtist>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { renderer ->
                    val id = browseIdOf(renderer)
                    val title = textOf(renderer["title"])
                    if (id != null && id.startsWith("UC") && !title.isNullOrBlank() && !out.containsKey(id)) {
                        out[id] = YtArtist(
                            browseId = id,
                            name = title,
                            thumbnailUrl = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                                ?.get("url")?.asString()?.let(::normalizeArtworkUrl),
                            subscribers = textOf(renderer["subtitle"]).orEmpty(),
                        )
                    }
                }
                element.values.forEach { collectArtistsDeep(it, out) }
            }
            is JsonArray -> element.forEach { collectArtistsDeep(it, out) }
            else -> Unit
        }
    }

    /** The first `continuations[].nextContinuationData.continuation` anywhere in the tree. */
    private fun firstContinuation(element: JsonElement?): String? {
        when (element) {
            is JsonObject -> {
                continuationIn(element)?.let { return it }
                for (value in element.values) {
                    firstContinuation(value)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in element) {
                    firstContinuation(value)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    /** A card's destination id, from its navigation endpoint or its title-link runs. */
    private fun browseIdOf(renderer: JsonObject): String? =
        renderer["navigationEndpoint"]?.asObject()
            ?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
            ?.takeIf { it.isNotBlank() }
            ?: renderer["flexColumns"]?.asArray()?.asSequence()
                ?.mapNotNull { column ->
                    column.asObject()
                        ?.get("musicResponsiveListItemFlexColumnRenderer")?.asObject()
                        ?.get("text")?.asObject()?.get("runs")?.asArray()
                }
                ?.flatMap { it.asSequence() }
                ?.mapNotNull { run ->
                    run.asObject()?.get("navigationEndpoint")?.asObject()
                        ?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
                }
                ?.firstOrNull { it.isNotBlank() }

    /**
     * One card. [entry] may be the renderer wrapper (`{"musicTwoRowItemRenderer": …}`) or the
     * renderer itself: the search walk hands over wrappers, while [collectCards] and
     * [collectCardsDeep] already unwrap each row before calling in. Both shapes are accepted.
     */
    private fun parseCard(entry: JsonObject): YtCard? {
        entry["musicTwoRowItemRenderer"]?.asObject()?.let { return parseTwoRowCard(it) }
        entry["musicResponsiveListItemRenderer"]?.asObject()?.let { return parseResponsiveCard(it) }
        if (entry["flexColumns"] != null) return parseResponsiveCard(entry)
        return parseTwoRowCard(entry)
    }

    private fun parseTwoRowCard(renderer: JsonObject): YtCard? {
        val title = textOf(renderer["title"]) ?: return null
        val subtitle = textOf(renderer["subtitle"]).orEmpty()
        val thumb = renderer.thumbnailArray()?.lastOrNull()?.asObject()
            ?.get("url")?.asString()?.let(::normalizeArtworkUrl)
        val navigation = renderer["navigationEndpoint"]?.asObject()
        val videoId = navigation?.get("watchEndpoint")?.asObject()?.get("videoId")?.asString()
        val browseId = navigation?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
        return YtCard(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumb,
            videoId = videoId,
            browseId = browseId,
            itemType = inferItemType(videoId, browseId, subtitle),
            musicVideoType = findString(renderer, MUSIC_VIDEO_TYPE_KEYS),
        )
    }

    private fun parseResponsiveCard(renderer: JsonObject): YtCard? {
        parseRenderer(renderer)?.let { song ->
            return YtCard(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
                durationMs = song.durationMs,
                itemType = if (song.isVideo) YtItemType.VIDEO.wire else YtItemType.SONG.wire,
                explicit = song.explicit,
                musicVideoType = song.musicVideoType,
            )
        }
        // No video id: this is an artist / album / playlist row. Keep its browse id so the
        // Home "Popular Artists" row can open the artist page. The destination can sit on the
        // renderer itself or on one of its title runs, so both are read.
        val title = renderer.flexText(0)?.takeIf { it.isNotBlank() }
            ?: textOf(renderer["title"])?.takeIf { it.isNotBlank() }
            ?: return null
        val subtitle = renderer.flexText(1).orEmpty()
            .ifBlank { textOf(renderer["subtitle"]).orEmpty() }
        val browseId = browseIdOf(renderer) ?: return null
        val thumb = renderer.thumbnailArray()?.lastOrNull()?.asObject()
            ?.get("url")?.asString()?.let(::normalizeArtworkUrl)
        return YtCard(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumb,
            videoId = null,
            browseId = browseId,
            itemType = inferItemType(null, browseId, subtitle),
        )
    }

    private fun inferItemType(videoId: String?, browseId: String?, subtitle: String): String = when {
        browseId == null -> if (videoId != null) YtItemType.SONG.wire else YtItemType.UNKNOWN.wire
        browseId.startsWith("VL") || browseId.startsWith("RD") -> YtItemType.PLAYLIST.wire
        browseId.startsWith("MPREb") -> YtItemType.ALBUM.wire
        browseId.startsWith("UC") -> YtItemType.ARTIST.wire
        browseId.startsWith("FEmusic_moods") || browseId.startsWith("FEmusic_genres") -> YtItemType.MOOD.wire
        ALBUM_HINT.containsMatchIn(subtitle.lowercase()) -> YtItemType.ALBUM.wire
        videoId != null -> YtItemType.SONG.wire
        else -> YtItemType.UNKNOWN.wire
    }

    private fun collectCards(element: JsonElement?, out: MutableList<YtCard>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { parseCard(it)?.let(out::add) }
                element["musicResponsiveListItemRenderer"]?.asObject()?.let { parseCard(it)?.let(out::add) }
                element.values.forEach { collectCards(it, out) }
            }
            is JsonArray -> element.forEach { collectCards(it, out) }
            else -> Unit
        }
    }

    private fun collectSongs(element: JsonElement?, out: MutableList<YtSong>) {
        when (element) {
            is JsonObject -> {
                element["musicResponsiveListItemRenderer"]?.asObject()?.let { parseRenderer(it)?.let(out::add) }
                element["playlistPanelVideoRenderer"]?.asObject()?.let { parsePanelSong(it)?.let(out::add) }
                element.values.forEach { collectSongs(it, out) }
            }
            is JsonArray -> element.forEach { collectSongs(it, out) }
            else -> Unit
        }
    }

    private fun findString(element: JsonElement?, keys: Set<String>): String? = when (element) {
        is JsonObject -> {
            for (key in keys) {
                // `musicVideoType` is a bare JSON string, while titles use `text`/`runs` objects.
                (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { return it }
                textOf(element[key])?.takeIf { it.isNotBlank() }?.let { return it }
            }
            element.values.firstNotNullOfOrNull { findString(it, keys) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, keys) }
        else -> null
    }

    /** Distinct `*Renderer` names on a page, for the Home diagnostics line (names only, never text). */
    private fun rendererNamesIn(root: JsonElement?, limit: Int = 12): List<String> {
        val names = LinkedHashSet<String>()
        fun visit(element: JsonElement?) {
            if (names.size >= limit) return
            when (element) {
                is JsonObject -> for ((key, value) in element) {
                    if (names.size >= limit) return
                    if (key.endsWith("Renderer")) names += key
                    visit(value)
                }
                is JsonArray -> for (value in element) visit(value)
                else -> Unit
            }
        }
        visit(root)
        return names.toList()
    }

    /** The first object carried under [key] anywhere in the tree (e.g. an account header). */
    private fun findRenderer(element: JsonElement?, key: String): JsonObject? = when (element) {
        is JsonObject -> element[key]?.asObject()
            ?: element.values.firstNotNullOfOrNull { findRenderer(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findRenderer(it, key) }
        else -> null
    }

    private fun findAvatar(element: JsonElement?): String? = when (element) {
        is JsonObject -> {
            element["accountPhoto"]?.asObject()?.get("thumbnails")?.asArray()
                ?.lastOrNull()?.asObject()?.get("url")?.asString()
                ?.let(::normalizeArtworkUrl)
                ?: element.values.firstNotNullOfOrNull { findAvatar(it) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findAvatar(it) }
        else -> null
    }

    private fun textOf(element: JsonElement?): String? {
        val obj = element?.asObject() ?: return null
        obj["simpleText"]?.asString()?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj["runs"]?.asArray() ?: return null
        return runs.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }.trim()
    }

    private fun parseSongs(root: JsonObject): List<YtSong> {
        val sections = root["contents"]
            ?.asObject()?.get("tabbedSearchResultsRenderer")
            ?.asObject()?.get("tabs")
            ?.asArray()?.firstOrNull()
            ?.asObject()?.get("tabRenderer")
            ?.asObject()?.get("content")
            ?.asObject()?.get("sectionListRenderer")
            ?.asObject()?.get("contents")
            ?.asArray() ?: return emptyList()

        val songs = ArrayList<YtSong>()
        for (section in sections) {
            val shelf = section.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
            val items = shelf["contents"]?.asArray() ?: continue
            for (entry in items) {
                val renderer = entry.asObject()
                    ?.get("musicResponsiveListItemRenderer")?.asObject() ?: continue
                parseRenderer(renderer)?.let(songs::add)
            }
        }
        val distinct = songs.distinctBy { it.videoId }
        val tally = ThumbnailTally()
        for (song in distinct) {
            tally.add("Search", song.videoId, song.thumbnailUrl)?.let { AppLog.w(TAG, it) }
        }
        AppLog.i(TAG, requestSummary("Search", tally))
        return distinct
    }

    /** Cards from the search result sections (songs + artist/album/playlist entities). */
    private fun parseSearchCards(root: JsonObject): List<YtCard> {
        val sections = root["contents"]
            ?.asObject()?.get("tabbedSearchResultsRenderer")
            ?.asObject()?.get("tabs")
            ?.asArray()?.firstOrNull()
            ?.asObject()?.get("tabRenderer")
            ?.asObject()?.get("content")
            ?.asObject()?.get("sectionListRenderer")
            ?.asObject()?.get("contents")
            ?.asArray() ?: return emptyList()
        val out = ArrayList<YtCard>()
        for (section in sections) collectCards(section, out)
        return out.distinctBy { it.videoId ?: it.browseId ?: it.title }
    }

    private fun parseRadioPage(body: String): RadioPage {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return RadioPage(emptyList(), null)
        val panel = findRadioPanel(root)
        val songs = ArrayList<YtSong>()
        panel?.get("contents")?.asArray()?.forEach { entry ->
            entry.asObject()?.get("playlistPanelVideoRenderer")?.asObject()
                ?.let(::parsePanelSong)?.let(songs::add)
        }
        val continuation = continuationIn(panel) ?: continuationIn(root)
        logRadioSongs(songs)
        return RadioPage(songs.distinctBy { it.videoId }, continuation)
    }

    private fun parseRadioContinuation(body: String): RadioPage {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return RadioPage(emptyList(), null)
        val contents = root["continuationContents"]?.asObject()
        val container = listOf(
            "playlistPanelContinuation",
            "musicPlaylistShelfContinuation",
            "sectionListContinuation",
        ).firstNotNullOfOrNull { contents?.get(it)?.asObject() }
        val songs = ArrayList<YtSong>()
        container?.get("contents")?.asArray()?.forEach { entry ->
            val renderer = entry.asObject() ?: return@forEach
            renderer["playlistPanelVideoRenderer"]?.asObject()?.let(::parsePanelSong)?.let(songs::add)
            renderer["musicResponsiveListItemRenderer"]?.asObject()?.let(::parseRenderer)?.let(songs::add)
        }
        val continuation = continuationIn(container) ?: continuationIn(contents) ?: continuationIn(root)
        logRadioSongs(songs)
        return RadioPage(songs.distinctBy { it.videoId }, continuation)
    }

    private fun findRadioPanel(root: JsonObject): JsonObject? {
        val tabs = root["contents"]
            ?.asObject()?.get("singleColumnMusicWatchNextResultsRenderer")
            ?.asObject()?.get("tabbedRenderer")
            ?.asObject()?.get("watchNextTabbedResultsRenderer")
            ?.asObject()?.get("tabs")
            ?.asArray() ?: return null
        return tabs.asSequence()
            .mapNotNull { tab ->
                val tabRenderer = tab.asObject()?.get("tabRenderer")?.asObject() ?: return@mapNotNull null
                val title = tabRenderer.textValue("title")
                tabRenderer["content"]?.asObject()
                    ?.get("musicQueueRenderer")?.asObject()
                    ?.get("content")?.asObject()
                    ?.get("playlistPanelRenderer")?.asObject()
                    ?.takeIf { title == "Up next" || title == null }
            }
            .firstOrNull()
    }

    private fun parsePanelSong(renderer: JsonObject): YtSong? {
        val videoId = renderer["videoId"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val title = renderer.textValue("title")?.takeIf { it.isNotBlank() } ?: return null
        val artist = renderer.textValue("longBylineText")
            ?: renderer.textValue("shortBylineText")
            ?: "Unknown artist"
        val rawUrl = renderer.thumbnailArray()?.lastOrNull()?.asObject()?.get("url")?.asString()
        val thumbUrl = rawUrl?.takeIf { it.isNotBlank() }?.let(::normalizeArtworkUrl)
            ?: fallbackThumbnailUrl(videoId)
        return YtSong(
            videoId = videoId,
            title = title,
            artist = artist,
            album = "",
            durationMs = parseDurationMs(renderer.textValue("lengthText")),
            thumbnailUrl = thumbUrl,
            musicVideoType = findString(renderer, MUSIC_VIDEO_TYPE_KEYS),
        )
    }

    private fun logRadioSongs(songs: List<YtSong>) {
        val tally = ThumbnailTally()
        for (song in songs.distinctBy { it.videoId }) {
            tally.add("Radio", song.videoId, song.thumbnailUrl)?.let { AppLog.w(TAG, it) }
        }
        AppLog.i(TAG, requestSummary("Radio", tally, noun = "parsed"))
    }

    /** Radio continuation tokens live in `continuations[].nextRadioContinuationData.continuation`. */
    private fun continuationIn(owner: JsonObject?): String? {
        val entries = owner?.get("continuations")?.asArray() ?: return null
        for (entry in entries) {
            val obj = entry.asObject() ?: continue
            for (key in RADIO_CONTINUATION_KEYS) {
                obj[key]?.asObject()?.get("continuation")?.asString()
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun parseSuggestions(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val sections = root["contents"]?.asArray() ?: return emptyList()
        val values = ArrayList<String>()
        for (section in sections) {
            val entries = section.asObject()
                ?.get("searchSuggestionsSectionRenderer")?.asObject()
                ?.get("contents")?.asArray() ?: continue
            for (entry in entries) {
                val runs = entry.asObject()
                    ?.get("searchSuggestionRenderer")?.asObject()
                    ?.get("suggestion")?.asObject()
                    ?.get("runs")?.asArray() ?: continue
                val text = runs.joinToString("") {
                    it.asObject()?.get("text")?.asString().orEmpty()
                }.trim()
                if (text.isNotEmpty()) values += text
            }
        }
        return values.distinct().take(MAX_SUGGESTIONS)
    }

    private fun parseRenderer(renderer: JsonObject, shelfTitle: String = ""): YtSong? {
        val videoId = renderer["playlistItemData"]?.asObject()?.get("videoId")?.asString()
            ?: findVideoId(renderer["overlay"]) ?: return null
        // Search/library rows keep the title in flex column 0; a few layouts emit `title` instead.
        val title = renderer.flexText(0)?.takeIf { it.isNotBlank() }
            ?: textOf(renderer["title"])?.takeIf { it.isNotBlank() }
            ?: return null
        val subtitle = renderer.flexText(1).orEmpty()
            .ifBlank { textOf(renderer["subtitle"]).orEmpty() }

        val tokens = subtitle.split('•').map { it.trim() }.filter { it.isNotEmpty() }
        val durationToken = tokens.lastOrNull()?.takeIf { DURATION_PATTERN.matches(it) }
        val metadata = if (durationToken != null) tokens.dropLast(1) else tokens
        val artist = metadata.firstOrNull().orEmpty().ifBlank { "Unknown artist" }
        val album = if (metadata.size > 1) metadata.drop(1).joinToString(" • ") else ""

        val thumbUrl = parseSearchThumbnail(renderer, videoId)
        val browseIds = browseIdsOf(renderer)

        return YtSong(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = parseDurationMs(durationToken),
            thumbnailUrl = thumbUrl,
            albumId = browseIds.firstOrNull { it.startsWith("MPREb") },
            artistId = browseIds.firstOrNull { it.startsWith("UC") },
            explicit = hasExplicitBadge(renderer),
            isVideo = isVideoItem(renderer),
            musicVideoType = findString(renderer, MUSIC_VIDEO_TYPE_KEYS),
            shelfTitle = shelfTitle,
        )
    }

    /** Browse ids referenced by a row's metadata runs (artist `UC…`, album `MPREb…`). */
    private fun browseIdsOf(renderer: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        fun fromRuns(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    element["navigationEndpoint"]?.asObject()?.get("browseEndpoint")?.asObject()
                        ?.get("browseId")?.asString()?.takeIf { it.isNotBlank() }?.let(out::add)
                    element.values.forEach(::fromRuns)
                }
                is JsonArray -> element.forEach(::fromRuns)
                else -> Unit
            }
        }
        fromRuns(renderer["flexColumns"])
        fromRuns(renderer["navigationEndpoint"])
        return out.toList()
    }

    private fun hasExplicitBadge(renderer: JsonObject): Boolean {
        val badges = renderer["badges"]?.asArray() ?: return false
        return badges.any { badge ->
            val text = badge.asObject()?.get("musicInlineBadgeRenderer")?.asObject()
                ?.get("icon")?.asObject()?.get("iconType")?.asString()
            text?.contains("EXPLICIT", ignoreCase = true) == true
        }
    }

    /** `watchEndpointMusicConfig.musicVideoType`; anything but ATV is a music video/UGC upload. */
    private fun isVideoItem(renderer: JsonObject): Boolean {
        val type = findString(renderer, MUSIC_VIDEO_TYPE_KEYS) ?: return false
        return !type.equals("MUSIC_VIDEO_TYPE_ATV", ignoreCase = true)
    }

    /** Reads one flex column and joins its text runs (titles can be split into several runs). */
    private fun JsonObject.flexText(index: Int): String? {
        val column = this["flexColumns"]?.asArray()?.getOrNull(index)
            ?.asObject()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.asObject() ?: return null
        val runs = column["text"]?.asObject()?.get("runs")?.asArray() ?: return null
        return runs.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }.trim()
    }

    private fun parseSearchThumbnail(renderer: JsonObject, videoId: String): String {
        val thumbnails = renderer["thumbnail"]?.asObject()
            ?.get("musicThumbnailRenderer")?.asObject()
            ?.get("thumbnail")?.asObject()
            ?.get("thumbnails")?.asArray()
        val rawUrl = thumbnails?.lastOrNull()?.asObject()?.get("url")?.asString()
        return rawUrl?.takeIf { it.isNotBlank() }?.let(::normalizeArtworkUrl)
            ?: fallbackThumbnailUrl(videoId)
    }

    /** Radio panels expose `thumbnail.thumbnails` directly or wrapped in `musicThumbnailRenderer`. */
    private fun JsonObject.thumbnailArray(): JsonArray? =
        this["thumbnail"]?.asObject()?.get("thumbnails")?.asArray()
            ?: this["thumbnail"]?.asObject()?.get("musicThumbnailRenderer")?.asObject()
                ?.get("thumbnail")?.asObject()?.get("thumbnails")?.asArray()
            // Carousel playlist/album cards wrap it one level deeper: thumbnailRenderer.*.
            ?: this["thumbnailRenderer"]?.asObject()?.get("musicThumbnailRenderer")?.asObject()
                ?.get("thumbnail")?.asObject()?.get("thumbnails")?.asArray()

    /** `{simpleText}` or `{runs:[{text}]}` -> plain text. */
    private fun JsonObject.textValue(key: String): String? {
        val element = this[key]?.asObject() ?: return null
        element["simpleText"]?.asString()?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = element["runs"]?.asArray() ?: return null
        return runs.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }.trim()
    }

    private fun findVideoId(element: JsonElement?): String? = when (element) {
        is JsonObject -> element["videoId"]?.asString()
            ?: element.values.firstNotNullOfOrNull { findVideoId(it) }
        is JsonArray -> element.firstNotNullOfOrNull { findVideoId(it) }
        else -> null
    }

    private fun parseDurationMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1_000L
            3 -> ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1_000L
            else -> 0L
        }
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content

    private companion object {
        const val TAG = "YouTube"
        const val BASE = "https://music.youtube.com/youtubei/v1"
        const val SEARCH_URL = "$BASE/search"
        const val NEXT_URL = "$BASE/next"
        const val SUGGESTIONS_URL = "$BASE/music/get_search_suggestions"
        const val BROWSE_URL = "$BASE/browse"
        const val ACCOUNT_MENU_URL = "$BASE/account/account_menu"
        const val LIKE_URL = "$BASE/like/like"
        const val UNLIKE_URL = "$BASE/like/removelike"
        const val PLAYLIST_CREATE_URL = "$BASE/playlist/create"
        const val PLAYLIST_DELETE_URL = "$BASE/playlist/delete"
        const val BROWSE_EDIT_PLAYLIST_URL = "$BASE/browse/edit_playlist"
        const val PLAYER_URL = "$BASE/player"
        const val ORIGIN = "https://music.youtube.com"
        const val WARM_UP_URL = "https://music.youtube.com/generate_204"
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_ID = "67"
        /** Fallback only; the live version read at sign-in takes precedence. */
        const val CLIENT_VERSION = "1.20250101.00.00"
        const val USER_AGENT = YOUTUBE_USER_AGENT
        const val DEFAULT_GL = "US"
        const val DEFAULT_HL = "en"
        const val SONGS_ONLY_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val MAX_SUGGESTIONS = 8
        const val HISTORY_MIN_SECONDS = 10L
        const val RETRY_BASE_MS = 500L
        const val MAX_ATTEMPTS = 3

        /** Renderer keys that denote one titled shelf on a browse page. */
        val SHELF_RENDERER_KEYS = listOf(
            "musicCarouselShelfRenderer",
            "musicImmersiveCarouselShelfRenderer",
            "musicShelfRenderer",
            "musicPlaylistShelfRenderer",
            "musicGridRenderer",
        )

        /** Header renderers that carry a shelf title. */
        val SHELF_HEADER_KEYS = listOf(
            "musicCarouselShelfBasicHeaderRenderer",
            "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer",
            "musicDetailHeaderRenderer",
        )

        /** Subtitle fragments that mark an album card even without an `MPREb` browse id. */
        val ALBUM_HINT = Regex("\\b(album|single|ep)\\b", RegexOption.IGNORE_CASE)
        val RELEASE_TYPES = setOf("Album", "Single", "EP")
        val TRACK_COUNT_PATTERN = Regex("(\\d+)\\s+(?:song|track|video)s?", RegexOption.IGNORE_CASE)
        val YEAR_PATTERN = Regex("^\\d{4}$")
        val MUSIC_VIDEO_TYPE_KEYS = setOf("musicVideoType")

        /** Names from the prompt; matched by title where YouTube uses one. */
        const val BROWSE_HOME = "FEmusic_home"
        const val BROWSE_HISTORY = "FEmusic_history"
        const val BROWSE_LIKED_SONGS = "VLLM"
        const val BROWSE_LIBRARY_SONGS = "FEmusic_liked_videos"
        const val BROWSE_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val BROWSE_LIBRARY_ALBUMS = "FEmusic_liked_albums"
        const val BROWSE_LIBRARY_ARTISTS = "FEmusic_library_corpus_track_artists"
        const val BROWSE_SUBSCRIPTIONS = "FEmusic_library_corpus_artists"
        const val BROWSE_NEW_RELEASES = "FEmusic_new_releases"
        const val BROWSE_EXPLORE = "FEmusic_explore"
        const val BROWSE_MOODS = "FEmusic_moods_and_genres"
        const val BROWSE_MIXED = "FEmusic_mixed_for_you"

        /** Cap per list so a runaway continuation can never exhaust memory. */
        const val PAGE_LIMIT = 500

        /** Playlist detail pages stop here; enough for long playlists, finite enough to stay snappy. */
        const val PLAYLIST_PAGE_LIMIT = 1000

        /** Home follows page continuations until this many shelves are collected. */
        const val MAX_HOME_SHELVES = 10
        const val MAX_HOME_PAGES = 8

        /** Playlist listing caps: enough for any real library, finite so a bad token cannot loop. */
        const val MAX_PLAYLISTS = 200
        const val MAX_PLAYLIST_PAGES = 40

        /** Fallback browse when `FEmusic_liked_playlists` yields nothing. */
        const val LIBRARY_LANDING = "FEmusic_library_landing"

        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DURATION_PATTERN = Regex("^\\d{1,3}(?::\\d{1,2}){1,2}$")
        val RADIO_CONTINUATION_KEYS = listOf(
            "nextRadioContinuationData",
            "nextContinuationData",
            "reloadContinuationData",
        )
        val ACCOUNT_NAME_KEYS = setOf("accountName", "displayName", "givenName")
        /** Shown when Google returns only the @handle for an account. */
        val ACCOUNT_HANDLE_KEYS = setOf("channelHandle")
        val MOOD_PATTERN = Regex("mood|genre|feel|vibe|chill|energy")
    }
}

/**
 * The signed request's `SAPISIDHASH` value: `SHA1("<seconds> <SAPISID> <origin>")` as lowercase hex.
 * Extracted so a unit test can pin the exact format; the secret is never logged.
 */
internal fun sapisidHash(
    sapisid: String,
    timestampSeconds: Long,
    origin: String = "https://music.youtube.com",
): String {
    val input = "$timestampSeconds $sapisid $origin"
    return MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
