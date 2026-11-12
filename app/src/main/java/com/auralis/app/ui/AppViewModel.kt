package com.auralis.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auralis.app.AppContainer
import com.auralis.app.data.db.PlayEventEntity
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.PlaylistYtEntryEntity
import com.auralis.app.data.db.QueueItemEntity
import com.auralis.app.data.db.SearchHistoryEntity
import com.auralis.app.data.db.SpotifyImportEntity
import com.auralis.app.data.db.SpotifyImportItemEntity
import com.auralis.app.data.db.YouTubeSyncOpEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.db.toYouTubeLikeEntity
import com.auralis.app.data.db.toYouTubeRecentEntity
import com.auralis.app.data.metadata.TagData
import com.auralis.app.data.settings.PlaybackSnapshot
import com.auralis.app.data.update.UpdateUiState
import com.auralis.app.data.auth.ClerkAccount
import com.auralis.app.data.auth.EmailCodeChallenge
import com.auralis.app.data.auth.toAccount
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.data.youtube.YtSong
import com.auralis.app.data.youtube.YouTubeAuth
import com.auralis.app.data.youtube.toTrack
import com.auralis.app.data.youtube.toYouTubeLikeEntity as ytSongToLikeEntity
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.stats.ListeningStats
import com.auralis.app.domain.stats.PlayEvent
import com.auralis.app.domain.stats.WrappedStats
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.NavState
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.performance.PerformanceController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** YouTube search state. Remote results are session-only; nothing is written to the library. */
data class YouTubeSearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<Track> = emptyList(),
)

/** Home categories shown as the filter chips directly under the header. */
enum class HomeCategory(val label: String) {
    FOR_YOU("For You"),
    TRENDING("Trending"),
    NEW_RELEASES("New Releases"),
    CHILL("Chill"),
    WORKOUT("Workout"),
    FOCUS("Focus"),
}

/**
 * One horizontal Home row.
 *
 * Rows load independently: a failure in one never blanks the whole page, and a radio-seeded row can
 * page more songs in on demand through its [seedVideoId].
 */
data class HomeRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val tracks: List<Track> = emptyList(),
    val cards: List<YtCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Video id used to page more songs in through the InnerTube radio (null when not pageable). */
    val seedVideoId: String? = null,
    val hasMore: Boolean = false,
)

/**
 * The entire Home feed. [rows] keeps insertion order so the UI renders the sections in the intended
 * order without sorting. [collection*] drives the artist/collection sheet opened from a card tap.
 */
data class HomeUiState(
    val category: HomeCategory = HomeCategory.FOR_YOU,
    val rows: Map<String, HomeRow> = emptyMap(),
    val refreshing: Boolean = false,
    val collectionTitle: String? = null,
    val collectionTracks: List<Track> = emptyList(),
    val collectionLoading: Boolean = false,
    val collectionSeedVideoId: String? = null,
    val collectionMore: Boolean = false,
    /** Artist-page header (Part L): set only when the opened card is a YouTube artist. */
    val collectionArtistBrowseId: String? = null,
    val collectionArtistImageUrl: String? = null,
)

/** Sign-in gate state: which of "loading / signed out / signed in" the Clerk layer is in. */
data class AuthUiState(
    val configured: Boolean = false,
    val initializing: Boolean = true,
    val signedIn: Boolean = false,
    val account: ClerkAccount? = null,
    /** True once an email code was sent and the screen should ask for it. */
    val awaitingCode: Boolean = false,
    val email: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val sources: StateFlow<List<MusicSource>> = container.sources.observeSources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tracks: StateFlow<List<Track>> = container.database.trackDao().observeAll().map { rows -> rows.map { it.toDomain() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites: StateFlow<Set<String>> = container.database.favoriteDao().observeIds().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val playlists = container.database.playlistDao().observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlistEntries = container.database.playlistDao().observeEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlistYtEntries = container.database.playlistDao().observeYtEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val spotifyImports = container.database.spotifyDao().observeImports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = container.database.historyDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playEvents = container.database.playEventDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Recent YouTube search terms (newest first); a Home recommendation signal, kept hot for reads. */
    val searchHistory: StateFlow<List<String>> = container.database.searchHistoryDao()
        .observeRecent(20)
        .map { rows -> rows.map { it.query } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playback = container.playback.state
    /**
     * The single resolved visual workload for the whole app. Combines the persisted mode with
     * live Battery Saver / thermal state; collected once in AuralisRoot and provided downwards.
     */
    private val performanceController = PerformanceController(
        settings = settings,
        conditions = container.performance.conditions,
        scope = viewModelScope,
    )
    val performanceProfile = performanceController.profile
    val performanceMode = performanceController.mode
    val performanceReason = performanceController.reason
    private val _nav = MutableStateFlow(NavState())
    val nav = _nav.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics = _lyrics.asStateFlow()
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading = _lyricsLoading.asStateFlow()
    /** Per-song lyrics timing offset in ms (Part J), applied on top of the global offset. */
    private val _lyricsOffsetMs = MutableStateFlow(0)
    val lyricsOffsetMs = _lyricsOffsetMs.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    /** In-app updater: GitHub release check, APK download and install state. */
    val updateState: StateFlow<UpdateUiState> = container.updates.state
    /** YouTube source: search results, in-flight lookup and per-row resolve state. */
    private val _youtubeSearch = MutableStateFlow(YouTubeSearchUiState())
    val youtubeSearch = _youtubeSearch.asStateFlow()
    private val _youtubeResolvingId = MutableStateFlow<String?>(null)
    val youtubeResolvingId = _youtubeResolvingId.asStateFlow()
    /** Signed-in YouTube Music session; drives login UI and InnerTube auth headers. */
    val youtubeAuth = container.youtubeAuth.auth
    /** The Home discovery feed: rows load independently, cached in memory until refreshed. */
    private val _home = MutableStateFlow(HomeUiState())
    val home = _home.asStateFlow()
    private var homeJob: Job? = null
    /** Sign-in gate state (Clerk). Inert and "signed in" when no publishable key is configured. */
    private val _auth = MutableStateFlow(
        AuthUiState(configured = container.clerkAuth.configured, initializing = container.clerkAuth.configured),
    )
    val auth = _auth.asStateFlow()
    /** True while the one-time "Connect YouTube Music" prompt (after a Clerk sign-in) is showing. */
    private val _youtubeConnectPrompt = MutableStateFlow(false)
    val youtubeConnectPrompt = _youtubeConnectPrompt.asStateFlow()
    /** The email the prompt hands to the YouTube sign-in WebView as `login_hint`. */
    private val _youtubeConnectEmail = MutableStateFlow<String?>(null)
    val youtubeConnectEmail = _youtubeConnectEmail.asStateFlow()
    /** The in-flight email-code challenge, kept between "send code" and "verify code". */
    private var pendingEmail: EmailCodeChallenge? = null
    /** Recently played YouTube songs, mapped to [Track] for Continue Listening and recommendations. */
    val youtubeRecents: StateFlow<List<Track>> = container.database.youtubeDao().observeRecents(20)
        .map { rows -> rows.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Full play history (newest first) for the History screen, with play counts. */
    val youtubeHistory: StateFlow<List<com.auralis.app.data.db.YouTubeRecentEntity>> =
        container.database.youtubeDao().observeHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Liked YouTube video ids from Room, mirrored from the account and used for instant UI. */
    val youtubeLikedIds: StateFlow<Set<String>> = container.database.youtubeDao().observeLikedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    /** Liked YouTube songs (Room mirror) as [Track]s for the Library "Liked Songs" list. */
    val youtubeLikedTracks: StateFlow<List<Track>> = container.database.youtubeDao().observeLiked()
        .map { rows -> rows.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _youtubePlaylists = MutableStateFlow<List<com.auralis.app.data.youtube.YtPlaylist>>(emptyList())
    val youtubePlaylists = _youtubePlaylists.asStateFlow()
    /** Offline YouTube downloads, exposed as local playable tracks. */
    val downloads: StateFlow<List<Track>> = container.database.downloadDao().observeAll()
        .map { rows -> rows.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloadedIds: StateFlow<Set<String>> = container.database.downloadDao().observeAll()
        .map { rows -> rows.map { it.videoId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    /**
     * Resolved streamed tracks by id. Session-only by design: the library, favorites, stats and
     * saved queue stay local; this map only lets Now Playing/dock/lyrics look the current song up.
     */
    private val _remoteTracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val remoteTracks = _remoteTracks.asStateFlow()
    private val youtubeQuery = MutableStateFlow("")
    private val youtubeRequest = MutableStateFlow(0)
    private var remoteQueueEpoch = 0
    private var refillJob: Job? = null
    private var refillKey: String? = null
    private var radioSeedingTrackId: String? = null
    private var searchPrefetchJob: Job? = null
    private val prefetchedStreamIds = mutableSetOf<String>()
    /** Track ids we have already tried to upgrade to real album art (Part J). */
    private val artworkResolved = mutableSetOf<String>()
    private var tapStartedAt = 0L
    private var tapTrackId: String? = null
    private var loadedLyricsTrackId: String? = null
    private var prefetchedLyricsTrackId: String? = null
    private var lyricsPrefetchJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var lastRecordedTrackId: String? = null
    private var lastRecordedRemoteId: String? = null
    private var historyWatchedId: String? = null
    /** Guards [flushYouTubeSync] so its queue replay never runs twice at once. */
    private var syncFlushing = false

    init {
        // The updater runs one throttled check per launch; its user-facing messages reuse the
        // app snackbar channel so Settings and the dialog share one style.
        container.updates.start()
        viewModelScope.launch { container.updates.messages.collect { _messages.trySend(it) } }

        // Clerk hand-off: the first time a Clerk (Google) user signs in, turn the YouTube Music
        // integration on and offer a one-time "Connect YouTube Music" prompt carrying their email.
        viewModelScope.launch {
            _auth.map { it.signedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) maybeOfferYouTubeConnect()
            }
        }

        viewModelScope.launch {
            val savedQueue = runCatching { container.database.queueDao().getAll() }
                .onFailure { report("Couldn't read the saved playback queue", it) }
                .getOrDefault(emptyList())
            if (savedQueue.isEmpty()) return@launch

            val snapshot = runCatching { container.playbackSnapshot.snapshot.first() }
                .onFailure { report("Couldn't read the playback checkpoint", it) }
                .getOrNull()
            val connected = container.playback.state.first { it.connected }
            // The service can outlive the Activity; retain its live session instead of replacing it.
            if (connected.trackId != null || connected.queueIds.isNotEmpty()) return@launch

            val library = tracks.first { it.isNotEmpty() }.associateBy { it.id }
            val restoredQueue = savedQueue.mapNotNull { library[it.trackId] }.distinctBy { it.id }
            if (restoredQueue.isEmpty()) {
                runCatching { container.database.queueDao().clear() }
                    .onFailure { report("Couldn't clear an invalid saved queue", it) }
                return@launch
            }
            val savedIndex = snapshot?.trackId?.let { id -> restoredQueue.indexOfFirst { it.id == id } } ?: -1
            val index = savedIndex.takeIf { it >= 0 } ?: 0
            container.playback.restore(
                restoredQueue,
                index,
                snapshot?.positionMs ?: 0L,
                snapshot?.wasPlaying ?: false,
                snapshot?.shuffle ?: false,
                snapshot?.repeatMode ?: 0,
            )
        }

        viewModelScope.launch {
            container.playback.state.map { state -> state.takeIf { it.connected }?.queueIds }
                .filterNotNull().filter { it.isNotEmpty() }.distinctUntilChanged().collect { ids ->
                    // Remote (YouTube) ids have no library row and must never be persisted.
                    val savable = localQueueIds(ids) ?: return@collect
                    runCatching { container.database.queueDao().replaceAll(savable.mapIndexed { i, id -> QueueItemEntity(i, id, i) }) }
                        .onFailure { report("Couldn't save the playback queue", it) }
                }
        }

        viewModelScope.launch {
            container.playback.state
                .filter { it.connected && it.trackId != null }
                .map { state ->
                    PlaybackSnapshot(
                        trackId = state.trackId,
                        positionMs = (state.positionMs / 5_000L) * 5_000L,
                        wasPlaying = state.playing,
                        shuffle = state.shuffle,
                        repeatMode = state.repeat,
                    )
                }
                .distinctUntilChanged()
                .collect { snapshot ->
                    runCatching { container.playbackSnapshot.save(snapshot) }
                        .onFailure { report("Couldn't save the playback position", it) }
                }
        }

        // Record real play events for Wrapped as Media3 advances through the queue.
        viewModelScope.launch {
            container.playback.state
                .filter { it.connected && it.trackId != null && it.playing }
                .map { it.trackId }
                .distinctUntilChanged()
                .collect { trackId -> trackId?.let(::recordPlayEvent) }
        }

        // ONE central hook for the player's current media item. Observing the session state here
        // means every way a song can change (list tap, next/previous, auto-advance, shuffle or
        // repeat, notification/Bluetooth controls, restored session) starts the lyrics lookup,
        // whether or not the lyrics page is open. loadLyrics() cancels a previous in-flight fetch.
        viewModelScope.launch {
            var lastTrackId: String? = null
            combine(
                container.playback.state
                    .map { state -> state.takeIf { it.connected }?.trackId }
                    .distinctUntilChanged(),
                tracks,
                _remoteTracks,
            ) { trackId, library, remote ->
                // Remote tracks are not in the DB, so resolve from the session map first.
                trackId?.let { id -> remote[id] ?: library.firstOrNull { it.id == id } }
            }
                .filterNotNull()
                .collect { track ->
                    if (track.id != lastTrackId) {
                        lastTrackId = track.id
                        loadLyrics(track)
                    }
                }
        }

        // Prefetch and parse the next queue item while the current one plays, so opening its
        // lyrics page is instant. Parsing happens off the main thread inside LyricsRepository.
        viewModelScope.launch {
            container.playback.state.collect { state ->
                val currentId = state.trackId ?: return@collect
                val nextId = if (state.shuffle) {
                    state.upNextIds.firstOrNull()
                } else {
                    val index = state.queueIds.indexOf(currentId)
                    if (index >= 0) state.queueIds.getOrNull(index + 1) else null
                } ?: return@collect
                if (nextId == currentId || nextId == loadedLyricsTrackId) return@collect
                if (nextId == prefetchedLyricsTrackId) return@collect
                val next = _remoteTracks.value[nextId]
                    ?: tracks.value.firstOrNull { it.id == nextId }
                    ?: return@collect
                prefetchedLyricsTrackId = next.id
                lyricsPrefetchJob?.cancel()
                lyricsPrefetchJob = viewModelScope.launch {
                    container.lyrics.prefetch(next)
                }
            }
        }

        // ONE search pipeline: 300 ms debounce + flatMapLatest cancels the previous query as soon
        // as the user types again. The repository's LRU makes a repeated prefix instant.
        viewModelScope.launch {
            combine(youtubeQuery, youtubeRequest) { query, request -> query to request }
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { (query, _) ->
                    if (query.isBlank()) {
                        flowOf(YouTubeSearchUiState())
                    } else {
                        flow {
                            val startedAt = System.currentTimeMillis()
                            emit(YouTubeSearchUiState(query = query, loading = true))
                            var songs = container.youtube.search(query)
                            if (songs.isEmpty()) {
                                // A single corrective call, only when the literal query found nothing.
                                val suggestion = runCatching {
                                    container.youtube.suggestions(query)
                                        .firstOrNull { it.isNotBlank() && !it.equals(query, true) }
                                }.getOrNull()
                                if (suggestion != null) {
                                    val corrected = runCatching { container.youtube.search(suggestion) }
                                        .getOrDefault(emptyList())
                                    if (corrected.isNotEmpty()) songs = corrected
                                }
                            }
                            val results = songs.map { it.toTrack() }
                            AppLog.i(
                                "Timing",
                                "search request -> render ${System.currentTimeMillis() - startedAt} ms " +
                                    "(${results.size} results)",
                            )
                            emit(YouTubeSearchUiState(query = query, results = results))
                            if (results.isNotEmpty()) recordSearch(query)
                            prefetchSearchResults(results)
                        }.catch { error ->
                            if (error is CancellationException) throw error
                            AppLog.e("YouTube", "Search failed", error)
                            _youtubeSearch.value = YouTubeSearchUiState(
                                query = query,
                                error = "YouTube search failed. Check your connection and try again.",
                            )
                        }
                    }
                }
                .collect { _youtubeSearch.value = it }
        }

        // Refill the automix before it runs dry. The InnerTube radio call returns up to 50 items;
        // we add only the ones not already queued so the timeline stays de-duplicated.
        viewModelScope.launch {
            container.playback.state.collect { state ->
                val currentId = state.trackId ?: return@collect
                if (!currentId.startsWith(YOUTUBE_TRACK_PREFIX)) return@collect
                val current = _remoteTracks.value[currentId] ?: return@collect
                val videoId = current.remoteId ?: return@collect
                // History counts a play only after 30 s. The track is reset when it changes so a
                // re-listen of the same song is counted again.
                if (historyWatchedId != currentId) {
                    historyWatchedId = currentId
                    lastRecordedRemoteId = null
                }
                if (currentId != lastRecordedRemoteId && state.positionMs >= HISTORY_MIN_MS) {
                    lastRecordedRemoteId = currentId
                    viewModelScope.launch {
                        runCatching {
                            val count = container.database.youtubeDao().playCountOf(videoId) ?: 0
                            container.database.youtubeDao().upsertRecent(current.toYouTubeRecentEntity(count + 1))
                        }.onFailure { report("Couldn't save history", it) }
                    }
                    // Signed-in history sync: tell YouTube this really played. Fired on a background
                    // job and fully swallowed so it can never block or break playback.
                    if (settings.value.youtubeSyncHistory && container.youtubeAuth.snapshot()?.signedIn == true) {
                        val watchedSeconds = state.positionMs / 1000L
                        viewModelScope.launch {
                            runCatching { container.youtube.reportPlayback(videoId, watchedSeconds) }
                                .onFailure { AppLog.d("YouTube", "History report skipped: ${it.message}") }
                        }
                    }
                }
                val remoteAhead = state.upNextIds.count { it.startsWith(YOUTUBE_TRACK_PREFIX) }
                if (remoteAhead >= MIN_RADIO_AHEAD) return@collect
                // The play tap is already seeding the automix; let that finish before refilling.
                if (radioSeedingTrackId != null) return@collect
                if (refillJob?.isActive == true) return@collect
                val key = "$currentId:$remoteAhead"
                if (refillKey == key) return@collect
                refillKey = key
                refillJob = viewModelScope.launch {
                    try {
                        val queueIds = container.playback.state.value.queueIds
                        val existing = queueIds.toSet()
                        val seenVideoIds = queueIds.asSequence()
                            .filter { it.startsWith(YOUTUBE_TRACK_PREFIX) }
                            .map { it.removePrefix(YOUTUBE_TRACK_PREFIX) }
                            .toSet()
                        val fallbackVideoId = queueIds.lastOrNull()
                            ?.takeIf { it.startsWith(YOUTUBE_TRACK_PREFIX) }
                            ?.removePrefix(YOUTUBE_TRACK_PREFIX)
                        val additions = container.youtube.radioNext(videoId, fallbackVideoId, seenVideoIds)
                            .asSequence()
                            .map { it.toTrack() }
                            .filter { it.id != currentId && it.id !in existing }
                            .distinctBy { it.id }
                            .take(RADIO_REFILL_SIZE)
                            .toList()
                        if (additions.isNotEmpty()) {
                            _remoteTracks.update { known -> known + additions.associateBy { it.id } }
                            container.playback.addToQueue(additions)
                            AppLog.i("YouTube", "Radio refilled with ${additions.size} tracks")
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        AppLog.w("YouTube", "Radio refill failed", error)
                    }
                }
            }
        }

        // Resolve the current and next stream URLs while the current song plays, so a notification
        // or lock-screen "next" starts from the memory cache instead of a cold extraction.
        viewModelScope.launch {
            container.playback.state
                .map { state -> state.trackId to state.upNextIds.firstOrNull() }
                .distinctUntilChanged()
                .collect { (currentId, nextId) ->
                    currentId?.let(::prefetchRemoteStream)
                    nextId?.let(::prefetchRemoteStream)
                    // Upgrade a YouTube video thumbnail to real square album art (Part J).
                    currentId?.let(::ensureArtwork)
                    nextId?.let(::ensureArtwork)
                }
        }

        // Measured end-to-end: time from the search-row tap to the first rendered audio frame.
        viewModelScope.launch {
            container.playback.state.collect { state ->
                val pendingId = tapTrackId ?: return@collect
                if (state.error != null) {
                    tapTrackId = null
                } else if (state.trackId == pendingId && state.playing) {
                    AppLog.i(
                        "Timing",
                        "tap -> first audio ${System.currentTimeMillis() - tapStartedAt} ms",
                    )
                    tapTrackId = null
                }
            }
        }

        // Sign-in gate: derive the whole auth UI state from Clerk's initialization + user flows.
        // The flows are cold no-ops when Clerk is not configured, so this is always safe to collect.
        if (container.clerkAuth.configured) {
            viewModelScope.launch {
                combine(
                    container.clerkAuth.isInitialized,
                    container.clerkAuth.user,
                    container.clerkAuth.initializationError,
                ) { ready, user, error -> Triple(ready, user, error) }
                    .collect { (ready, user, error) ->
                        _auth.update { state ->
                            state.copy(
                                configured = true,
                                // Stop the spinner once Clerk reports an error, otherwise an offline
                                // first launch would sit on "Preparing your session..." forever.
                                initializing = !ready && user == null && error == null,
                                signedIn = user != null,
                                account = user?.toAccount(),
                                error = when {
                                    user != null -> null
                                    error != null && !ready -> "Couldn't reach the sign-in service. Check your connection."
                                    else -> state.error
                                },
                                awaitingCode = if (user != null) false else state.awaitingCode,
                            )
                        }
                    }
            }
        } else {
            _auth.value = AuthUiState(configured = false, initializing = false, signedIn = true)
        }
    }

    fun selectTab(tab: Tab) { _nav.update { it.selectTab(tab) } }
    fun push(destination: Destination) { _nav.update { it.push(destination) } }
    fun back() { _nav.update { it.pop() } }
    fun updateSettings(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { runCatching { container.settings.update(transform) }.onFailure { report("Couldn't save settings", it) } } }

    // ---- Clerk authentication -----------------------------------------------------------------

    /** Starts Google sign-in through Clerk (browser OAuth; returns via the app's deep link). */
    fun signInWithGoogle() {
        if (_auth.value.busy) return
        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            container.clerkAuth.signInWithGoogle()
                .onSuccess { AppLog.i("Clerk", "Google sign-in completed") }
                .onFailure { error ->
                    _auth.update { it.copy(error = error.localizedMessage ?: "Google sign-in failed") }
                }
            _auth.update { it.copy(busy = false) }
        }
    }

    /** Sends a one-time sign-in code to [email] and switches the screen to the code step. */
    fun requestEmailCode(email: String) {
        val clean = email.trim()
        if (clean.isBlank() || !clean.contains('@')) {
            _auth.update { it.copy(error = "Enter a valid email address") }
            return
        }
        if (_auth.value.busy) return
        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            container.clerkAuth.requestEmailCode(clean)
                .onSuccess { challenge ->
                    pendingEmail = challenge
                    _auth.update { it.copy(busy = false, awaitingCode = true, email = clean) }
                    _messages.trySend("We emailed a sign-in code to $clean")
                }
                .onFailure { error ->
                    _auth.update { it.copy(busy = false, error = error.localizedMessage ?: "Couldn't send the code") }
                }
        }
    }

    /** Verifies the emailed code; on success Clerk starts a session and [auth] flips to signed-in. */
    fun verifyEmailCode(code: String) {
        val challenge = pendingEmail ?: return
        val clean = code.trim()
        if (clean.isBlank()) return
        if (_auth.value.busy) return
        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            container.clerkAuth.verifyEmailCode(challenge, clean)
                .onSuccess {
                    pendingEmail = null
                    _auth.update { it.copy(busy = false, awaitingCode = false, email = "") }
                }
                .onFailure { error ->
                    _auth.update { it.copy(busy = false, error = error.localizedMessage ?: "That code didn't work") }
                }
        }
    }

    /** Goes back from the code step to the email step (also drops the pending challenge). */
    fun editEmail() {
        pendingEmail = null
        _auth.update { it.copy(awaitingCode = false, email = "", error = null) }
    }

    fun clearAuthError() { _auth.update { it.copy(error = null) } }

    /** Signs out of Clerk. The sign-in screen returns, so the user can pick another account. */
    fun signOutClerk() {
        if (_auth.value.busy) return
        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            container.clerkAuth.signOut()
                .onSuccess { _messages.trySend("Signed out") }
                .onFailure { error ->
                    _auth.update { it.copy(error = error.localizedMessage ?: "Sign-out failed") }
                }
            _auth.update { it.copy(busy = false) }
        }
    }

    // ---- Stage 1B: YouTube Music integration -------------------------------------------------

    fun setYouTubeIntegration(enabled: Boolean) {
        updateSettings { it.copy(youtubeIntegration = enabled) }
        if (!enabled) {
            _home.value = HomeUiState()
            return
        }
        refreshYouTubeHome()
        refreshYouTubeAccount()
        refreshYouTubeLibrary()
    }

    /** Cached Room likes render immediately; the account refresh runs in the background. */
    fun refreshYouTubeLibrary() {
        if (settings.value.youtubeIntegration.not()) return
        viewModelScope.launch {
            runCatching { container.youtube.libraryPlaylists() }
                .onSuccess { _youtubePlaylists.value = it }
                .onFailure { AppLog.w("YouTube", "Account playlists skipped", it) }
        }
        if (container.youtubeAuth.snapshot()?.signedIn == true) loadLikedSongs()
    }

    fun openYouTubePlaylist(playlist: com.auralis.app.data.youtube.YtPlaylist) {
        viewModelScope.launch {
            runCatching { container.youtube.browseSongs(playlist.id) }
                .onSuccess { songs ->
                    val list = songs.map { it.toTrack() }
                    if (list.isNotEmpty()) {
                        _remoteTracks.update { known -> known + list.associateBy { it.id } }
                        play(list.first(), list)
                    }
                }
                .onFailure { report("Couldn't open ${playlist.title}", it) }
        }
    }

    /** Re-runs the whole Home feed for the current category (pull-to-refresh, header button). */
    fun refreshYouTubeHome() = loadHome(_home.value.category, force = true)

    /**
     * Loads the Home discovery feed for [category]. Every section runs in its own coroutine and is
     * written back as soon as it lands, so the feed fills progressively and one failed section never
     * blanks the page. A non-forced call with content already present is a no-op (the repository's
     * LRUs make an accidental repeat cheap anyway).
     *
     * Recommendation signals (see [recommendationSeeds]): YouTube listening history (weighted by play
     * count), recently played, favourite tracks' artists, the on-device library's artists and the
     * user's search history. Deliberately public data + AURALIS activity only — a Google/Clerk
     * sign-in does not grant the private YouTube Music personalised feed.
     */
    fun loadHome(category: HomeCategory = _home.value.category, force: Boolean = false) {
        if (settings.value.youtubeIntegration.not()) return
        val unchanged = _home.value.category == category
        val hasContent = _home.value.rows.values.any { it.tracks.isNotEmpty() || it.cards.isNotEmpty() }
        if (!force && unchanged && hasContent) return

        val specs = homeSections(category)
        _home.update { previous ->
            previous.copy(
                category = category,
                refreshing = true,
                rows = specs.associate { spec ->
                    val existing = previous.rows[spec.id]
                    val keep = if (unchanged) existing else null
                    spec.id to (keep?.copy(title = spec.title, loading = false, error = null)
                        ?: HomeRow(id = spec.id, title = spec.title, subtitle = spec.subtitle, loading = true))
                },
            )
        }
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val jobs = specs.map { spec -> launch { runHomeRow(spec) } }
            jobs.joinAll()
            _home.update { it.copy(refreshing = false) }
        }
    }

    /** Category chip tap: loads the section set for that filter. */
    fun selectHomeCategory(category: HomeCategory) {
        if (_home.value.category == category && _home.value.rows.values.any { it.tracks.isNotEmpty() || it.cards.isNotEmpty() }) return
        loadHome(category, force = false)
    }

    /** Pages more songs into a radio-seeded row (Quick Picks / Daily Mix / Because You Listened). */
    fun loadMoreHomeRow(rowId: String) {
        val row = _home.value.rows[rowId] ?: return
        val seed = row.seedVideoId ?: return
        if (row.loading) return
        _home.update { state ->
            state.rows[rowId]?.let { state.copy(rows = state.rows + (rowId to it.copy(loading = true))) } ?: state
        }
        viewModelScope.launch {
            val seen = row.tracks.mapNotNull { it.remoteId ?: it.id.removePrefix(YOUTUBE_TRACK_PREFIX) }.toSet()
            val more = runCatching { container.youtube.radioNext(seed, row.tracks.lastOrNull()?.remoteId, seen) }
                .getOrDefault(emptyList())
                .map { it.toTrack() }
            _home.update { state ->
                val current = state.rows[rowId] ?: return@update state
                val merged = (current.tracks + more).distinctBy { it.id }
                state.copy(rows = state.rows + (rowId to current.copy(tracks = merged, loading = false, hasMore = more.isNotEmpty())))
            }
        }
    }

    /** A Home card tap: song cards play immediately; artist/collection cards open as a sheet. */
    fun openHomeCard(card: YtCard) {
        if (card.videoId != null) {
            playYouTubeCard(card)
            return
        }
        _home.update {
            it.copy(
                collectionTitle = card.title,
                collectionTracks = emptyList(),
                collectionLoading = true,
                // An artist card carries a channel id ("UC...") and no video id; albums/playlists do not.
                collectionArtistBrowseId = card.browseId?.takeIf { id -> card.videoId == null && id.startsWith("UC") },
                collectionArtistImageUrl = card.thumbnailUrl,
            )
        }
        viewModelScope.launch {
            val songs = if (card.browseId != null) {
                runCatching { container.youtube.browseSongs(card.browseId) }.getOrDefault(emptyList())
            } else {
                runCatching { container.youtube.search(card.title) }.getOrDefault(emptyList())
            }
            val results = songs.map { it.toTrack() }
            _remoteTracks.update { known -> known + results.associateBy { it.id } }
            _home.update { it.copy(collectionTracks = results, collectionLoading = false) }
        }
    }

    fun closeHomeCollection() {
        _home.update { it.copy(collectionTitle = null, collectionTracks = emptyList(), collectionLoading = false, collectionArtistBrowseId = null, collectionArtistImageUrl = null) }
    }

    private suspend fun runHomeRow(spec: HomeSectionSpec) {
        val result = runCatching { spec.load() }
        val section = result.getOrNull()
        _home.update { state ->
            val row = state.rows[spec.id] ?: return@update state
            if (section == null) {
                // Keep existing content on a failed refresh; only surface an error when the row is empty.
                state.copy(
                    rows = state.rows + (spec.id to row.copy(
                        loading = false,
                        error = if (row.tracks.isEmpty() && row.cards.isEmpty()) "Couldn't load this row" else null,
                    )),
                )
            } else {
                state.copy(
                    rows = state.rows + (spec.id to row.copy(
                        title = spec.title,
                        subtitle = section.subtitle ?: spec.subtitle,
                        tracks = section.tracks,
                        cards = section.cards,
                        loading = false,
                        error = null,
                        seedVideoId = section.seedVideoId,
                        hasMore = section.hasMore,
                    )),
                )
            }
        }
    }

    // ---- Home section definitions ------------------------------------------------------------

    /** Ordered section specs for a category. Each loads independently through the same repository. */
    private fun homeSections(category: HomeCategory): List<HomeSectionSpec> = when (category) {
        HomeCategory.FOR_YOU -> listOf(
            HomeSectionSpec(SECTION_QUICK_PICKS, "Quick Picks") { loadQuickPicks() },
            HomeSectionSpec(SECTION_TRENDING_IN, "Trending in India") { loadTrendingIndia() },
            HomeSectionSpec(SECTION_MADE_FOR_YOU, "Made For You") { loadMadeForYou() },
            HomeSectionSpec(SECTION_GLOBAL_HITS, "Global Top Hits") { loadGlobalTopHits() },
            HomeSectionSpec(SECTION_CONTINUE, "Continue Listening") { loadContinueListening() },
            HomeSectionSpec(SECTION_NEW_RELEASES, "New Releases") { loadNewReleases() },
            HomeSectionSpec(SECTION_ARTISTS, "Popular Artists") { loadPopularArtists() },
            HomeSectionSpec(SECTION_DAILY_MIX, "Daily Mix") { loadDailyMix() },
            HomeSectionSpec(SECTION_BECAUSE_YOU, "Because You Listened") { loadBecauseYouListened() },
            HomeSectionSpec(SECTION_DISCOVER_WEEKLY, "Discover Weekly") { loadDiscoverWeekly() },
        )
        HomeCategory.TRENDING -> listOf(
            HomeSectionSpec(SECTION_TRENDING_IN, "Trending in India") { loadTrendingIndia() },
            HomeSectionSpec(SECTION_GLOBAL_HITS, "Global Top Hits") { loadGlobalTopHits() },
            HomeSectionSpec(SECTION_QUICK_PICKS, "Quick Picks") { loadQuickPicks() },
            HomeSectionSpec(SECTION_NEW_RELEASES, "New Releases") { loadNewReleases() },
        )
        HomeCategory.NEW_RELEASES -> listOf(
            HomeSectionSpec(SECTION_NEW_RELEASES, "New Releases") { loadNewReleases() },
            HomeSectionSpec(SECTION_MADE_FOR_YOU, "Made For You") { loadMadeForYou() },
            HomeSectionSpec(SECTION_DISCOVER_WEEKLY, "Discover Weekly") { loadDiscoverWeekly() },
            HomeSectionSpec(SECTION_ARTISTS, "Popular Artists") { loadPopularArtists() },
        )
        HomeCategory.CHILL -> listOf(
            HomeSectionSpec(SECTION_CHILL, "Chill") { loadMoodQuery("chill lofi songs") },
            HomeSectionSpec(SECTION_DAILY_MIX, "Daily Mix") { loadDailyMix() },
            HomeSectionSpec(SECTION_MADE_FOR_YOU, "Made For You") { loadMadeForYou() },
            HomeSectionSpec(SECTION_CONTINUE, "Continue Listening") { loadContinueListening() },
        )
        HomeCategory.WORKOUT -> listOf(
            HomeSectionSpec(SECTION_WORKOUT, "Workout") { loadMoodQuery("workout motivation songs") },
            HomeSectionSpec(SECTION_GLOBAL_HITS, "Global Top Hits") { loadGlobalTopHits() },
            HomeSectionSpec(SECTION_QUICK_PICKS, "Quick Picks") { loadQuickPicks() },
        )
        HomeCategory.FOCUS -> listOf(
            HomeSectionSpec(SECTION_FOCUS, "Focus") { loadMoodQuery("focus instrumental study music") },
            HomeSectionSpec(SECTION_DAILY_MIX, "Daily Mix") { loadDailyMix() },
            HomeSectionSpec(SECTION_DISCOVER_WEEKLY, "Discover Weekly") { loadDiscoverWeekly() },
        )
    }

    private suspend fun loadQuickPicks(): HomeSection {
        val out = LinkedHashMap<String, Track>()
        val seedVideo = frequentlyPlayedVideoIds().firstOrNull() ?: recentVideoIds().firstOrNull()
        seedVideo?.let { id ->
            runCatching { container.youtube.radio(id) }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
        }
        for (seed in recommendationSeeds().take(3)) {
            runCatching { container.youtube.search(seed) }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
            if (out.size >= HOME_ROW_SIZE) break
        }
        if (out.isEmpty()) out.putAll(fallbackPopular())
        return HomeSection(
            tracks = out.values.take(HOME_ROW_SIZE),
            seedVideoId = seedVideo,
            hasMore = seedVideo != null,
        )
    }

    private suspend fun loadTrendingIndia(): HomeSection = loadChart(country = "India", gl = "IN", fallbackQuery = "trending india songs")

    private suspend fun loadGlobalTopHits(): HomeSection = loadChart(country = "International", gl = "US", fallbackQuery = "global top hits")

    /** Uses the public charts page: finds the country/global chart playlist, then browses its songs. */
    private suspend fun loadChart(country: String, gl: String, fallbackQuery: String): HomeSection {
        val cards = runCatching { container.youtube.browseCards(CHARTS_BROWSE_ID) }.getOrDefault(emptyList())
        val chart = cards.firstOrNull { it.browseId?.startsWith("VL") == true && it.title.contains(country, true) }
        val tracks = chart?.browseId?.let { id ->
            runCatching { container.youtube.browseSongs(id) }.getOrDefault(emptyList()).map { it.toTrack() }
        }.orEmpty()
        if (tracks.isNotEmpty()) return HomeSection(tracks = tracks.take(HOME_ROW_SIZE))
        val fallback = runCatching { container.youtube.search(fallbackQuery, gl) }.getOrDefault(emptyList()).map { it.toTrack() }
        return HomeSection(tracks = fallback.take(HOME_ROW_SIZE))
    }

    private suspend fun loadMadeForYou(): HomeSection {
        val out = LinkedHashMap<String, Track>()
        for (seed in recommendationSeeds().take(4)) {
            runCatching { container.youtube.search(seed) }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
            if (out.size >= HOME_ROW_SIZE) break
        }
        if (out.isEmpty()) out.putAll(fallbackPopular())
        return HomeSection(tracks = out.values.take(HOME_ROW_SIZE))
    }

    /** Local-only: the most recent YouTube plays, so it renders instantly with no network call. */
    private fun loadContinueListening(): HomeSection {
        val tracks = youtubeRecents.value.filter { it.isRemote }.take(HOME_ROW_SIZE)
        return HomeSection(tracks = tracks)
    }

    private suspend fun loadNewReleases(): HomeSection {
        val out = LinkedHashMap<String, Track>()
        runCatching { container.youtube.search("new music releases", "US") }.getOrDefault(emptyList())
            .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
        return HomeSection(tracks = out.values.take(HOME_ROW_SIZE))
    }

    private suspend fun loadPopularArtists(): HomeSection {
        val cards = runCatching { container.youtube.browseCards(CHARTS_BROWSE_ID) }.getOrDefault(emptyList())
        val artists = cards
            .filter { it.videoId == null && it.browseId?.startsWith("UC") == true }
            .distinctBy { it.browseId }
            .take(ARTIST_ROW_SIZE)
        if (artists.isNotEmpty()) return HomeSection(cards = artists)
        val fallback = recommendationSeeds().take(ARTIST_ROW_SIZE).map { YtCard(it, "Artist", null) }
        return HomeSection(cards = fallback)
    }

    private suspend fun loadDailyMix(): HomeSection {
        val out = LinkedHashMap<String, Track>()
        frequentlyPlayedVideoIds().take(2).forEach { id ->
            runCatching { container.youtube.radio(id) }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
        }
        for (seed in recommendationSeeds().take(3)) {
            runCatching { container.youtube.search("$seed mix") }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
            if (out.size >= HOME_ROW_SIZE) break
        }
        if (out.isEmpty()) out.putAll(fallbackPopular())
        val seedVideo = frequentlyPlayedVideoIds().firstOrNull()
        return HomeSection(tracks = out.values.take(HOME_ROW_SIZE), seedVideoId = seedVideo, hasMore = seedVideo != null)
    }

    private suspend fun loadBecauseYouListened(): HomeSection {
        val last = youtubeHistory.value.firstOrNull { it.videoId.isNotBlank() } ?: return HomeSection()
        val tracks = runCatching { container.youtube.radio(last.videoId) }.getOrDefault(emptyList())
            .filter { it.videoId != last.videoId }
            .map { it.toTrack() }
        return HomeSection(
            tracks = tracks.take(HOME_ROW_SIZE),
            subtitle = "Because you listened to ${last.title}",
            seedVideoId = last.videoId,
            hasMore = tracks.isNotEmpty(),
        )
    }

    private suspend fun loadDiscoverWeekly(): HomeSection {
        val recentArtists = youtubeRecents.value.map { it.artist }.toSet()
        val all = recommendationSeeds()
        val seeds = all.filterNot { it in recentArtists }.ifEmpty { all }
        val out = LinkedHashMap<String, Track>()
        for (seed in seeds.take(5)) {
            runCatching { container.youtube.search("$seed mix") }.getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
            if (out.size >= HOME_ROW_SIZE) break
        }
        if (out.isEmpty()) out.putAll(fallbackPopular())
        return HomeSection(tracks = out.values.take(HOME_ROW_SIZE))
    }

    private suspend fun loadMoodQuery(query: String): HomeSection {
        val tracks = runCatching { container.youtube.search(query, "IN") }.getOrDefault(emptyList()).map { it.toTrack() }
        return HomeSection(tracks = tracks.take(HOME_ROW_SIZE))
    }

    private suspend fun fallbackPopular(): Map<String, Track> {
        val out = LinkedHashMap<String, Track>()
        runCatching { container.youtube.search("popular songs", "US") }.getOrDefault(emptyList())
            .forEach { out.putIfAbsent(it.videoId, it.toTrack()) }
        return out
    }

    /** Top artists/genres across history (weighted by play count), favourites, library and search. */
    private fun recommendationSeeds(): List<String> {
        val counts = LinkedHashMap<String, Int>()
        fun add(value: String?, weight: Int) {
            val clean = value?.trim().orEmpty()
            if (clean.isBlank() || clean.equals("Unknown artist", ignoreCase = true)) return
            counts[clean] = (counts[clean] ?: 0) + weight
        }
        // Search history is the strongest explicit signal.
        searchHistory.value.forEach { add(it, 6) }
        val favIds = favorites.value
        tracks.value.filter { it.id in favIds }.forEach { add(it.artist, 5) }
        // Frequently + recently played YouTube songs (history carries play counts).
        youtubeHistory.value.forEach { add(it.artist, 3 + it.playCount.coerceAtMost(5)) }
        youtubeRecents.value.forEach { add(it.artist, 3) }
        // Whole-library artists, weakly.
        tracks.value.take(300).forEach { add(it.artist, 1) }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun recentVideoIds(): List<String> =
        youtubeHistory.value.map { it.videoId }.filter { it.isNotBlank() }.distinct()

    private fun frequentlyPlayedVideoIds(): List<String> =
        youtubeHistory.value.sortedByDescending { it.playCount }
            .map { it.videoId }.filter { it.isNotBlank() }.distinct()

    private data class HomeSectionSpec(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val load: suspend () -> HomeSection,
    )

    private data class HomeSection(
        val tracks: List<Track> = emptyList(),
        val cards: List<YtCard> = emptyList(),
        val subtitle: String? = null,
        val seedVideoId: String? = null,
        val hasMore: Boolean = false,
    )

    /**
     * Offers the YouTube Music hand-off once, right after a Clerk sign-in: turns the integration
     * switch on and shows the prompt. Skipped when Clerk is not configured, when YouTube is already
     * signed in, when it was dismissed for good, or when the integration is already on.
     */
    private fun maybeOfferYouTubeConnect() {
        if (!_auth.value.configured) return
        if (container.youtubeAuth.snapshot()?.signedIn == true) return
        if (settings.value.youtubeConnectPromptDismissed) return
        if (settings.value.youtubeIntegration) return
        _youtubeConnectEmail.value = _auth.value.account?.email?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            runCatching { container.settings.update { it.copy(youtubeIntegration = true) } }
                .onFailure { report("Couldn't turn on YouTube Music", it) }
        }
        _youtubeConnectPrompt.value = true
    }

    /** Dismisses the hand-off prompt; [dontAskAgain] remembers the choice across launches. */
    fun dismissYouTubeConnectPrompt(dontAskAgain: Boolean) {
        _youtubeConnectPrompt.value = false
        if (dontAskAgain) {
            viewModelScope.launch {
                runCatching { container.settings.update { it.copy(youtubeConnectPromptDismissed = true) } }
                    .onFailure { report("Couldn't save that preference", it) }
            }
        }
    }

    fun refreshYouTubeAccount() {
        val session = container.youtubeAuth.snapshot() ?: return
        viewModelScope.launch {
            runCatching { container.youtube.accountInfo() }
                .onSuccess { info -> if (info != null) container.youtubeAuth.updateProfile(info.first, info.second) }
                .onFailure { AppLog.w("YouTube", "Account lookup skipped", it) }
        }
    }

    /** Called by the login WebView once SAPISID/VISITOR_DATA/DATASYNC_ID were read from music.youtube.com. */
    fun saveYouTubeSession(cookie: String, visitorData: String, dataSyncId: String) {
        container.youtubeAuth.save(YouTubeAuth(cookie = cookie, visitorData = visitorData, dataSyncId = dataSyncId))
        container.youtube.invalidateHome()
        refreshYouTubeAccount()
        refreshYouTubeHome()
        refreshYouTubeLibrary()
        _messages.trySend("Signed in to YouTube Music")
        // Any playlist edits made while signed out/failed are now retryable.
        flushYouTubeSync()
    }

    fun signOutYouTube() {
        container.youtubeAuth.clear()
        container.youtube.invalidateHome()
        _home.value = HomeUiState()
        viewModelScope.launch { runCatching { container.database.youtubeDao().clearLikes() } }
        _messages.trySend("Signed out of YouTube Music")
    }

    fun playYouTubeCard(card: com.auralis.app.data.youtube.YtCard) {
        val videoId = card.videoId ?: return
        val song = YtSong(
            videoId = videoId,
            title = card.title,
            artist = card.subtitle,
            album = "",
            durationMs = card.durationMs,
            thumbnailUrl = card.thumbnailUrl,
        )
        val track = song.toTrack()
        _remoteTracks.update { known -> known + (track.id to track) }
        play(track, listOf(track))
    }

    /** Instant Room mirror + best-effort account call; local-only when signed out. */
    fun toggleYouTubeLike(track: Track) {
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val wasLiked = youtubeLikedIds.value.contains(videoId)
        viewModelScope.launch {
            runCatching {
                if (wasLiked) container.database.youtubeDao().deleteLike(videoId)
                else container.database.youtubeDao().upsertLike(track.toYouTubeLikeEntity())
            }.onFailure { report("Couldn't update likes", it) }
            if (settings.value.youtubeSyncLikes && container.youtubeAuth.snapshot()?.signedIn == true) {
                runCatching { container.youtube.setLiked(videoId, !wasLiked) }
                    .onFailure { AppLog.w("YouTube", "Like sync skipped", it) }
            }
        }
    }

    fun playYouTubeTrack(track: Track) {
        _remoteTracks.update { known -> known + (track.id to track) }
        play(track, listOf(track))
    }

    fun downloadTrack(track: Track) {
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        container.downloads.enqueue(track)
        _messages.trySend("Downloading “${track.title}”")
    }

    fun deleteDownload(track: Track) {
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        viewModelScope.launch { runCatching { container.downloads.delete(videoId) }.onFailure { report("Couldn't delete the download", it) } }
    }

    fun cancelDownload(track: Track) {
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        container.downloads.cancel(videoId)
    }

    /** Replays a History entry, resolving its stream on demand. */
    fun playHistoryEntry(entry: com.auralis.app.data.db.YouTubeRecentEntity) {
        playYouTubeTrack(entry.toDomain())
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { container.database.youtubeDao().clearRecents() }
                .onFailure { report("Couldn't clear history", it) }
        }
    }

    fun loadLikedSongs() {
        viewModelScope.launch {
            runCatching { container.youtube.likedSongs() }
                .onSuccess { songs ->
                    val entries = songs.map { it.ytSongToLikeEntity() }
                    runCatching { entries.forEach { container.database.youtubeDao().upsertLike(it) } }
                    _messages.trySend("Synced ${entries.size} liked songs")
                }
                .onFailure { report("Couldn't load liked songs", it) }
        }
    }

    fun checkForUpdates() = container.updates.checkNow()
    fun dismissUpdate() = container.updates.later()
    fun skipUpdateVersion() = container.updates.skipVersion()
    fun downloadUpdate() = container.updates.downloadUpdate()
    fun cancelUpdateDownload() = container.updates.cancelDownload()
    fun installUpdate() = container.updates.installDownloaded()
    fun openUpdatePage() = container.updates.openReleasePage()
    /** Called from MainActivity.onResume to finish an install after the permission screen. */
    fun onAppResumed() {
        container.updates.onAppForeground()
        // Coming back to the foreground is a good moment to push any queued playlist changes.
        flushYouTubeSync()
    }

    fun addFolder(uri: Uri) { viewModelScope.launch {
        container.sources.addTreeSource(uri).onSuccess { _messages.trySend("Added “${it.name}”"); scanLibrary() }
            .onFailure { report("Couldn't access that folder", it) }
    } }
    fun removeSource(source: MusicSource) { viewModelScope.launch {
        runCatching { container.sources.removeSource(source) }
            .onSuccess { _messages.trySend("Removed “${source.name}” from your library"); scanLibrary() }
            .onFailure { report("Couldn't remove the folder", it) }
    } }
    fun scanLibrary() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val result = container.scanner.scanAll()
                val note = if (result.failedSources > 0) " (${result.failedSources} source(s) could not be read)" else ""
                _messages.trySend("Indexed ${result.tracks} tracks$note")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("Scan failed", e) }
            finally { _scanning.value = false }
        }
    }

    /** Lets the auto-scanner attach its MediaStore observer right after the user grants audio access. */
    fun onLibraryPermissionGranted() = container.autoScanner.onPermissionGranted()

    fun play(track: Track, list: List<Track> = tracks.value) {
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        container.playback.play(list, index)
    }

    /**
     * YouTube playback: resolve only the tapped song, then hand the URL to the existing Media3
     * queue as a single remote item. Nothing is resolved for the rest of the result list.
     */
    /**
     * A search-row tap replaces the queue immediately with the tapped song. The metadata (title,
     * artist, artwork URI) is already on the MediaItem, so the player, dock and notification update
     * in the same frame; PlaybackService resolves the signed URL on its background loader thread.
     */
    fun playYouTube(track: Track) {
        val videoId = track.remoteId ?: return
        val startedAt = System.currentTimeMillis()
        tapStartedAt = startedAt
        tapTrackId = track.id
        _remoteTracks.update { it + (track.id to track) }

        remoteQueueEpoch++
        val epoch = remoteQueueEpoch
        // The first automix fetch is the seeding call; do not let the refill watcher duplicate it.
        refillJob?.cancel()
        radioSeedingTrackId = track.id
        container.playback.play(listOf(track), 0)
        _youtubeResolvingId.value = track.id

        // Warm the exact URL the player will open; the resolver de-duplicates the in-flight work.
        viewModelScope.launch {
            try {
                container.youtube.resolveStream(videoId)
                AppLog.i("Timing", "tap -> URL ${System.currentTimeMillis() - startedAt} ms")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.e("YouTube", "Could not resolve the tapped song", error)
                _messages.trySend("Couldn't play this YouTube song: ${error.localizedMessage ?: "stream unavailable"}")
            } finally {
                if (_youtubeResolvingId.value == track.id) _youtubeResolvingId.value = null
            }
        }

        // The InnerTube "next" automix supplies 10-15 similar tracks after the current one.
        viewModelScope.launch {
            try {
                val radio = container.youtube.radio(videoId)
                if (epoch != remoteQueueEpoch) return@launch
                val additions = radio.asSequence()
                    .filter { it.videoId != videoId }
                    .map { it.toTrack() }
                    .distinctBy { it.id }
                    .take(RADIO_INITIAL_SIZE)
                    .toList()
                if (additions.isEmpty()) return@launch
                _remoteTracks.update { known -> known + additions.associateBy { it.id } }
                container.playback.addToQueue(additions)
                AppLog.i("YouTube", "Queued ${additions.size} radio tracks")
                prefetchRemoteStream(additions.firstOrNull()?.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.w("YouTube", "Radio lookup failed", error)
            } finally {
                if (radioSeedingTrackId == track.id) radioSeedingTrackId = null
            }
        }
    }

    /** Feeds the 300 ms debounced search pipeline; a retry re-triggers the same literal query. */
    fun searchYouTube(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            youtubeQuery.value = ""
            _youtubeSearch.value = YouTubeSearchUiState()
            return
        }
        if (youtubeQuery.value == trimmed) {
            youtubeRequest.update { it + 1 }
        } else {
            youtubeQuery.value = trimmed
        }
    }

    /** Tapping a taste chip on Home: run the search and jump straight to the Explore/Search tab. */
    fun searchFromHome(query: String) {
        if (query.isBlank()) return
        searchYouTube(query)
        selectTab(Tab.SEARCH)
    }

    /** Persists a successful search term; the newest 20 feed the Home recommendation seeds. */
    private fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        viewModelScope.launch {
            runCatching {
                container.database.searchHistoryDao().upsert(SearchHistoryEntity(trimmed, System.currentTimeMillis()))
            }.onFailure { AppLog.w("YouTube", "Search history not saved", it) }
        }
    }

    /** Resolve the first results in the background so the first tap usually hits a warm URL. */
    private fun prefetchSearchResults(results: List<Track>) {        searchPrefetchJob?.cancel()
        searchPrefetchJob = viewModelScope.launch {
            results.take(SEARCH_PREFETCH_COUNT).forEach { track ->
                val videoId = track.remoteId ?: return@forEach
                if (!prefetchedStreamIds.add(track.id)) return@forEach
                runCatching { container.youtube.resolveStream(videoId) }
            }
        }
    }

    /**
     * Resolves a circular artist photo (Part L). [knownUrl] is used directly when the caller already
     * has one; otherwise the browse id or a close Deezer name match is looked up off the main thread.
     * Results (and 7-day negatives) are cached in Room so lists never block while scrolling.
     */
    suspend fun resolveArtistImage(name: String, browseId: String?, knownUrl: String?): String? =
        runCatching { container.artistImages.resolve(name, browseId, knownUrl) }
            .onFailure { AppLog.w("ArtistPhotos", "Couldn't resolve a photo for $name", it) }
            .getOrNull()

    /** Upgrades a YouTube video thumbnail to real square album art; updates the UI and notification. */
    private fun ensureArtwork(trackId: String?) {
        if (trackId == null) return
        val track = _remoteTracks.value[trackId] ?: return
        // Real art already present (square googleusercontent or a metadata cover): nothing to do.
        if (!track.artworkUrl.isNullOrBlank() && !com.auralis.app.data.youtube.isVideoThumbnailUrl(track.artworkUrl)) return
        if (!artworkResolved.add(trackId)) return
        if (artworkResolved.size > ARTWORK_MEMORY_LIMIT) artworkResolved.clear()
        viewModelScope.launch {
            val url = runCatching { container.artworkResolver.resolve(track) }.getOrNull() ?: return@launch
            _remoteTracks.update { known ->
                val current = known[trackId] ?: return@update known
                known + (trackId to current.copy(artworkUrl = url))
            }
            container.playback.updateArtwork(trackId, url)
        }
    }

    private fun prefetchRemoteStream(trackId: String?) {
        if (trackId == null) return
        val track = _remoteTracks.value[trackId] ?: return
        val videoId = track.remoteId ?: return
        if (!prefetchedStreamIds.add(trackId)) return
        if (prefetchedStreamIds.size > PREFETCH_MEMORY_LIMIT) prefetchedStreamIds.clear()
        // Data saver stops prefetching on cellular; the stream still resolves just in time.
        val current = settings.value
        if (!com.auralis.app.data.network.NetworkQuality.allowPrefetch(container.appContext, current)) return
        val quality = com.auralis.app.data.network.NetworkQuality.effective(container.appContext, current)
        container.youtube.prefetchStream(videoId, quality)
    }

    fun enqueue(track: Track) = enqueueAll(listOf(track))
    fun playNext(track: Track) { container.playback.playNext(track) }
    fun enqueueAll(items: List<Track>) {
        container.playback.addToQueue(items)
        persistCurrentQueue()
    }
    fun removeQueueItem(trackId: String) { container.playback.removeFromQueue(trackId); persistCurrentQueue() }
    fun moveQueueItem(trackId: String, toIndex: Int) { container.playback.moveQueueItem(trackId, toIndex); persistCurrentQueue() }
    fun playQueueItem(trackId: String) { container.playback.playQueueItem(trackId) }
    fun clearQueue() {
        container.playback.clearQueue()
        viewModelScope.launch { runCatching { container.database.queueDao().clear() }.onFailure { report("Couldn't clear the saved queue", it) } }
    }
    /** Unified playlist entries: on-device tracks and streamed YouTube songs, in order. */
    fun playlistItems(playlistId: Long): List<PlaylistItem> {
        val byId = tracks.value.associateBy { it.id }
        val local = playlistEntries.value.filter { it.playlistId == playlistId }
            .sortedBy { it.position }
            .mapNotNull { entry -> byId[entry.trackId]?.let { PlaylistItem(entry.entryId, false, it, entry.position) } }
        val remote = playlistYtEntries.value.filter { it.playlistId == playlistId }
            .sortedBy { it.position }
            .map { entry -> PlaylistItem(entry.entryId, true, entry.toDomain(), entry.position) }
        return (local + remote).sortedBy { it.position }
    }

    fun enqueuePlaylist(playlistId: Long) {
        runCatching {
            val ordered = playlistItems(playlistId).map { it.track }
            if (ordered.isEmpty()) return@runCatching
            _remoteTracks.update { known -> known + ordered.filter { it.isRemote }.associateBy { it.id } }
            enqueueAll(ordered)
        }.onFailure { report("Couldn't add this playlist to the queue", it) }
    }

    fun playPlaylist(playlistId: Long, startIndex: Int = 0) {
        val ordered = playlistItems(playlistId).map { it.track }
        if (ordered.isEmpty()) return
        _remoteTracks.update { known -> known + ordered.filter { it.isRemote }.associateBy { it.id } }
        play(ordered[startIndex.coerceIn(0, ordered.lastIndex)], ordered)
    }
    fun toggleFavorite(track: Track) {
        if (track.isRemote) { _messages.trySend("Favorites are for library songs only"); return }
        viewModelScope.launch { runCatching { container.database.favoriteDao().toggle(track.id, System.currentTimeMillis()) }.onFailure { report("Couldn't update favorite", it) } }
    }
    fun createPlaylist(name: String) { viewModelScope.launch {
        val clean = name.trim()
        if (clean.isBlank()) return@launch
        val now = System.currentTimeMillis()
        val localId = runCatching {
            container.database.playlistDao().insert(PlaylistEntity(name = clean, createdAt = now, updatedAt = now))
        }.onSuccess { _messages.trySend("Playlist created") }
            .onFailure { report("Couldn't create playlist", it) }
            .getOrNull() ?: return@launch
        syncPlaylistCreate(localId, clean)
    } }
    fun renamePlaylist(playlistId: Long, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            runCatching { container.database.playlistDao().rename(playlistId, clean, System.currentTimeMillis()) }
                .onFailure { report("Couldn't rename playlist", it) }
            syncPlaylistRename(playlistId, clean)
        }
    }
    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val remote = runCatching { container.database.playlistDao().remoteIdOf(playlistId) }.getOrNull()
            runCatching { container.database.playlistDao().delete(playlistId) }
                .onSuccess { _messages.trySend("Playlist deleted") }.onFailure { report("Couldn't delete playlist", it) }
            if (!remote.isNullOrBlank() && settings.value.youtubeSyncPlaylists && youTubeSignedIn()) {
                val ok = runCatching { container.youtube.deletePlaylist(remote) }.getOrDefault(false)
                if (!ok) enqueueSync(kind = "DELETE", playlistId = 0L, remoteId = remote, videoId = null, title = null)
            }
        }
    }
    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { runCatching {
            val dao=container.database.playlistDao()
            val position = maxOf(dao.maxPosition(playlistId), dao.maxYtPosition(playlistId)) + 1
            if (track.isRemote) {
                val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
                if (videoId.isBlank()) return@runCatching
                dao.insertYtEntries(listOf(PlaylistYtEntryEntity(playlistId=playlistId, videoId=videoId, title=track.title, artist=track.artist, album=track.album, durationMs=track.durationMs, thumbnailUrl=track.artworkUrl, position=position)))
            } else {
                dao.insertEntries(listOf(PlaylistEntryEntity(playlistId=playlistId,trackId=track.id,position=position)))
            }
            dao.touch(playlistId,System.currentTimeMillis())
        }.onSuccess { _messages.trySend("Added to playlist") }.onFailure { report("Couldn't add song to playlist", it) }
            // Only streamed songs exist on YouTube; local-only songs are skipped.
            if (track.isRemote) syncPlaylistAdd(playlistId, track)
        }
    }
    fun removePlaylistItem(playlistId: Long, item: PlaylistItem) {
        viewModelScope.launch { runCatching {
            val dao = container.database.playlistDao()
            if (item.remote) {
                val videoId = item.track.remoteId ?: item.track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
                dao.removeYtEntry(playlistId, videoId)
            } else {
                dao.removeEntry(playlistId, item.track.id)
            }
            dao.touch(playlistId, System.currentTimeMillis())
        }.onFailure { report("Couldn't remove song from playlist", it) }
            if (item.remote) syncPlaylistRemove(playlistId, item)
        }
    }
    /** Moves one entry by [delta] slots (+1 down, -1 up) and rewrites both tables' positions. */
    fun movePlaylistItem(playlistId: Long, item: PlaylistItem, delta: Int) {
        val current = playlistItems(playlistId)
        val index = current.indexOfFirst { it.entryId == item.entryId && it.remote == item.remote }
        val target = index + delta
        if (index < 0 || target !in current.indices) return
        val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
        viewModelScope.launch { runCatching {
            val dao = container.database.playlistDao()
            reordered.forEachIndexed { position, entry ->
                if (entry.remote) dao.setYtPosition(entry.entryId, position) else dao.setPosition(entry.entryId, position)
            }
        }.onFailure { report("Couldn't reorder the playlist", it) } }
    }

    // ---- YouTube Music account sync (Part E) ------------------------------------------------

    private fun youTubeSignedIn(): Boolean = container.youtubeAuth.snapshot()?.signedIn == true

    /**
     * Mirrors a freshly created local playlist to the account. Only does work when playlist sync is
     * enabled and a YouTube session exists; a transient failure is queued for [flushYouTubeSync].
     */
    private suspend fun syncPlaylistCreate(localId: Long, title: String) {
        if (!settings.value.youtubeSyncPlaylists || !youTubeSignedIn()) return
        val remote = runCatching { container.youtube.createPlaylist(title) }.getOrNull()
        if (!remote.isNullOrBlank()) {
            runCatching { container.database.playlistDao().setRemoteId(localId, remote) }
                .onFailure { AppLog.w("YouTube", "Couldn't store the remote playlist id", it) }
        } else {
            enqueueSync("CREATE", localId, null, null, title)
        }
    }

    private suspend fun syncPlaylistRename(playlistId: Long, title: String) {
        if (!settings.value.youtubeSyncPlaylists || !youTubeSignedIn()) return
        val remote = runCatching { container.database.playlistDao().remoteIdOf(playlistId) }.getOrNull() ?: return
        if (remote.isBlank()) return
        val ok = runCatching { container.youtube.renamePlaylist(remote, title) }.getOrDefault(false)
        if (!ok) enqueueSync("RENAME", playlistId, remote, null, title)
    }

    private suspend fun syncPlaylistAdd(playlistId: Long, track: Track) {
        if (!settings.value.youtubeSyncPlaylists || !youTubeSignedIn()) return
        val remote = runCatching { container.database.playlistDao().remoteIdOf(playlistId) }.getOrNull() ?: return
        if (remote.isBlank()) return
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val ok = runCatching { container.youtube.addPlaylistVideo(remote, videoId) }.getOrDefault(false)
        if (!ok) enqueueSync("ADD_VIDEO", playlistId, remote, videoId, null)
    }

    private suspend fun syncPlaylistRemove(playlistId: Long, item: PlaylistItem) {
        if (!settings.value.youtubeSyncPlaylists || !youTubeSignedIn()) return
        val remote = runCatching { container.database.playlistDao().remoteIdOf(playlistId) }.getOrNull() ?: return
        if (remote.isBlank()) return
        val videoId = item.track.remoteId ?: item.track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val ok = runCatching { container.youtube.removePlaylistVideo(remote, videoId) }.getOrDefault(false)
        if (!ok) enqueueSync("REMOVE_VIDEO", playlistId, remote, videoId, null)
    }

    private fun enqueueSync(kind: String, playlistId: Long, remoteId: String?, videoId: String?, title: String?) {
        viewModelScope.launch {
            runCatching {
                container.database.youtubeSyncDao().insert(
                    YouTubeSyncOpEntity(
                        kind = kind,
                        playlistId = playlistId,
                        remoteId = remoteId,
                        videoId = videoId,
                        title = title,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { AppLog.w("YouTube", "Couldn't queue a playlist change", it) }
        }
    }

    /**
     * Replays queued playlist changes in order. Stops at the first still-failing op so ordering is
     * preserved. Safe to call often (sign-in, resume, after an edit); a re-entrancy guard keeps it
     * to a single run at a time.
     */
    fun flushYouTubeSync() {
        if (syncFlushing) return
        if (!settings.value.youtubeSyncPlaylists || !youTubeSignedIn()) return
        syncFlushing = true
        viewModelScope.launch {
            try {
                val dao = container.database.youtubeSyncDao()
                val ops = runCatching { dao.getAll() }.getOrDefault(emptyList())
                for (op in ops) {
                    val done = runCatching { applySyncOp(op) }.getOrDefault(false)
                    if (!done) break
                    runCatching { dao.delete(op.id) }
                }
            } finally {
                syncFlushing = false
            }
        }
    }

    /** Applies one queued op. Returns false when it failed and should stay queued for a retry. */
    private suspend fun applySyncOp(op: YouTubeSyncOpEntity): Boolean {
        val playlists = container.database.playlistDao()
        return when (op.kind) {
            "CREATE" -> {
                val title = op.title ?: return true
                val remote = container.youtube.createPlaylist(title) ?: return false
                playlists.setRemoteId(op.playlistId, remote)
                true
            }
            "RENAME" -> {
                val remote = op.remoteId ?: playlists.remoteIdOf(op.playlistId) ?: return true
                val title = op.title ?: return true
                container.youtube.renamePlaylist(remote, title)
            }
            "DELETE" -> {
                val remote = op.remoteId ?: return true
                container.youtube.deletePlaylist(remote)
            }
            "ADD_VIDEO" -> {
                val remote = op.remoteId ?: playlists.remoteIdOf(op.playlistId) ?: return true
                val videoId = op.videoId ?: return true
                container.youtube.addPlaylistVideo(remote, videoId)
            }
            "REMOVE_VIDEO" -> {
                val remote = op.remoteId ?: playlists.remoteIdOf(op.playlistId) ?: return true
                val videoId = op.videoId ?: return true
                container.youtube.removePlaylistVideo(remote, videoId)
            }
            else -> true
        }
    }

    // ---- Spotify (Exportify) CSV import ----

    fun observeImportReview(importId: Long): Flow<List<SpotifyImportItemEntity>> =
        container.database.spotifyDao().observeNeedsReview(importId)

    /** Copies each picked CSV into app storage, creates a playlist and starts a background import. */
    fun importSpotify(uris: List<Uri>) {
        if (!settings.value.youtubeIntegration) {
            _messages.trySend("Turn on the YouTube integration to import Spotify playlists")
            return
        }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                runCatching {
                    val name = queryDisplayName(uri)
                    val now = System.currentTimeMillis()
                    val playlistId = container.database.playlistDao()
                        .insert(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
                    val importId = container.database.spotifyDao().insertImport(
                        SpotifyImportEntity(
                            fileName = "$name.csv",
                            createdAt = now,
                            state = "RUNNING",
                            total = 0,
                            processed = 0,
                            matched = 0,
                            review = 0,
                            failed = 0,
                            playlistId = playlistId,
                        ),
                    )
                    val path = withContext(Dispatchers.IO) {
                        val dir = java.io.File(container.appContext.filesDir, "spotify_import").apply { mkdirs() }
                        val target = java.io.File(dir, "$importId.csv")
                        container.appContext.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        target.absolutePath
                    }
                    container.spotifyImport.enqueue(importId, playlistId, path, name)
                }.onFailure { report("Couldn't start the Spotify import", it) }
            }
            _messages.trySend("Importing Spotify playlist…")
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val name = runCatching {
            container.appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return (name ?: uri.lastPathSegment ?: "Spotify playlist").removeSuffix(".csv").ifBlank { "Spotify playlist" }
    }

    fun decodeCandidates(item: SpotifyImportItemEntity): List<SpotifyCandidate> {
        val json = item.candidates ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SpotifyCandidate(
                    videoId = o.optString("videoId"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("durationMs"),
                    thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                    score = o.optDouble("score", 0.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Picks a candidate for a review item (or skips it when [candidate] is null) and fills the playlist. */
    fun resolveImportItem(importId: Long, itemId: Long, candidate: SpotifyCandidate?) {
        viewModelScope.launch {
            runCatching {
                val dao = container.database.spotifyDao()
                if (candidate == null) {
                    dao.resolveItem(itemId, "SKIPPED", null, null, null)
                    return@runCatching
                }
                dao.resolveItem(itemId, "MATCHED", candidate.videoId, candidate.title, candidate.artist)
                val playlistId = dao.getImport(importId)?.playlistId
                if (playlistId != null) {
                    val pd = container.database.playlistDao()
                    val position = maxOf(pd.maxPosition(playlistId), pd.maxYtPosition(playlistId)) + 1
                    pd.insertYtEntries(
                        listOf(
                            PlaylistYtEntryEntity(
                                playlistId = playlistId,
                                videoId = candidate.videoId,
                                title = candidate.title,
                                artist = candidate.artist,
                                album = candidate.album,
                                durationMs = candidate.durationMs,
                                thumbnailUrl = candidate.thumbnailUrl,
                                position = position,
                            ),
                        ),
                    )
                    pd.touch(playlistId, System.currentTimeMillis())
                }
            }.onFailure { report("Couldn't update this import item", it) }
        }
    }

    /** Re-runs the YouTube Music search for one review item and refreshes its candidates. */
    fun retryImportItem(importId: Long, item: SpotifyImportItemEntity) {
        viewModelScope.launch {
            runCatching {
                val dao = container.database.spotifyDao()
                val row = com.auralis.app.data.spotify.SpotifyRow(item.title, item.artist, item.album, item.durationMs)
                val results = container.youtube.search(com.auralis.app.data.spotify.SpotifyMatcher.query(row))
                val candidates = com.auralis.app.data.spotify.SpotifyMatcher.candidates(row, results)
                val best = candidates.firstOrNull()
                val status = when {
                    best == null -> "FAILED"
                    best.confident -> "MATCHED"
                    else -> "REVIEW"
                }
                if (status == "MATCHED" && best != null) {
                    resolveImportItem(
                        importId,
                        item.id,
                        SpotifyCandidate(
                            best.song.videoId,
                            best.song.title,
                            best.song.artist,
                            best.song.album,
                            best.song.durationMs,
                            best.song.thumbnailUrl,
                            best.score,
                        ),
                    )
                } else {
                    val json = org.json.JSONArray().apply {
                        candidates.forEach { c ->
                            put(
                                org.json.JSONObject().apply {
                                    put("videoId", c.song.videoId)
                                    put("title", c.song.title)
                                    put("artist", c.song.artist)
                                    put("album", c.song.album)
                                    put("durationMs", c.song.durationMs)
                                    put("thumbnailUrl", c.song.thumbnailUrl ?: "")
                                    put("score", c.score)
                                },
                            )
                        }
                    }.toString()
                    dao.upsertItem(item.copy(status = status, candidates = json))
                }
            }.onFailure { report("Couldn't retry this match", it) }
        }
    }

    fun togglePlayback() = container.playback.toggle()
    fun next() = container.playback.next()
    fun previous() = container.playback.previous()
    fun seekTo(positionMs: Long) = container.playback.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = container.playback.seekBy(deltaMs)
    fun toggleShuffle() = container.playback.toggleShuffle()
    fun cycleRepeat() = container.playback.cycleRepeat()
    fun startSleepTimer(minutes: Int) = container.playback.startSleepTimer(minutes)
    fun cancelSleepTimer() = container.playback.cancelSleepTimer()

    fun loadLyrics(track: Track) {
        // Already resolved (or still fetching) for this song: nothing to do.
        if (loadedLyricsTrackId == track.id) return
        // A new song cancels the previous lookup; slow late responses are also dropped by the
        // track-id check below, so lyrics can never land on the wrong song.
        lyricsLoadJob?.cancel()
        // Reset immediately so a previous song's offset never leaks on while the saved value loads.
        _lyricsOffsetMs.value = 0
        viewModelScope.launch {
            val saved = runCatching { container.lyrics.offsetMs(track.id) }.getOrDefault(0)
            if (loadedLyricsTrackId == track.id) _lyricsOffsetMs.value = saved
        }
        // Prefetched or previously visited track: show the fully parsed result in one frame.
        container.lyrics.cached(track.id)?.let { cached ->
            loadedLyricsTrackId = track.id
            _lyrics.value = cached
            _lyricsLoading.value = false
            return
        }
        loadedLyricsTrackId = track.id
        _lyrics.value = null
        _lyricsLoading.value = true
        lyricsLoadJob = viewModelScope.launch {
            val result = try {
                // Cached lyrics first, then embedded/sidecar, then a live online lookup. Providers
                // are queried in parallel (Part Q); each intermediate winner is shown as it arrives.
                container.lyrics.load(track, onUpdate = { partial ->
                    if (loadedLyricsTrackId == track.id) {
                        _lyrics.value = partial
                        _lyricsLoading.value = false
                    }
                })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                report("Couldn't load lyrics", error)
                null
            }
            if (loadedLyricsTrackId == track.id) {
                _lyrics.value = result
                _lyricsLoading.value = false
            }
        }
    }

    /** Nudges this song's lyrics timing by [deltaMs] (clamped to ±5 s) and persists it. Part J. */
    fun adjustTrackLyricsOffset(track: Track, deltaMs: Int) {
        val next = (_lyricsOffsetMs.value + deltaMs).coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        _lyricsOffsetMs.value = next
        viewModelScope.launch {
            runCatching { container.lyrics.setOffsetMs(track.id, next) }
                .onFailure { report("Couldn't save the lyrics offset", it) }
        }
    }

    /** Clears this song's lyrics timing offset back to zero. Part J. */
    fun resetTrackLyricsOffset(track: Track) {
        _lyricsOffsetMs.value = 0
        viewModelScope.launch {
            runCatching { container.lyrics.setOffsetMs(track.id, 0) }
                .onFailure { report("Couldn't reset the lyrics offset", it) }
        }
    }

    fun importLyrics(track: Track, uri: Uri) {
        viewModelScope.launch {
            runCatching { container.lyrics.import(track, uri) }
                .onSuccess { lyrics ->
                    lyricsLoadJob?.cancel()
                    _lyrics.value = lyrics
                    loadedLyricsTrackId = track.id
                    _messages.trySend(if (lyrics.synced != null) "Synchronized lyrics imported" else "Lyrics imported (no timestamps found)")
                }
                .onFailure { report("Couldn't import lyrics", it) }
        }
    }

    fun saveLyrics(track: Track, text: String) {
        viewModelScope.launch {
            runCatching { container.lyrics.saveText(track, text, LyricsSource.USER) }
                .onSuccess {
                    lyricsLoadJob?.cancel()
                    _lyrics.value = it
                    loadedLyricsTrackId = track.id
                    _messages.trySend("Lyrics saved")
                }
                .onFailure { report("Couldn't save lyrics", it) }
        }
    }

    fun removeLyrics(track: Track) {
        viewModelScope.launch {
            runCatching { container.lyrics.remove(track, LyricsSource.USER) }
                .onSuccess { removed ->
                    if (removed) {
                        lyricsLoadJob?.cancel()
                        _lyrics.value = null
                        loadedLyricsTrackId = null
                        _messages.trySend("Saved lyrics removed")
                        // Fall back to embedded/sidecar/LRCLIB lyrics now that the manual copy is gone.
                        loadLyrics(track)
                    } else {
                        _messages.trySend("No user lyrics to remove")
                    }
                }
                .onFailure { report("Couldn't remove lyrics", it) }
        }
    }

    /** "Try another source": skip the provider that supplied the current lyrics and re-query. */
    fun tryAnotherLyricsSource(track: Track) {
        viewModelScope.launch {
            _lyricsLoading.value = true
            try {
                val currentProvider = _lyrics.value?.provider
                val result = runCatching { container.lyrics.tryAnother(track, currentProvider) }
                    .onFailure { report("Couldn't fetch lyrics from another source", it) }
                    .getOrNull()
                if (loadedLyricsTrackId != track.id) return@launch
                if (result != null) {
                    _lyrics.value = result
                    val label = result.provider ?: "another source"
                    _messages.trySend("Lyrics from $label")
                } else {
                    _messages.trySend("No other provider has lyrics for this song")
                }
            } finally {
                if (loadedLyricsTrackId == track.id) _lyricsLoading.value = false
            }
        }
    }

    /** Retry button: forces a fresh lookup (forgetting cached provider answers) for this song. */
    fun retryLyrics(track: Track) {
        lyricsLoadJob?.cancel()
        _lyricsOffsetMs.value = 0
        loadedLyricsTrackId = track.id
        _lyrics.value = null
        _lyricsLoading.value = true
        lyricsLoadJob = viewModelScope.launch {
            val result = try {
                container.lyrics.retry(track)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                report("Couldn't load lyrics", error)
                null
            }
            if (loadedLyricsTrackId == track.id) {
                _lyrics.value = result
                _lyricsLoading.value = false
            }
        }
    }

    // ---- Online metadata (Part G) ----------------------------------------------------------

    private val _metadataCandidates = MutableStateFlow<List<com.auralis.app.data.metadata.MetadataCandidate>>(emptyList())
    val metadataCandidates = _metadataCandidates.asStateFlow()
    private val _metadataLoading = MutableStateFlow(false)
    val metadataLoading = _metadataLoading.asStateFlow()

    /** "Fetch metadata": fills the candidate list from the enabled providers. */
    fun fetchMetadata(track: Track) {
        viewModelScope.launch {
            _metadataLoading.value = true
            _metadataCandidates.value = emptyList()
            val results = runCatching {
                container.metadataRepository.search(
                    title = track.title.ifBlank { track.displayName.substringBeforeLast('.') },
                    artist = track.artist,
                    album = track.album.takeIf { it.isNotBlank() },
                    durationMs = track.durationMs.takeIf { it > 0 },
                )
            }.onFailure { report("Metadata lookup failed", it) }.getOrDefault(emptyList())
            _metadataCandidates.value = results
            _metadataLoading.value = false
            if (results.isEmpty()) _messages.trySend("No metadata matches found")
        }
    }

    fun clearMetadataCandidates() {
        _metadataCandidates.value = emptyList()
    }

    /** Persists a chosen candidate for a YouTube song as a Room override, applied to the UI + notification. */
    fun saveRemoteMetadataOverride(track: Track, candidate: com.auralis.app.data.metadata.MetadataCandidate) {
        viewModelScope.launch {
            runCatching {
                val dao = container.database.providerCacheDao()
                dao.upsert(
                    com.auralis.app.data.db.ProviderCacheEntity(
                        kind = KIND_METADATA_OVERRIDE,
                        trackId = track.id,
                        provider = candidate.source,
                        payload = candidate.toJson().toString(),
                        found = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val updated = track.copy(
                    title = candidate.title.ifBlank { track.title },
                    artist = candidate.artists.ifBlank { track.artist },
                    albumArtist = candidate.albumArtist.ifBlank { track.albumArtist },
                    album = candidate.album.ifBlank { track.album },
                    artworkUrl = candidate.coverUrl ?: track.artworkUrl,
                    year = candidate.year ?: track.year,
                )
                _remoteTracks.update { known -> known + (track.id to updated) }
                _messages.trySend("Metadata saved")
            }.onFailure { report("Couldn't save metadata", it) }
        }
    }

    fun clearLyricsMetadataCache() {
        viewModelScope.launch {
            runCatching {
                container.lyrics.clearProviderCache()
                container.database.providerCacheDao().clearKind("metadata")
                container.artistImages.clearAll()
            }.onSuccess { _messages.trySend("Lyrics and metadata cache cleared") }
                .onFailure { report("Couldn't clear the cache", it) }
        }
    }

    /** Reorders one lyrics provider by [delta] positions inside the persisted lookup order. */
    fun moveLyricsProvider(id: String, delta: Int) {
        val order = settings.value.lyricsProviderOrder.toMutableList()
        val index = order.indexOf(id)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, id)
        updateSettings { it.copy(lyricsProviderOrder = order) }
        onLyricsSettingsChanged()
    }

    /** Storage: deletes the app's cache directory (images, logs, temp) and reports it. */
    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(container.appContext.cacheDir, "image_cache"); dir.deleteRecursively()
                val logs = File(container.appContext.cacheDir, "logs"); logs.deleteRecursively()
                // Updates are kept so an in-progress install is not disturbed.
                container.appContext.cacheDir.listFiles()?.forEach { file ->
                    if (file.name !in setOf("updates")) runCatching { file.deleteRecursively() }
                }
            }.onSuccess { _messages.trySend("Cache cleared") }
                .onFailure { report("Couldn't clear the cache", it) }
        }
    }

    /** Storage: removes every downloaded song (file + sidecar + row). */
    fun removeAllDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ids = container.database.downloadDao().allVideoIds()
                val manager = com.auralis.app.data.download.DownloadManager(container.appContext)
                ids.forEach { manager.delete(it) }
            }.onSuccess { _messages.trySend("All downloads removed") }
                .onFailure { report("Couldn't remove downloads", it) }
        }
    }

    /** Called after a lyrics/metadata setting change: forget "none found" markers so it re-queries. */
    fun onLyricsSettingsChanged() {
        viewModelScope.launch { runCatching { container.lyrics.clearNegativeCache() } }
    }

    /** Library "Auto-fix tags": a WorkManager batch that fills empty tag fields from the providers. */
    fun autoFixTags() {
        com.auralis.app.data.metadata.AutoTagManager(container.appContext).start()
        _messages.trySend("Fixing tags in the background")
    }

    fun wrappedStats(year: Int, month: Int?): WrappedStats {
        val events = playEvents.value.map { PlayEvent(it.trackId, it.playedAt, it.durationMs) }
        return ListeningStats.compute(tracks.value, events, year, month)
    }

    suspend fun readTags(track: Track): Result<TagData> = runCatching {
        container.metadata.read(Uri.parse(track.uri), track.displayName).tag
    }

    suspend fun writeTags(track: Track, tag: TagData): Result<Unit> = runCatching {
        container.metadata.write(Uri.parse(track.uri), track.displayName, tag)
        _messages.trySend("Tags saved. Rescanning to refresh the library.")
        scanLibrary()
    }.onFailure { report("Couldn't save tags", it) }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.backup.export(uri) }
                .onSuccess { summary ->
                    _messages.trySend("Backup saved · ${summary.playlists} playlists, ${summary.favorites} favorites, ${summary.lyrics} lyric files")
                }
                .onFailure { report("Backup failed", it) }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.backup.restore(uri) }
                .onSuccess { summary ->
                    _messages.trySend("Restored · ${summary.playlists} playlists, ${summary.favorites} favorites, ${summary.history} history entries")
                }
                .onFailure { report("Restore failed", it) }
        }
    }

    private fun recordPlayEvent(trackId: String) {
        if (trackId == lastRecordedTrackId) return
        lastRecordedTrackId = trackId
        val track = tracks.value.firstOrNull { it.id == trackId } ?: return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                container.database.historyDao().recordPlay(trackId, now)
                container.database.playEventDao().insert(
                    PlayEventEntity(trackId = trackId, playedAt = now, durationMs = track.durationMs.coerceAtLeast(0L)),
                )
            }.onFailure { report("Playback started but statistics could not be saved", it) }
        }
    }

    private fun persistCurrentQueue() {
        val ids = container.playback.state.value.queueIds
        val savable = localQueueIds(ids) ?: return
        viewModelScope.launch { runCatching {
            if (savable.isEmpty()) container.database.queueDao().clear()
            else container.database.queueDao().replaceAll(savable.mapIndexed { i, id -> QueueItemEntity(i, id, i) })
        }.onFailure { report("Couldn't save the playback queue", it) } }
    }

    /**
     * Queue ids that exist in the library. Returns null while the library has not loaded yet so a
     * cold start cannot wipe a valid saved queue. Streamed YouTube ids are always filtered out.
     */
    private fun localQueueIds(ids: List<String>): List<String>? {
        val library = tracks.value
        if (library.isEmpty()) return null
        val local = library.mapTo(HashSet()) { it.id }
        return ids.filter { it in local }
    }

    private fun report(label: String, error: Throwable) {
        if (error is CancellationException) throw error
        android.util.Log.e(TAG, label, error)
        AppLog.e("ViewModel", label, error)
        _messages.trySend("$label: ${error.localizedMessage ?: "unknown error"}")
    }
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
    private companion object {
        const val TAG = "AuralisViewModel"
        const val KIND_METADATA_OVERRIDE = "metadata_override"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val RADIO_INITIAL_SIZE = 15
        const val RADIO_REFILL_SIZE = 12
        const val MIN_RADIO_AHEAD = 3
        const val SEARCH_PREFETCH_COUNT = 3
        const val PREFETCH_MEMORY_LIMIT = 200
        const val ARTWORK_MEMORY_LIMIT = 200
        /** Per-song lyrics timing offset ceiling (Part J). */
        const val MAX_LYRICS_OFFSET_MS = 5_000
        const val HISTORY_MIN_MS = 30_000L
        /** YouTube Music charts landing page (public browse id); powers Trending and Popular Artists. */
        const val CHARTS_BROWSE_ID = "FEmusic_charts"
        const val HOME_ROW_SIZE = 20
        const val ARTIST_ROW_SIZE = 12
        // Stable section ids, kept across refreshes so a row's state updates in place.
        const val SECTION_QUICK_PICKS = "quick_picks"
        const val SECTION_TRENDING_IN = "trending_in"
        const val SECTION_MADE_FOR_YOU = "made_for_you"
        const val SECTION_GLOBAL_HITS = "global_hits"
        const val SECTION_CONTINUE = "continue"
        const val SECTION_NEW_RELEASES = "new_releases"
        const val SECTION_ARTISTS = "artists"
        const val SECTION_DAILY_MIX = "daily_mix"
        const val SECTION_BECAUSE_YOU = "because_you"
        const val SECTION_DISCOVER_WEEKLY = "discover_weekly"
        const val SECTION_CHILL = "chill"
        const val SECTION_WORKOUT = "workout"
        const val SECTION_FOCUS = "focus"
    }
}
