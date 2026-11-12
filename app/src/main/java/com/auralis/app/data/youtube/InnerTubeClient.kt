package com.auralis.app.data.youtube

import com.auralis.app.logging.AppLog
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

/** Thrown when the stored Google session is no longer accepted; the UI asks the user to sign in again. */
class YouTubeAuthException(message: String) : IllegalStateException(message)

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
    private val auth: () -> YouTubeAuth? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

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

        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .authed()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLog.e(TAG, "InnerTube search failed with HTTP ${response.code}")
                throw IllegalStateException("YouTube search failed (HTTP ${response.code})")
            }
            return runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        }
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

        val request = Request.Builder()
            .url(NEXT_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .authed()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLog.e(TAG, "InnerTube radio failed with HTTP ${response.code}")
                throw IllegalStateException("YouTube radio failed (HTTP ${response.code})")
            }
            parseRadioPage(body).also { AppLog.d(TAG, "InnerTube radio parsed ${it.songs.size} songs") }
        }
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

        val request = Request.Builder()
            .url(NEXT_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .authed()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLog.e(TAG, "InnerTube radio continuation failed with HTTP ${response.code}")
                throw IllegalStateException("YouTube radio continuation failed (HTTP ${response.code})")
            }
            parseRadioContinuation(body).also {
                AppLog.d(TAG, "InnerTube radio continuation parsed ${it.songs.size} songs")
            }
        }
    }

    /** Query autocomplete used for a zero-result correction; only the text values are needed. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("input", query)
        }.toString()

        val request = Request.Builder()
            .url(SUGGESTIONS_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .authed()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext emptyList()
            parseSuggestions(body)
        }
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

    /** Songs behind a shelf/playlist card (mood chip, album, playlist). */
    suspend fun browseSongs(browseId: String): List<YtSong> = withContext(Dispatchers.IO) {
        val out = ArrayList<YtSong>()
        runCatching { collectSongs(browseRoot(browseId), out) }
        out.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
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

    /** Display name and avatar for the signed-in account, if the account menu exposes them. */
    suspend fun accountInfo(): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("context", clientContext()) }.toString()
            val root = postJson(ACCOUNT_MENU_URL, payload)
            val name = findString(root, ACCOUNT_NAME_KEYS)?.takeIf { it.isNotBlank() } ?: return@runCatching null
            name to (findAvatar(root) ?: "")
        }.getOrNull()
    }

    /** The account's liked-songs playlist (LM). Songs only. */
    suspend fun likedSongs(): List<YtSong> = browseSongs("VLLM")

    /** Read-only account playlists from the YouTube Music library landing page. */
    suspend fun libraryPlaylists(): List<YtPlaylist> = withContext(Dispatchers.IO) {
        val out = ArrayList<YtPlaylist>()
        runCatching { collectPlaylists(browseRoot("FEmusic_library_landing"), out) }
        out.distinctBy { it.id }
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

    // ---- Playback history reporting (best-effort, never throws) ----------------------------

    /**
     * Tells YouTube a signed-in song really played, so it lands in the account history. The
     * tracking URLs come from the InnerTube `player` response and require a client-generated `cpn`;
     * any failure is swallowed because playback must never depend on this.
     */
    suspend fun reportPlayback(videoId: String, watchedSeconds: Long): Boolean = withContext(Dispatchers.IO) {
        if (auth() == null) return@withContext false
        runCatching {
            val payload = buildJsonObject {
                put("context", clientContext())
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString()
            val tracking = postJson(PLAYER_URL, payload)["playbackTracking"]?.asObject()
                ?: return@runCatching false
            val playbackUrl = tracking["videostatsPlaybackUrl"]?.asObject()?.get("baseUrl")?.asString()
            val watchtimeUrl = tracking["videostatsWatchtimeUrl"]?.asObject()?.get("baseUrl")?.asString()
            val cpn = randomCpn()
            val seconds = watchedSeconds.coerceAtLeast(HISTORY_MIN_SECONDS)
            playbackUrl?.let { getBlank(trackingUrl(it, cpn, null)) }
            watchtimeUrl?.let { getBlank(trackingUrl(it, cpn, seconds)) }
            !playbackUrl.isNullOrBlank() || !watchtimeUrl.isNullOrBlank()
        }.onFailure { AppLog.d(TAG, "Playback reporting skipped: ${it.message}") }.getOrDefault(false)
    }

    private fun trackingUrl(base: String, cpn: String, cmtSeconds: Long?): String {
        val separator = if (base.contains('?')) '&' else '?'
        val withCpn = "$base${separator}cpn=$cpn&ver=2&fmt=1"
        return if (cmtSeconds != null) "$withCpn&cmt=$cmtSeconds" else withCpn
    }

    private fun getBlank(url: String) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).authed().build()
        http.newCall(request).execute().use { it.body?.close() }
    }

    private fun randomCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        val random = java.security.SecureRandom()
        return buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun browseRoot(browseId: String): JsonObject {
        val payload = buildJsonObject {
            put("context", clientContext())
            put("browseId", browseId)
        }.toString()
        return postJson(BROWSE_URL, payload)
    }

    private fun postJson(url: String, payload: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .authed()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw YouTubeAuthException("YouTube Music rejected the stored session")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("YouTube request failed (HTTP ${response.code})")
            }
            return runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: throw IllegalStateException("YouTube returned an unreadable response")
        }
    }

    private fun clientContext(gl: String = DEFAULT_GL, hl: String = DEFAULT_HL): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", CLIENT_NAME)
            put("clientVersion", CLIENT_VERSION)
            put("hl", hl)
            put("gl", gl)
        }
        auth()?.let { session ->
            val behalf = session.dataSyncId.substringBefore("||")
            if (behalf.isNotBlank()) {
                putJsonObject("user") { put("onBehalfOfUser", behalf) }
            }
        }
    }

    private fun Request.Builder.authed(): Request.Builder {
        val session = auth() ?: return this
        val sapisid = session.sapisid ?: return this
        val timestamp = System.currentTimeMillis() / 1000
        val hash = sha1Hex("$timestamp $sapisid $ORIGIN")
        return header("Cookie", session.cookie)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$hash")
            .header("X-Goog-AuthUser", "0")
            .header("X-Goog-Visitor-Id", session.visitorData)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
    }

    private fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseHomeShelves(root: JsonObject): List<YtShelf> {
        val sections = root["contents"]?.asObject()
            ?.get("singleColumnBrowseResultsRenderer")?.asObject()
            ?.get("tabs")?.asArray()?.firstOrNull()?.asObject()
            ?.get("tabRenderer")?.asObject()
            ?.get("content")?.asObject()
            ?.get("sectionListRenderer")?.asObject()
            ?.get("contents")?.asArray() ?: return emptyList()
        val shelves = ArrayList<YtShelf>()
        for (section in sections) {
            val obj = section.asObject() ?: continue
            val renderer = obj["musicCarouselShelfRenderer"]?.asObject()
                ?: obj["musicShelfRenderer"]?.asObject()
                ?: continue
            val contents = renderer["contents"]?.asArray() ?: continue
            val items = ArrayList<YtCard>()
            for (entry in contents) {
                val inner = entry.asObject() ?: continue
                val card = parseCard(inner)
                    ?: inner.values.firstNotNullOfOrNull { value -> value.asObject()?.let(::parseCard) }
                if (card != null) items += card
            }
            if (items.isNotEmpty()) shelves += YtShelf(shelfTitle(renderer), items)
        }
        return shelves
    }

    private fun shelfTitle(renderer: JsonObject): String {
        textOf(renderer["title"])?.takeIf { it.isNotBlank() }?.let { return it }
        renderer["header"]?.asObject()
            ?.get("musicCarouselShelfBasicHeaderRenderer")?.asObject()
            ?.get("title")?.let { textOf(it)?.takeIf(String::isNotBlank)?.let { title -> return title } }
        return "YouTube Music"
    }

    private fun parseCard(entry: JsonObject): YtCard? {
        entry["musicTwoRowItemRenderer"]?.asObject()?.let { renderer ->
            val title = textOf(renderer["title"]) ?: return null
            val subtitle = textOf(renderer["subtitle"]).orEmpty()
            val thumb = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                ?.get("url")?.asString()?.let(::normalizeArtworkUrl)
            val navigation = renderer["navigationEndpoint"]?.asObject()
            val videoId = navigation?.get("watchEndpoint")?.asObject()?.get("videoId")?.asString()
            val browseId = navigation?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
            return YtCard(title, subtitle, thumb, videoId, browseId)
        }
        entry["musicResponsiveListItemRenderer"]?.asObject()?.let { renderer ->
            parseRenderer(renderer)?.let { song ->
                return YtCard(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = null,
                    durationMs = song.durationMs,
                )
            }
            // No video id: this is an artist / album / playlist row. Keep its browse id so the
            // Home "Popular Artists" row can open the artist page.
            val title = renderer.flexText(0)?.takeIf { it.isNotBlank() } ?: return null
            val browseId = renderer["navigationEndpoint"]?.asObject()
                ?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
                ?.takeIf { it.isNotBlank() } ?: return null
            val thumb = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                ?.get("url")?.asString()?.let(::normalizeArtworkUrl)
            return YtCard(
                title = title,
                subtitle = renderer.flexText(1).orEmpty(),
                thumbnailUrl = thumb,
                videoId = null,
                browseId = browseId,
            )
        }
        return null
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

    private fun collectPlaylists(element: JsonElement?, out: MutableList<YtPlaylist>) {
        when (element) {
            is JsonObject -> {
                element["musicTwoRowItemRenderer"]?.asObject()?.let { renderer ->
                    val id = renderer["navigationEndpoint"]?.asObject()
                        ?.get("browseEndpoint")?.asObject()?.get("browseId")?.asString()
                        ?.takeIf { it.startsWith("VL") }
                    val title = textOf(renderer["title"])
                    if (id != null && !title.isNullOrBlank()) {
                        val thumb = renderer.thumbnailArray()?.lastOrNull()?.asObject()
                            ?.get("url")?.asString()?.let(::normalizeArtworkUrl)
                        out += YtPlaylist(id, title, thumb)
                    }
                }
                element.values.forEach { collectPlaylists(it, out) }
            }
            is JsonArray -> element.forEach { collectPlaylists(it, out) }
            else -> Unit
        }
    }

    private fun findString(element: JsonElement?, keys: Set<String>): String? = when (element) {
        is JsonObject -> {
            for (key in keys) {
                textOf(element[key])?.takeIf { it.isNotBlank() }?.let { return it }
            }
            element.values.firstNotNullOfOrNull { findString(it, keys) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, keys) }
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
        for (song in distinct) {
            val status = when {
                song.thumbnailUrl == null -> "null"
                song.thumbnailUrl.isBlank() -> "empty"
                else -> "valid (${song.thumbnailUrl})"
            }
            AppLog.i(TAG, "Search result [${song.videoId}] “${song.title}”: thumbnail URL is $status")
        }
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
        )
    }

    private fun logRadioSongs(songs: List<YtSong>) {
        for (song in songs.distinctBy { it.videoId }) {
            val status = when {
                song.thumbnailUrl == null -> "null"
                song.thumbnailUrl.isBlank() -> "empty"
                else -> "valid (${song.thumbnailUrl})"
            }
            AppLog.i(TAG, "Radio track [${song.videoId}] “${song.title}”: thumbnail URL is $status")
        }
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

    private fun parseRenderer(renderer: JsonObject): YtSong? {
        val videoId = renderer["playlistItemData"]?.asObject()?.get("videoId")?.asString()
            ?: findVideoId(renderer["overlay"]) ?: return null
        val title = renderer.flexText(0)?.takeIf { it.isNotBlank() } ?: return null
        val subtitle = renderer.flexText(1).orEmpty()

        val tokens = subtitle.split('•').map { it.trim() }.filter { it.isNotEmpty() }
        val durationToken = tokens.lastOrNull()?.takeIf { DURATION_PATTERN.matches(it) }
        val metadata = if (durationToken != null) tokens.dropLast(1) else tokens
        val artist = metadata.firstOrNull().orEmpty().ifBlank { "Unknown artist" }
        val album = if (metadata.size > 1) metadata.drop(1).joinToString(" • ") else ""

        val thumbUrl = parseSearchThumbnail(renderer, videoId)

        return YtSong(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = parseDurationMs(durationToken),
            thumbnailUrl = thumbUrl,
        )
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
        const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        const val SUGGESTIONS_URL = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false"
        const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        const val ACCOUNT_MENU_URL = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"
        const val LIKE_URL = "https://music.youtube.com/youtubei/v1/like/like?prettyPrint=false"
        const val UNLIKE_URL = "https://music.youtube.com/youtubei/v1/like/removelike?prettyPrint=false"
        const val PLAYLIST_CREATE_URL = "https://music.youtube.com/youtubei/v1/playlist/create?prettyPrint=false"
        const val PLAYLIST_DELETE_URL = "https://music.youtube.com/youtubei/v1/playlist/delete?prettyPrint=false"
        const val BROWSE_EDIT_PLAYLIST_URL = "https://music.youtube.com/youtubei/v1/browse/edit_playlist?prettyPrint=false"
        const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val ORIGIN = "https://music.youtube.com"
        const val WARM_UP_URL = "https://music.youtube.com/generate_204"
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_VERSION = "1.20250101.00.00"
        const val USER_AGENT = YOUTUBE_USER_AGENT
        const val DEFAULT_GL = "US"
        const val DEFAULT_HL = "en"
        const val SONGS_ONLY_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val MAX_SUGGESTIONS = 8
        const val HISTORY_MIN_SECONDS = 30L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DURATION_PATTERN = Regex("^\\d{1,3}(?::\\d{1,2}){1,2}$")
        val RADIO_CONTINUATION_KEYS = listOf(
            "nextRadioContinuationData",
            "nextContinuationData",
            "reloadContinuationData",
        )
        val ACCOUNT_NAME_KEYS = setOf("accountName", "displayName", "givenName")
        val MOOD_PATTERN = Regex("mood|genre|feel|vibe|chill|energy")
    }
}
