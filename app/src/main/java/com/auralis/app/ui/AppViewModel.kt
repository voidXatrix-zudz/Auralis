package com.auralis.app.ui

import android.net.Uri
import android.os.SystemClock
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
import com.auralis.app.data.youtube.YtPlaylist
import com.auralis.app.data.youtube.YtSong
import com.auralis.app.data.youtube.YouTubeAuth
import com.auralis.app.data.youtube.YouTubeLoadResult
import com.auralis.app.data.youtube.SessionClearRules
import com.auralis.app.data.youtube.SessionHealOutcome
import com.auralis.app.data.youtube.HomeRowStyle
import com.auralis.app.data.youtube.HomeShelfBlock
import com.auralis.app.data.youtube.buildSignedInLayout
import com.auralis.app.data.youtube.classifyShelf
import com.auralis.app.data.youtube.looksLikeMoodShelf
import com.auralis.app.data.youtube.clearYouTubeWebSessionCookies
import com.auralis.app.data.youtube.hasSigningCookie
import com.auralis.app.data.youtube.filterMusicTracksCounting
import com.auralis.app.data.youtube.isMusicCard
import com.auralis.app.data.youtube.isMusicTrack
import com.auralis.app.data.youtube.isYouTubeTrack
import com.auralis.app.data.youtube.normalizeDataSyncId
import com.auralis.app.data.youtube.normalizePlaylistBrowseId
import com.auralis.app.data.youtube.playbackStart
import com.auralis.app.data.youtube.routeLibraryPlay
import com.auralis.app.data.youtube.toTrack
import com.auralis.app.playback.HISTORY_MIN_MS
import com.auralis.app.playback.PlaybackHistoryTracker
import com.auralis.app.ui.screens.YouTubeCapturedSession
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
import com.auralis.app.logging.SessionLogSanitizer
import com.auralis.app.logging.YouTubeSessionLog
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.NavState
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.performance.PerformanceController
import com.auralis.app.ui.explore.ExploreMoods
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** One YouTube playlist/album detail page. [loaded] separates "still loading" from "no songs". */
data class YouTubePlaylistUiState(
    val id: String? = null,
    val title: String = "",
    val owner: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** One row of the merged History screen: a local library song or a streamed YouTube song. */
data class HistoryEntry(
    val track: Track,
    val playedAt: Long,
    val playCount: Int,
    /** Set for YouTube entries; null for local songs. */
    val videoId: String? = null,
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
    /** Which shape this row renders. Signed-in YouTube shelves are classified, not all cards. */
    val style: HomeRowStyle = HomeRowStyle.AUTO,
    /** "Show all" in the section header (Find Your Mood opens Explore). */
    val showAll: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Video id used to page more songs in through the InnerTube radio (null when not pageable). */
    val seedVideoId: String? = null,
    /** Browse continuation for a signed-in YouTube shelf row. */
    val continuation: String? = null,
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
    /** Non-null when the signed-in recommendations could not be loaded; the page offers a retry. */
    val homeError: String? = null,
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
    /** True when a keyless build's user chose to skip the "sign-in isn't configured" notice. */
    val authBypassed: Boolean = false,
)

/** Explore mood page: the streaming songs for one mood, plus its loading/error state. */
data class ExploreUiState(
    val moodId: String? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppViewModel(private val container: AppContainer) : ViewModel() {
    /**
     * The saved settings, or null until DataStore has answered once. The app shell gates its first
     * composition on [loadedSettings] so the default Normal theme (and the local "Your music" Home)
     * can never paint over a saved Glassmorphic preference.
     */
    private val storedSettings = MutableStateFlow<AppSettings?>(null)
    val loadedSettings: StateFlow<AppSettings?> = storedSettings.asStateFlow()
    val settings: StateFlow<AppSettings> = storedSettings
        .map { it ?: AppSettings() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
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
    /** Non-null when the stored blob cannot be decrypted (type only), shown in Settings. */
    val youtubeSessionReadError = container.youtubeAuth.readError
    /** True whenever an encrypted session snapshot exists on disk (even if unreadable). */
    val youtubeSessionSnapshot = container.youtubeAuth.hasBlob
    /** The Home discovery feed: rows load independently, cached in memory until refreshed. */
    private val _home = MutableStateFlow(HomeUiState())
    val home = _home.asStateFlow()
    private var homeJob: Job? = null
    /**
     * Only the newest Home load may publish rows. A cancelled or slow load from an earlier trigger
     * must not overwrite the rows of a newer one (which caused content to vanish and reappear).
     */
    private var homeLoadGeneration = 0
    /** Sign-in gate state (Clerk). Stays inert and signed out when no publishable key is configured,
     *  so the gate can show a clear configuration notice instead of silently skipping auth. */
    private val _auth = MutableStateFlow(
        AuthUiState(configured = container.clerkAuth.configured, initializing = container.clerkAuth.configured),
    )
    val auth = _auth.asStateFlow()
    /** Explore tab: the currently opened mood and its streamed songs. */
    private val _explore = MutableStateFlow(ExploreUiState())
    val explore = _explore.asStateFlow()
    /** Session cache so re-opening a mood is instant and survives a tab switch. */
    private val exploreCache = mutableMapOf<String, List<Track>>()
    private var exploreJob: Job? = null
    /** True while the one-time "Connect YouTube Music" prompt (after a Clerk sign-in) is showing. */
    private val _youtubeConnectPrompt = MutableStateFlow(false)
    val youtubeConnectPrompt = _youtubeConnectPrompt.asStateFlow()
    /** True when that prompt is really a "session expired, sign in again" nudge. */
    private val _youtubeConnectExpired = MutableStateFlow(false)
    val youtubeConnectExpired = _youtubeConnectExpired.asStateFlow()
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
    /**
     * History rows from both sources, newest first. Local songs and streamed YouTube songs share one
     * list so a user sees what they actually listened to, not just the YouTube half.
     */
    val mergedHistory: StateFlow<List<HistoryEntry>> = combine(history, tracks, youtubeHistory) { local, library, remote ->
        val byId = library.associateBy { it.id }
        val localEntries = local.mapNotNull { row ->
            byId[row.trackId]?.let { HistoryEntry(it, row.lastPlayedAt, row.playCount, null) }
        }
        val remoteEntries = remote.map { row ->
            HistoryEntry(row.toDomain(), row.playedAt, row.playCount, row.videoId)
        }
        (localEntries + remoteEntries).sortedByDescending { it.playedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Session cache of `videoId -> musicVideoType` for likes, so read-time filtering is accurate. */
    private val likedMusicTypes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Read-time songs filter for the Room liked mirror. Older rows stored no type, so the shared
     * rule falls back to the duration cut-off; a type learned during sync makes the test exact.
     */
    private fun likePasses(videoId: String, durationMs: Long, strict: Boolean): Boolean {
        val song = YtSong(
            videoId = videoId,
            title = "",
            artist = "",
            album = "",
            durationMs = durationMs.coerceAtLeast(0L),
            thumbnailUrl = null,
            musicVideoType = likedMusicTypes[videoId],
        )
        return isMusicTrack(song, strict)
    }
    /** Liked YouTube video ids from Room, mirrored from the account and used for instant UI. */
    val youtubeLikedIds: StateFlow<Set<String>> = container.database.youtubeDao().observeLikedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    /** Liked YouTube songs (Room mirror) as [Track]s for the Library "Liked Songs" list. */
    val youtubeLikedTracks: StateFlow<List<Track>> =
        combine(container.database.youtubeDao().observeLiked(), settings) { rows, current ->
            rows.filter { likePasses(it.videoId, it.durationMs, current.youtubeSongsOnly) }
                .map { it.toDomain() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _youtubePlaylists = MutableStateFlow<List<com.auralis.app.data.youtube.YtPlaylist>>(emptyList())
    val youtubePlaylists = _youtubePlaylists.asStateFlow()
    /** Live-sync engine snapshot: drives the Liked Songs indicator and the Settings status row. */
    val liveSyncUi: StateFlow<com.auralis.app.data.youtube.LiveSyncUiState> = container.liveSync.ui
    /** The currently open YouTube playlist/album detail page. */
    private val _ytPlaylist = MutableStateFlow(YouTubePlaylistUiState())
    val ytPlaylist = _ytPlaylist.asStateFlow()
    private var ytPlaylistJob: Job? = null
    /** Why the account playlist fetch failed; null when it succeeded. Drives the Library retry row. */
    private val _youtubeLibraryError = MutableStateFlow<String?>(null)
    val youtubeLibraryError = _youtubeLibraryError.asStateFlow()
    private val _youtubeLibraryLoading = MutableStateFlow(false)
    val youtubeLibraryLoading = _youtubeLibraryLoading.asStateFlow()
    /** Result lines of Settings → "Test YouTube connection"; never contains a secret. */
    private val _youTubeTestLines = MutableStateFlow<List<String>>(emptyList())
    val youTubeTestLines = _youTubeTestLines.asStateFlow()
    private val _youTubeTestRunning = MutableStateFlow(false)
    val youTubeTestRunning = _youTubeTestRunning.asStateFlow()
    /** Serialises account → playlists → liked songs → Home so they never race each other. */
    private var youtubeSyncJob: Job? = null
    /**
     * False until the persisted settings have actually been read. The Library uses this to avoid
     * showing the "nothing here yet" hero before it knows whether YouTube Music is connected.
     */
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded = _settingsLoaded.asStateFlow()
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
    /** Guards [flushYouTubeSync] so its queue replay never runs twice at once. */
    private var syncFlushing = false

    init {
        // The single DataStore subscription: one collector feeds both the defaulted `settings`
        // state and the root's exact-value gate. `_settingsLoaded` flips in the same assignment,
        // so a true flag can never be observed alongside stale defaults.
        viewModelScope.launch {
            container.settings.settings
                .catch { failure ->
                    // Never leave the startup gate closed forever if the store is unreadable.
                    AppLog.w("Settings", "Couldn't read saved settings; using defaults", failure)
                    emit(AppSettings())
                }
                .collect { persisted ->
                    storedSettings.value = persisted
                    _settingsLoaded.value = true
                }
        }

        // The updater runs one throttled check per launch; its user-facing messages reuse the
        // app snackbar channel so Settings and the dialog share one style.
        container.updates.start()
        viewModelScope.launch { container.updates.messages.collect { _messages.trySend(it) } }

        // InnerTube HTTP 401/403 on a signed call: the store keeps the cookie but marks it expired.
        // Home drops to the anonymous state immediately and Settings offers a re-login.
        viewModelScope.launch {
            container.youtube.authExpired.collect {
                // Keep whatever Home already shows. Wiping it here replaced rendered artwork with
                // the loading skeleton mid-session (the content → Loading → content flicker); an
                // expired session is a refresh failure, not an empty feed. The reload is left to
                // the user's Retry/sign-in action, which is a deliberate content change.
                _youtubePlaylists.value = emptyList()
                _youtubeLibraryError.value = "Session expired, log in again"
                _messages.trySend("Session expired, log in again")
            }
        }

        // A session that was flagged expired may have been killed by a transient refusal. Re-check
        // the stored cookie once per launch, then attempt one silent refresh; a real revocation
        // keeps it signed out with a 30-minute backoff.
        viewModelScope.launch {
            val stored = container.youtubeAuth.snapshot()
            YouTubeSessionLog.record(
                "app",
                "start session=${if (stored != null) "yes" else "no"} " +
                    "expired=${if (stored?.expired == true) "yes" else "no"} " +
                    "signingCookie=${if (stored?.let { hasSigningCookie(it.cookie) } == true) "yes" else "no"} " +
                    "key=${if (container.youtubeAuth.keyPresent()) "yes" else "no"} " +
                    "readError=${container.youtubeAuth.readError.value ?: "none"} " +
                    "lastRefresh=${com.auralis.app.logging.refreshAgeText(stored?.lastRefreshedAt ?: 0L)} " +
                    "reason=${if (stored == null) "no stored session" else "stored"}",
            )
            if (stored?.expired == true) {
                runCatching { container.youtube.healSession(force = false) }
                    .onSuccess { outcome ->
                        if (outcome == SessionHealOutcome.RECOVERED) {
                            _youtubeLibraryError.value = null
                            _messages.trySend("YouTube Music reconnected")
                        }
                    }
                    .onFailure { AppLog.d("YouTube", "Session revive attempt failed: ${it.message}") }
            } else if (stored != null) {
                // Rotating Google cookies can make a six-hour-old snapshot unusable even though the
                // browser jar is still logged in. Refresh it before the first signed request.
                runCatching { container.youtube.refreshSessionIfStale("startup") }
                    .onSuccess { refreshed ->
                        if (refreshed) {
                            YouTubeSessionLog.record("app", "startup refresh captured a fresh session")
                            _youtubeLibraryError.value = null
                        }
                    }
                    .onFailure { AppLog.d("YouTube", "Startup session refresh failed: ${it.message}") }
            }
        }

        // State 3 (integration ON and a signed-in session): hydrate the Room-cached playlist list
        // immediately, then refresh. Re-runs when the session becomes signed in, so a cold start
        // whose settings arrive late still refreshes without waiting for the Library tab.
        viewModelScope.launch {
            combine(
                settings.map { it.youtubeIntegration },
                container.youtubeAuth.auth.map { it?.signedIn == true },
            ) { enabled, signedIn -> enabled && signedIn }
                .distinctUntilChanged()
                .collect { ready -> if (ready) startYouTubeSync(includeHome = true, force = true) }
        }

        // Live sync runs only while the integration is on, live sync is enabled and the session is
        // usable. Toggling any of those re-evaluates the engine without restarting the process.
        viewModelScope.launch {
            combine(
                settings.map { it.youtubeIntegration && it.liveSyncEnabled },
                container.youtubeAuth.auth.map { it?.signedIn == true },
            ) { wanted, signedIn -> wanted && signedIn }
                .distinctUntilChanged()
                .collect { ready -> if (ready) container.liveSync.start() else container.liveSync.stop() }
        }

        // The medium lane owns the account-playlist snapshot; mirror it and refetch the open page
        // when its title or count changed.
        viewModelScope.launch {
            container.liveSync.accountPlaylists.collect { list ->
                if (list.isEmpty() || list == _youtubePlaylists.value) return@collect
                val previous = _youtubePlaylists.value.associateBy { it.id }
                _youtubePlaylists.value = list
                val open = _ytPlaylist.value.id ?: return@collect
                val before = previous[open]
                val after = list.firstOrNull { it.id == open } ?: return@collect
                if (before == null || before.title != after.title || before.trackCount != after.trackCount) {
                    retryYouTubePlaylist()
                }
            }
        }

        // A rejected session stops the engine; nudge the user once, respecting the 48-hour snooze.
        viewModelScope.launch {
            container.liveSync.ui
                .map { it.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (state == com.auralis.app.data.youtube.LiveSyncState.AuthExpired) {
                        offerYouTubeReconnect()
                    }
                }
        }

        // Turning the integration back ON keeps the stored session; refresh it first when it is old.
        viewModelScope.launch {
            settings.map { it.youtubeIntegration }
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect { refreshYouTubeSessionIfStale() }
        }

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

        // ONE history rule for local and YouTube songs: a play counts only after ten seconds of real
        // playing time. PlaybackHistoryTracker ignores pauses and seeks, resets on a track change and
        // counts a re-listen again. The writers run on this collector so both paths share the rule.
        viewModelScope.launch {
            val tracker = PlaybackHistoryTracker(HISTORY_MIN_MS)
            container.playback.state.collect { state ->
                val id = state.trackId ?: return@collect
                val counted = tracker.onTick(
                    trackId = id,
                    positionMs = state.positionMs,
                    playing = state.connected && state.playing,
                    wallClockMs = SystemClock.elapsedRealtime(),
                )
                if (!counted) return@collect
                if (id.startsWith(YOUTUBE_TRACK_PREFIX)) {
                    recordYouTubePlay(id, state.positionMs)
                } else {
                    recordPlayEvent(id)
                }
            }
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
            // Keyless build: nothing to collect. Stay signed out so the gate shows the configuration
            // notice (with a continue-without-an-account escape hatch) rather than faking a session.
            _auth.value = AuthUiState(configured = false, initializing = false, signedIn = false)
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

    /**
     * Lets a keyless build past the "sign-in isn't configured" notice. Only reachable while Clerk is
     * unconfigured, so it never bypasses a real gate; the rest of the app stays fully usable.
     */
    fun continueWithoutAccount() { _auth.update { it.copy(authBypassed = true) } }

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
        // Account first, then the account lists, then Home — never three parallel calls that can
        // disagree about whether the session is still valid.
        syncYouTubeAccount(includeHome = true)
    }

    /** Settings → "Live sync" switch. The engine reacts through the settings/auth collector. */
    fun setLiveSyncEnabled(enabled: Boolean) {
        updateSettings { it.copy(liveSyncEnabled = enabled) }
        if (enabled) container.liveSync.start() else container.liveSync.stop()
    }

    /** Settings → "Sync now": one immediate fast + medium + full pass. */
    fun syncLiveNow() = container.liveSync.forceRefresh()

    /**
     * Loads the signed-in account data in a fixed order:
     *
     *  1. `account_menu` (also verifies the session),
     *  2. `FEmusic_liked_playlists` (+ continuations),
     *  3. `VLLM` liked songs (+ continuations),
     *  4. Home and history.
     *
     * A 401/403 in step 1 stops the sequence: the client has already verified the expiry against
     * the account menu, so the UI shows the "log in again" state instead of retrying.
     */
    fun syncYouTubeAccount(includeHome: Boolean = true, force: Boolean = false) {
        if (!settings.value.youtubeIntegration) return
        startYouTubeSync(includeHome, force)
    }

    /**
     * Runs the ordered load even when [settings] has not caught up yet — the cold-start collector
     * below fires from the DataStore flow, one tick before [settings] reflects `youtubeIntegration`.
     */
    private fun startYouTubeSync(includeHome: Boolean, force: Boolean) {
        if (youtubeSyncJob?.isActive == true) return
        youtubeSyncJob = viewModelScope.launch {
            // Paint the last known playlists from Room before any network work starts, so a
            // reopened app shows the library immediately instead of an empty screen.
            hydrateCachedPlaylists()
            _youtubeLibraryLoading.value = true
            try {
                if (!youTubeSignedIn()) {
                    _youtubePlaylists.value = emptyList()
                    // An expired session is still a failure to explain, not an empty library.
                    _youtubeLibraryError.value = if (container.youtubeAuth.snapshot()?.expired == true) {
                        "Session expired, log in again"
                    } else {
                        null
                    }
                    if (includeHome) loadHome(force = true)
                    return@launch
                }
                // 1) Account menu: resolves the name and proves the cookie still works.
                when (val account = container.youtube.accountMenu()) {
                    is YouTubeLoadResult.Success -> {
                        if (account.value.first.isNotBlank()) {
                            container.youtubeAuth.updateProfile(account.value.first, account.value.second)
                        }
                    }
                    is YouTubeLoadResult.Failure -> {
                        _youtubeLibraryError.value = if (account.code == 401 || account.code == 403) {
                            // confirmExpiry() has already verified and marked the session expired.
                            "Session expired, log in again"
                        } else {
                            "Couldn't reach YouTube Music"
                        }
                        return@launch
                    }
                }
                // 2) The real playlist list.
                when (val playlists = container.youtube.likedPlaylists(force)) {
                    is YouTubeLoadResult.Success -> {
                        _youtubePlaylists.value = playlists.value
                        _youtubeLibraryError.value = null
                    }
                    is YouTubeLoadResult.Failure -> {
                        // Keep whatever was already shown; the row explains why it may be stale.
                        _youtubeLibraryError.value = "Couldn't refresh playlists"
                    }
                }
                if (!youTubeSignedIn()) return@launch
                // 3) Liked songs, quietly (no "Synced N liked songs" toast on every launch).
                syncLikedSongs(notify = false)
                // 4) Home and history only after the library data has landed.
                if (includeHome) loadHome(force = true)
            } finally {
                _youtubeLibraryLoading.value = false
            }
        }
    }

    /** Cached likes render immediately; the account refresh runs in the background. */
    fun refreshYouTubeLibrary() = syncYouTubeAccount(includeHome = false)

    /**
     * Restores the playlist list from Room. Only fills an empty in-memory list, so a fresh response
     * that already landed can never be replaced by an older cached one.
     */
    private suspend fun hydrateCachedPlaylists() {
        if (_youtubePlaylists.value.isNotEmpty()) return
        val cached = runCatching { container.youtube.cachedPlaylists() }.getOrNull().orEmpty()
        if (cached.isNotEmpty() && _youtubePlaylists.value.isEmpty()) {
            _youtubePlaylists.value = cached
            AppLog.i("YouTube", "Library: ${cached.size} cached playlist(s) restored")
        }
    }

    /** Library retry row: bypasses the playlist cache for a real refetch. */
    fun retryYouTubeLibrary() = syncYouTubeAccount(includeHome = false, force = true)

    /**
     * A playlist card tap opens the detail page. It no longer starts playback: the user chooses a
     * song (or Play/Shuffle) on the screen, which is the behaviour a library playlist already had.
     */
    fun openYouTubePlaylist(playlist: YtPlaylist) {
        push(
            Destination.YouTubePlaylist(
                id = normalizePlaylistBrowseId(playlist.id),
                title = playlist.title,
                coverUrl = playlist.thumbnailUrl,
                owner = playlist.owner,
            ),
        )
        loadYouTubePlaylist(playlist.id, playlist.title, playlist.thumbnailUrl, playlist.owner)
    }

    /**
     * Loads one playlist/album page: the last cached page paints instantly, then a fresh fetch
     * replaces it. A failure keeps the cached rows and otherwise surfaces "couldn't load" with a
     * retry; an empty successful response is the only state that may say "No songs".
     */
    fun loadYouTubePlaylist(
        id: String,
        title: String,
        coverUrl: String? = null,
        owner: String = "",
        force: Boolean = false,
    ) {
        val browseId = normalizePlaylistBrowseId(id)
        ytPlaylistJob?.cancel()
        _ytPlaylist.value = YouTubePlaylistUiState(
            id = browseId,
            title = title,
            owner = owner,
            coverUrl = coverUrl,
            loading = true,
        )
        ytPlaylistJob = viewModelScope.launch {
            val cached = runCatching { container.youtube.cachedPlaylistSongs(browseId) }
                .getOrDefault(emptyList())
                .map { it.toTrack() }
            if (cached.isNotEmpty()) {
                _remoteTracks.update { known -> known + cached.associateBy { it.id } }
                _ytPlaylist.update {
                    if (it.id == browseId) it.copy(tracks = cached, loading = true, error = null) else it
                }
            }
            when (val result = container.youtube.playlistSongs(browseId, force = true)) {
                is YouTubeLoadResult.Success -> {
                    val tracks = result.value.map { it.toTrack() }
                    _remoteTracks.update { known -> known + tracks.associateBy { it.id } }
                    if (_ytPlaylist.value.id == browseId) {
                        _ytPlaylist.update {
                            it.copy(
                                tracks = tracks,
                                loading = false,
                                loaded = true,
                                error = null,
                                coverUrl = it.coverUrl ?: tracks.firstOrNull()?.artworkUrl,
                            )
                        }
                    }
                    AppLog.i("YouTube", "Playlist $browseId: ${tracks.size} song(s) after filtering")
                }
                is YouTubeLoadResult.Failure -> {
                    if (_ytPlaylist.value.id == browseId) {
                        _ytPlaylist.update {
                            it.copy(
                                loading = false,
                                loaded = it.tracks.isNotEmpty(),
                                error = if (it.tracks.isEmpty()) "Couldn't load this playlist" else null,
                            )
                        }
                    }
                    AppLog.w("YouTube", "Playlist $browseId failed: ${result.reason} (HTTP ${result.code})")
                }
            }
        }
    }

    fun retryYouTubePlaylist() {
        val current = _ytPlaylist.value
        val id = current.id ?: return
        loadYouTubePlaylist(id, current.title, current.coverUrl, current.owner, force = true)
    }

    /** Play/Shuffle on the playlist detail page: queues the filtered list from the top. */
    fun playYouTubePlaylist(shuffle: Boolean = false) {
        val tracks = _ytPlaylist.value.tracks
        if (tracks.isEmpty()) return
        _remoteTracks.update { known -> known + tracks.associateBy { it.id } }
        if (shuffle) {
            container.playback.play(tracks.shuffled(), 0)
        } else {
            container.playback.play(tracks, 0)
        }
        prefetchRemoteTracks(tracks.drop(1))
    }

    /** A row tap on the playlist detail page queues the whole filtered playlist at that position. */
    fun playYouTubePlaylistTrack(track: Track) {
        val list = _ytPlaylist.value.tracks
        playYouTubeTrackFrom(track, list)
    }

    /** The small play button on a playlist card: fetch (cache-first) and start the first song. */
    fun playYouTubePlaylistCard(playlist: YtPlaylist) {
        viewModelScope.launch {
            val result = container.youtube.playlistSongs(playlist.id)
            val tracks = (result as? YouTubeLoadResult.Success)?.value.orEmpty().map { it.toTrack() }
            if (tracks.isEmpty()) {
                _messages.trySend("Couldn't play “${playlist.title}”")
                return@launch
            }
            _remoteTracks.update { known -> known + tracks.associateBy { it.id } }
            container.playback.play(tracks, 0)
            prefetchRemoteTracks(tracks.drop(1))
        }
    }

    /**
     * Re-runs the whole Home feed for the current category (pull-to-refresh, header button).
     * Signed in, it goes through the ordered sequence so the session is verified first; the
     * repository call is forced, so this bypasses the cache.
     */
    fun refreshYouTubeHome() {
        if (youTubeSignedIn()) syncYouTubeAccount(includeHome = true, force = true)
        else loadHome(_home.value.category, force = true)
    }

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
        // Signed in: the account's real `FEmusic_home` shelves are the For You feed.
        if (category == HomeCategory.FOR_YOU && youTubeSignedIn()) {
            loadSignedInHome(force)
            return
        }
        val unchanged = _home.value.category == category
        val hasContent = _home.value.rows.values.any { it.tracks.isNotEmpty() || it.cards.isNotEmpty() }
        if (!force && unchanged && hasContent) return

        val generation = ++homeLoadGeneration
        val specs = homeSections(category)
        _home.update { previous ->
            previous.copy(
                category = category,
                refreshing = true,
                homeError = null,
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
            val jobs = specs.map { spec -> launch { runHomeRow(spec, generation) } }
            jobs.joinAll()
            _home.update { if (generation == homeLoadGeneration) it.copy(refreshing = false) else it }
        }
    }

    /**
     * Signed-in For You, laid out like the reference: Top Picks large cards, Recents song rows,
     * Find Your Mood tiles, then the remaining shelves classified by their own content (song rows,
     * square album/playlist cards, wide music-video cards, circular artist cards).
     *
     * A failed fetch is an error state with a retry, never an empty feed. Quick Picks load below the
     * message so the page still has something to play while the account call is broken.
     */
    private fun loadSignedInHome(force: Boolean) {
        val unchanged = _home.value.category == HomeCategory.FOR_YOU
        val hasContent = _home.value.rows.values.any { it.cards.isNotEmpty() || it.tracks.isNotEmpty() }
        if (!force && unchanged && hasContent && _home.value.homeError == null) return

        val generation = ++homeLoadGeneration
        _home.update { previous ->
            previous.copy(
                category = HomeCategory.FOR_YOU,
                refreshing = true,
                homeError = null,
                rows = if (unchanged) previous.rows else emptyMap(),
            )
        }
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val signedIn = youTubeSignedIn()
            val shelvesJob = async { container.youtube.homeShelves(force) }
            val historyJob = async {
                runCatching { container.youtube.historySongs() }.getOrDefault(emptyList())
            }
            val shelvesResult = shelvesJob.await()
            val history = historyJob.await().take(HOME_ROW_SIZE).map { it.toTrack() }
            val strict = settings.value.youtubeSongsOnly

            val rows = LinkedHashMap<String, HomeRow>()
            when (shelvesResult) {
                is YouTubeLoadResult.Success -> {
                    val shelves = shelvesResult.value
                    // Some accounts return no mood/genre shelf. The anonymous Home already derived
                    // the same tiles, so reuse them instead of dropping "Find Your Mood".
                    val fallbackMoods = if (shelves.none(::looksLikeMoodShelf)) {
                        runCatching { container.youtube.homeFeed().moods }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    val layout = buildSignedInLayout(shelves, strict, fallbackMoods)

                    layout.topPicks?.let { rows[SECTION_TOP_PICKS] = it.toHomeRow(SECTION_TOP_PICKS) }

                    if (history.isNotEmpty()) {
                        // Every row track is registered so a tap resolves through the normal path.
                        _remoteTracks.update { known -> known + history.associateBy { it.id } }
                        rows[SECTION_RECENTS] = HomeRow(
                            id = SECTION_RECENTS,
                            title = "Recents",
                            tracks = history,
                            style = HomeRowStyle.TRACKS,
                        )
                    }

                    layout.moods?.let {
                        rows[SECTION_MOODS] = it.toHomeRow(SECTION_MOODS).copy(showAll = true)
                    }

                    layout.shelves.forEachIndexed { index, block ->
                        val id = "$SECTION_YT_SHELF$index"
                        rows[id] = block.toHomeRow(id)
                    }

                    AppLog.i(
                        "YouTube",
                        "Home: status=OK signed=$signedIn shelves=${shelves.size} " +
                            "rows=${rows.size} topPicks=${layout.topPicks?.cards?.size ?: 0} " +
                            "moods=${layout.moods?.cards?.size ?: 0} " +
                            "titles=${shelves.take(3).joinToString { it.title }}",
                    )
                }
                is YouTubeLoadResult.Failure -> {
                    AppLog.w(
                        "YouTube",
                        "Home: status=FAIL signed=$signedIn code=${shelvesResult.code} " +
                            "reason=${shelvesResult.reason}",
                    )
                }
            }

            // Quick Picks are the secondary row on failure (and the only row on a truly empty page).
            val failed = shelvesResult is YouTubeLoadResult.Failure
            if (failed || rows.isEmpty()) {
                val quick = runCatching { loadQuickPicks() }.getOrNull() ?: HomeSection()
                if (quick.tracks.isNotEmpty()) {
                    rows[SECTION_QUICK_PICKS] = HomeRow(
                        id = SECTION_QUICK_PICKS,
                        title = "Quick Picks",
                        tracks = quick.tracks,
                        style = HomeRowStyle.TRACKS,
                        seedVideoId = quick.seedVideoId,
                        hasMore = quick.hasMore,
                    )
                }
            }
            _home.update { previous ->
                if (generation != homeLoadGeneration) return@update previous
                val hadContent = previous.rows.values.any {
                    it.tracks.isNotEmpty() || it.cards.isNotEmpty()
                }
                previous.copy(
                    // A failed background refresh keeps the rows already on screen. Replacing
                    // them with Quick Picks (or an empty map) is what made loaded artwork turn
                    // back into Loading placeholders.
                    rows = if (failed && hadContent) previous.rows else rows,
                    refreshing = false,
                    homeError = if (failed) "Couldn't load your recommendations" else null,
                )
            }
        }
    }

    /** One classified shelf block as a Home row; track rows also join the playable remote map. */
    private fun HomeShelfBlock.toHomeRow(id: String): HomeRow {
        if (style == HomeRowStyle.TRACKS) {
            val tracks = cards.map { it.toTrack() }.distinctBy { it.id }
            _remoteTracks.update { known -> known + tracks.associateBy { it.id } }
            return HomeRow(
                id = id,
                title = title,
                tracks = tracks,
                style = HomeRowStyle.TRACKS,
                continuation = continuation,
                hasMore = continuation != null,
            )
        }
        return HomeRow(
            id = id,
            title = title,
            cards = cards,
            style = style,
            continuation = continuation,
            hasMore = continuation != null,
        )
    }

    /** Category chip tap: loads the section set for that filter. */
    fun selectHomeCategory(category: HomeCategory) {
        if (_home.value.category == category && _home.value.rows.values.any { it.tracks.isNotEmpty() || it.cards.isNotEmpty() }) return
        loadHome(category, force = false)
    }

    /** Pages more content into a Home row: YouTube shelf continuation first, then a radio seed. */
    fun loadMoreHomeRow(rowId: String) {
        val row = _home.value.rows[rowId] ?: return
        if (row.loading) return
        val generation = homeLoadGeneration
        val continuation = row.continuation
        if (continuation != null) {
            _home.update { state ->
                state.rows[rowId]?.let { state.copy(rows = state.rows + (rowId to it.copy(loading = true))) } ?: state
            }
            viewModelScope.launch {
                val shelves = container.youtube.homeContinuation(continuation)
                val strict = settings.value.youtubeSongsOnly
                val next = shelves.firstNotNullOfOrNull { it.continuation }
                val blocks = shelves.flatMap { classifyShelf(it, strict) }
                _home.update { state ->
                    val current = state.rows[rowId] ?: return@update state
                    // A refresh replaced this row while the continuation was in flight.
                    if (generation != homeLoadGeneration || current.continuation != continuation) return@update state
                    if (current.style == HomeRowStyle.TRACKS) {
                        val more = blocks.filter { it.style == HomeRowStyle.TRACKS }
                            .flatMap { it.cards }
                            .map { it.toTrack() }
                            .distinctBy { it.id }
                        _remoteTracks.update { known -> known + more.associateBy { it.id } }
                        val merged = (current.tracks + more).distinctBy { it.id }
                        state.copy(
                            rows = state.rows + (
                                rowId to current.copy(
                                    tracks = merged,
                                    loading = false,
                                    continuation = next,
                                    hasMore = next != null,
                                )
                                ),
                        )
                    } else {
                        val wanted = if (current.style == HomeRowStyle.LARGE_CARDS) {
                            HomeRowStyle.SQUARE_TILES
                        } else {
                            current.style
                        }
                        val more = if (current.style == HomeRowStyle.AUTO) {
                            shelves.flatMap { it.items }.filter { isMusicCard(it, strict) }
                        } else {
                            blocks.filter { it.style == wanted }.flatMap { it.cards }
                        }
                        val merged = (current.cards + more)
                            .distinctBy { it.videoId ?: it.browseId ?: it.title }
                        state.copy(
                            rows = state.rows + (
                                rowId to current.copy(
                                    cards = merged,
                                    loading = false,
                                    continuation = next,
                                    hasMore = next != null,
                                )
                                ),
                        )
                    }
                }
            }
            return
        }
        val seed = row.seedVideoId ?: return
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
                // A refresh replaced the radio seed while this page was in flight.
                if (generation != homeLoadGeneration || current.seedVideoId != seed) return@update state
                val merged = (current.tracks + more).distinctBy { it.id }
                state.copy(rows = state.rows + (rowId to current.copy(tracks = merged, loading = false, hasMore = more.isNotEmpty())))
            }
        }
    }

    /** A Home card tap: song cards play immediately; album/playlist cards open their detail page. */
    fun openHomeCard(card: YtCard) {
        if (card.videoId != null) {
            playYouTubeCard(card)
            return
        }
        val browseId = card.browseId
        val isArtist = browseId?.startsWith("UC") == true
        // Album ("MPRE…"), playlist ("VL…"/"OLAK…") cards land on the same detail page as Library
        // playlists. Artist/mood cards keep the song sheet, which has no single ordered track list.
        if (browseId != null && !isArtist &&
            (browseId.startsWith("VL") || browseId.startsWith("MPRE") || browseId.startsWith("OLAK"))
        ) {
            openYouTubePlaylist(
                YtPlaylist(
                    id = browseId,
                    title = card.title,
                    thumbnailUrl = card.thumbnailUrl,
                    owner = card.subtitle,
                ),
            )
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

    // ---- Explore (streaming-only moods) -------------------------------------------------------

    /**
     * Loads the streaming songs for [moodId]. Repeat opens are served from [exploreCache]; a load
     * already in flight for the same mood is ignored. Content comes from YouTube Music only — the
     * local library and downloads are never read or filtered. A network failure becomes an inline
     * error the page can retry.
     */
    fun loadExploreMood(moodId: String, force: Boolean = false) {
        val mood = ExploreMoods.byId(moodId) ?: return
        if (!force) {
            exploreCache[moodId]?.let { cached ->
                _explore.value = ExploreUiState(moodId = moodId, tracks = cached)
                return
            }
        }
        if (_explore.value.moodId == moodId && _explore.value.loading) return
        exploreJob?.cancel()
        _explore.value = ExploreUiState(moodId = moodId, loading = true)
        exploreJob = viewModelScope.launch {
            try {
                val tracks = container.youtube.moodSongs(mood.title).map { it.toTrack() }
                exploreCache[moodId] = tracks
                _remoteTracks.update { known -> known + tracks.associateBy { it.id } }
                if (_explore.value.moodId == moodId) {
                    _explore.value = ExploreUiState(moodId = moodId, tracks = tracks)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                AppLog.w("Explore", "Mood \"${mood.title}\" failed", error)
                if (_explore.value.moodId == moodId) {
                    _explore.value = ExploreUiState(
                        moodId = moodId,
                        error = "Couldn't load this mood. Check your connection and try again.",
                    )
                }
            }
        }
    }

    /** Plays a mood song inside the mood's own queue. */
    fun playExploreTrack(track: Track) {
        _remoteTracks.update { known -> known + (track.id to track) }
        play(track, _explore.value.tracks)
    }

    private suspend fun runHomeRow(spec: HomeSectionSpec, generation: Int) {
        // Cancellation is not a failure: a superseded load must stop silently instead of writing
        // an error (or a stale result) over the newer load's row.
        val section = try {
            spec.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AppLog.d("Home", "Section ${spec.id} failed: ${failure.message}")
            null
        }
        _home.update { state ->
            if (generation != homeLoadGeneration) return@update state
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
     * Offers the YouTube Music hand-off after a Clerk sign-in: turns the integration switch on and
     * shows the prompt. Skipped when Clerk is not configured, when YouTube is already signed in, or
     * while the 48-hour "Don't ask again" snooze is still running.
     */
    private fun maybeOfferYouTubeConnect() {
        if (!_auth.value.configured) return
        if (container.youtubeAuth.snapshot()?.signedIn == true) return
        _youtubeConnectEmail.value = _auth.value.account?.email?.takeIf { it.isNotBlank() }
        _youtubeConnectExpired.value = false
        viewModelScope.launch {
            // Read the stored preferences, not the StateFlow default: Clerk can restore the
            // session before DataStore emits, and a persisted snooze must still be honoured.
            val current = runCatching { container.settings.settings.first() }
                .getOrDefault(settings.value)
            if (System.currentTimeMillis() < current.youtubeConnectPromptSnoozeUntil) return@launch
            runCatching { container.settings.update { it.copy(youtubeIntegration = true) } }
                .onFailure { report("Couldn't turn on YouTube Music", it) }
            _youtubeConnectPrompt.value = true
        }
    }

    /**
     * One non-blocking nudge to sign in again after live sync was rejected. Shares the same 48-hour
     * "Don't ask again" snooze as the connect prompt, so the user is asked at most once per snooze.
     */
    private fun offerYouTubeReconnect() {
        if (_youtubeConnectPrompt.value) return
        _youtubeConnectEmail.value = _auth.value.account?.email?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            val current = runCatching { container.settings.settings.first() }
                .getOrDefault(settings.value)
            if (System.currentTimeMillis() < current.youtubeConnectPromptSnoozeUntil) return@launch
            _youtubeConnectExpired.value = true
            _youtubeConnectPrompt.value = true
        }
    }

    /**
     * Hides the hand-off prompt. "Not now" only hides it for this session; "Don't ask again"
     * persists a 48-hour snooze, so the prompt returns on a later launch once it expires.
     */
    fun dismissYouTubeConnectPrompt(dontAskAgain: Boolean) {
        _youtubeConnectPrompt.value = false
        _youtubeConnectExpired.value = false
        if (dontAskAgain) {
            val snoozeUntil = System.currentTimeMillis() + YOUTUBE_PROMPT_SNOOZE_MS
            viewModelScope.launch {
                runCatching { container.settings.update { it.copy(youtubeConnectPromptSnoozeUntil = snoozeUntil) } }
                    .onFailure { report("Couldn't save that preference", it) }
            }
        }
    }

    fun refreshYouTubeAccount() {
        if (container.youtubeAuth.snapshot() == null) return
        viewModelScope.launch {
            when (val account = container.youtube.accountMenu()) {
                is YouTubeLoadResult.Success ->
                    if (account.value.first.isNotBlank()) {
                        container.youtubeAuth.updateProfile(account.value.first, account.value.second)
                    }
                is YouTubeLoadResult.Failure ->
                    AppLog.w("YouTube", "Account lookup failed: ${account.reason}")
            }
        }
    }

    /**
     * Called every time Settings → YouTube Music opens. Re-checks a stored session flagged expired
     * (account_menu first, then one silent refresh) without blocking the UI. Respects the 30-minute
     * backoff, so opening the page repeatedly cannot hammer Google. A still-valid but old session is
     * refreshed instead (six-hour staleness), so "Continue as <name>" normally needs one tap.
     */
    fun healYouTubeSession() {
        val stored = container.youtubeAuth.snapshot() ?: return
        if (stored.expired) {
            viewModelScope.launch {
                runCatching { container.youtube.healSession(force = false) }
                    .onSuccess { outcome ->
                        if (outcome == SessionHealOutcome.RECOVERED) {
                            _youtubeLibraryError.value = null
                            _messages.trySend("YouTube Music reconnected")
                        }
                    }
                    .onFailure { AppLog.d("YouTube", "Session heal from Settings failed: ${it.message}") }
            }
        } else {
            refreshYouTubeSessionIfStale()
        }
    }

    /**
     * App start/foreground and integration-on trigger. Only a session older than six hours does any
     * work; the repository serialises attempts and enforces the 30-minute failure backoff.
     */
    fun refreshYouTubeSessionIfStale() {
        if (container.youtubeAuth.snapshot() == null) return
        viewModelScope.launch {
            runCatching { container.youtube.refreshSessionIfStale("foreground") }
                .onSuccess { refreshed ->
                    if (refreshed) {
                        _youtubeLibraryError.value = null
                        AppLog.i("YouTube", "Session refreshed before it could be refused")
                    }
                }
                .onFailure { AppLog.d("YouTube", "Foreground session refresh failed: ${it.message}") }
        }
    }

    /**
     * Settings → "Test YouTube connection". Runs account_menu, FEmusic_liked_playlists and VLLM in
     * order and shows one line each. The checks never expire the session; only a real signed-in
     * library load can do that (after its own verification call).
     */
    fun testYouTubeConnection() {
        if (_youTubeTestRunning.value) return
        viewModelScope.launch {
            _youTubeTestRunning.value = true
            try {
                val checks = runCatching { container.youtube.connectionTest() }
                    .onFailure { AppLog.w("YouTube", "Connection test failed", it) }
                    .getOrDefault(emptyList())
                _youTubeTestLines.value = checks.map { it.display() }
            } finally {
                _youTubeTestRunning.value = false
            }
        }
    }

    /**
     * Called by the login WebView only after the user taps Confirm. Cookie/visitor/page values come
     * from the page; the app never logs them.
     */
    fun saveYouTubeSession(session: YouTubeCapturedSession) {
        val delegated = session.delegatedSessionId.trim()
        container.youtubeAuth.save(
            YouTubeAuth(
                cookie = session.cookie,
                visitorData = session.visitorData,
                // Continue keeps the saved identity until the ordered account refresh corrects it;
                // a different account sends none, so the old profile is never inherited.
                name = session.name,
                email = session.email,
                avatarUrl = session.avatarUrl,
                // DELEGATED_SESSION_ID wins; otherwise the active (second) half of DATASYNC_ID.
                dataSyncId = normalizeDataSyncId(delegated.ifBlank { session.dataSyncId }).orEmpty(),
                pageId = delegated.takeIf { it.isNotBlank() },
                authUser = session.sessionIndex.ifBlank { "0" },
                clientVersion = session.clientVersion,
                userAgent = session.userAgent,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        container.youtubeAuth.clearHealFailure()
        YouTubeSessionLog.record(
            "login",
            "session saved cookies=[" +
                SessionLogSanitizer.cookieNames(session.cookie).joinToString(",") + "]",
        )
        // Connected: the prompt must never appear again, even if a snooze was running.
        _youtubeConnectPrompt.value = false
        _youtubeConnectExpired.value = false
        container.youtube.invalidateHome()
        // Ordered, not parallel: account menu verifies the new cookie before anything else runs.
        syncYouTubeAccount(includeHome = true)
        _messages.trySend("Signed in to YouTube Music")
        // Any playlist edits made while signed out/failed are now retryable.
        flushYouTubeSync()
    }

    fun signOutYouTube() {
        youtubeSyncJob?.cancel()
        container.youtubeAuth.clear(SessionClearRules.DISCONNECT)
        // The explicit Disconnect is one of only two moments the browser cookies are removed.
        clearYouTubeWebSessionCookies()
        YouTubeSessionLog.record("session", "disconnect caller=${SessionClearRules.DISCONNECT}")
        container.youtube.invalidateHome()
        _home.value = HomeUiState()
        _youtubePlaylists.value = emptyList()
        _youtubeLibraryError.value = null
        _youTubeTestLines.value = emptyList()
        viewModelScope.launch {
            runCatching { container.database.youtubeDao().clearLikes() }
            // Disconnect removes cached YouTube data only; the local library and Clerk stay.
            runCatching { container.youtube.clearCachedData() }
        }
        _messages.trySend("Disconnected YouTube Music")
        if (settings.value.youtubeIntegration) loadHome(force = true)
    }

    /**
     * "Use a different account": expires the Google browser cookies by name so the chooser cannot
     * silently resume the old account, then opens the sign-in dialog. The stored session is kept (and
     * the switch stays usable) until a successful sign-in replaces it, so cancelling the chooser
     * changes nothing.
     */
    fun switchYouTubeAccount() {
        clearYouTubeWebSessionCookies()
        YouTubeSessionLog.record("login", "use a different account: browser cookies expired")
        _messages.trySend("Choose a different Google account")
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

    /** Instant Room mirror + best-effort account call; rolled back with a toast if the account rejects it. */
    fun toggleYouTubeLike(track: Track) {
        val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val wasLiked = youtubeLikedIds.value.contains(videoId)
        viewModelScope.launch {
            val local = runCatching {
                if (wasLiked) container.database.youtubeDao().deleteLike(videoId)
                else container.database.youtubeDao().upsertLike(track.toYouTubeLikeEntity())
            }
            if (local.isFailure) {
                report("Couldn't update likes", local.exceptionOrNull() ?: IllegalStateException("Like update failed"))
                return@launch
            }
            // Tell the live-sync poller to leave this id alone until YouTube catches up.
            container.liveSync.notePendingLike(videoId, liked = !wasLiked)
            if (!settings.value.youtubeSyncLikes || container.youtubeAuth.snapshot()?.signedIn != true) return@launch
            val synced = runCatching { container.youtube.setLiked(videoId, !wasLiked) }.getOrDefault(false)
            if (!synced) {
                // Roll the heart back so the UI always matches what the account actually holds.
                runCatching {
                    if (wasLiked) container.database.youtubeDao().upsertLike(track.toYouTubeLikeEntity())
                    else container.database.youtubeDao().deleteLike(videoId)
                }
                _messages.trySend("Couldn't update this like")
            }
        }
    }

    /**
     * Plays one YouTube song on its own. Used where the caller has no surrounding list (Home cards,
     * History, search results).
     */
    fun playYouTubeTrack(track: Track) = playYouTubeTrackFrom(track, listOf(track))

    /**
     * Plays a YouTube song chosen from a mixed list (Liked Songs, playlist detail). Every streamable
     * entry in [list] is registered so the player can resolve it, and the queue starts at the
     * tapped song. Local entries are left out: the stream resolver only understands YouTube ids.
     */
    fun playYouTubeTrackFrom(track: Track, list: List<Track>) {
        // Start the end-to-end latency clock; PlaybackService logs resolved/ready/audible against it.
        com.auralis.app.playback.PlaybackTiming.markTap()
        val remote = list.filter { it.isYouTubeTrack() }
        // `playbackStart` never substitutes the list's first song for a missing tap; the tapped song
        // plays on its own instead. That was the old index-0 bug on a stale/mismatched list.
        val start = playbackStart(track, remote)
        if (!start.found) AppLog.w("Playback", "Tapped YouTube song was missing from the queue; playing it alone")
        _remoteTracks.update { known -> known + start.queue.associateBy { it.id } }
        container.playback.play(start.queue, start.index)
        _youtubeResolvingId.value = track.id
        prefetchRemoteTracks(start.queue.drop(start.index + 1).take(REMOTE_PREFETCH_COUNT))
        viewModelScope.launch {
            try {
                track.remoteId?.let { container.youtube.resolveStream(it) }
                backfillTrackMetadata(track)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.w("YouTube", "Could not resolve ${track.id}", error)
                _messages.trySend("Couldn't play this YouTube song: ${error.localizedMessage ?: "stream unavailable"}")
            } finally {
                if (_youtubeResolvingId.value == track.id) _youtubeResolvingId.value = null
            }
        }
    }

    /** Warms the resolver for the next few queue entries so Next is instant. */
    private fun prefetchRemoteTracks(tracks: List<Track>) {
        tracks.forEach { prefetchRemoteStream(it.id) }
    }

    /**
     * Fills in title/artist/duration/artwork for a queue entry the caller only had a video id for
     * (for example an old History row). The now-playing UI and the media notification read the same
     * `_remoteTracks` map, so the row updates in place once the metadata arrives.
     */
    private fun backfillTrackMetadata(track: Track) {
        val videoId = track.remoteId ?: return
        if (track.artist.isNotBlank() && !track.artworkUrl.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { container.youtube.metadata(videoId) }
                .onSuccess { meta ->
                    if (meta == null) return@onSuccess
                    _remoteTracks.update { known ->
                        val current = known[track.id] ?: return@update known
                        known + (track.id to current.copy(
                            title = current.title.ifBlank { meta.title },
                            artist = current.artist.ifBlank { meta.artist },
                            album = current.album.ifBlank { meta.album },
                            durationMs = if (current.durationMs > 0L) current.durationMs else meta.durationMs,
                            artworkUrl = current.artworkUrl?.takeIf { !com.auralis.app.data.youtube.isVideoThumbnailUrl(it) }
                                ?: meta.thumbnailUrl?.let { com.auralis.app.data.youtube.normalizeArtworkUrl(it) },
                        ))
                    }
                }
                .onFailure { AppLog.d("YouTube", "Metadata lookup skipped for $videoId: ${it.message}") }
        }
    }

    /**
     * One tap in the Library. A streamed YouTube song goes through the YouTube queue; anything else
     * is a local file and stays on the existing local path. Used for sections whose rows are all
     * local, where a YouTube id can still appear through a saved queue entry.
     */
    fun playLibraryTrack(track: Track, list: List<Track>) = routeLibraryPlay(
        track,
        playLocal = { play(it, tracks.value) },
        playYouTube = { playYouTubeTrackFrom(it, list) },
    )

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

    /**
     * Replays a History row. The rest of the (merged, newest-first) history becomes the queue, so
     * Next continues down the list instead of stopping after one song.
     */
    fun playHistoryEntry(entry: HistoryEntry) {
        val list = mergedHistory.value.map { it.track }
        if (entry.track.isYouTubeTrack()) {
            playYouTubeTrackFrom(entry.track, list)
        } else {
            play(entry.track, list.filter { !it.isYouTubeTrack() })
        }
    }

    /** "Clear history" on the History screen: local plays and the YouTube mirror both go. */
    fun clearHistory() {
        viewModelScope.launch {
            runCatching {
                container.database.historyDao().clear()
                container.database.youtubeDao().clearRecents()
            }.onFailure { report("Couldn't clear history", it) }
        }
    }

    /** Removes one Recent-search row (case-insensitive), leaving the rest untouched. */
    fun deleteSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.database.searchHistoryDao().delete(trimmed) }
                .onFailure { AppLog.w("Search", "Could not delete a recent search", it) }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            runCatching { container.database.searchHistoryDao().clear() }
                .onFailure { AppLog.w("Search", "Could not clear recent searches", it) }
        }
    }

    fun loadLikedSongs() {
        viewModelScope.launch { syncLikedSongs(notify = true) }
    }

    /** Mirrors VLLM into Room. [notify] is false for the ordered startup sequence. */
    private suspend fun syncLikedSongs(notify: Boolean) {
        runCatching { container.youtube.likedSongsForSync() }
            .onSuccess { (songs, hiddenIds) ->
                songs.forEach { song -> likedMusicTypes[song.videoId] = song.musicVideoType.orEmpty() }
                val entries = songs.map { it.ytSongToLikeEntity() }
                runCatching {
                    val dao = container.database.youtubeDao()
                    entries.forEach { dao.upsertLike(it) }
                    // Only the ids that failed the songs filter are removed; passing hearts stay.
                    hiddenIds.forEach { dao.deleteLike(it) }
                }
                if (notify) _messages.trySend("Synced ${entries.size} liked songs")
            }
            .onFailure { if (notify) report("Couldn't load liked songs", it) }
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
        // ...and to poll the account once and resume the fast live-sync lanes.
        container.liveSync.onAppForeground()
    }

    /** Called from MainActivity.onStop: the in-process lanes step aside for the background tier. */
    fun onAppBackgrounded() {
        container.liveSync.onAppBackground()
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

    /**
     * Starts [track] inside [list]. If the caller handed a list that does not contain the tapped
     * track, the tapped song is played on its own: falling back to index 0 (the old behaviour)
     * quietly played a different song, which is exactly what the Liked Songs rows showed.
     */
    fun play(track: Track, list: List<Track> = tracks.value) {
        val start = playbackStart(track, list)
        if (!start.found) {
            AppLog.w("Playback", "Tapped track was not in the supplied queue; playing it alone")
        }
        container.playback.play(start.queue, start.index)
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
        // A tap on a result is also a completed search: remember the term even if the deep-link
        // was reached without a keyboard submit.
        _youtubeSearch.value.query.takeIf { it.isNotBlank() }?.let(::recordSearch)
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

    /** Persists a search term: trimmed, min 2 chars, case-insensitive dedupe, newest first, max 20. */
    private fun recordSearch(query: String) {
        val trimmed = com.auralis.app.data.db.SearchHistoryRules.normalize(query) ?: return
        viewModelScope.launch {
            runCatching {
                val dao = container.database.searchHistoryDao()
                // The table's primary key is the literal query, so "Abbey" and "abbey" would both
                // survive. Remove every casing of the term first, then re-insert at the top.
                dao.delete(trimmed)
                dao.upsert(SearchHistoryEntity(trimmed, System.currentTimeMillis()))
                dao.trim(SEARCH_HISTORY_LIMIT)
            }.onFailure { AppLog.w("YouTube", "Search history not saved", it) }
        }
    }

    /**
     * Keyboard "Search" / a recent-search tap: save the term now (the user explicitly submitted it)
     * and run the same query the debounced pipeline would have run.
     */
    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        recordSearch(trimmed)
        searchYouTube(trimmed)
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

    /** Plays a playlist in a random order (Part R ⋮ menu). */
    fun shufflePlaylist(playlistId: Long) {
        val ordered = playlistItems(playlistId).map { it.track }
        if (ordered.isEmpty()) return
        _remoteTracks.update { known -> known + ordered.filter { it.isRemote }.associateBy { it.id } }
        play(ordered.random(), ordered)
        if (!container.playback.state.value.shuffle) toggleShuffle()
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
                // are queried in parallel; each intermediate winner is shown as it arrives.
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
        // Called by the shared PlaybackHistoryTracker once a song has genuinely been listened to for
        // ten seconds, so no guard against "the same id again" is needed: a re-listen is a new play.
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

    /**
     * A YouTube song crossed the ten-second mark. The local mirror always records; reporting to the
     * account stays opt-in and fully swallowed so it can never break playback.
     */
    private fun recordYouTubePlay(trackId: String, positionMs: Long) {
        val videoId = trackId.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val current = _remoteTracks.value[trackId]
        viewModelScope.launch {
            runCatching {
                val dao = container.database.youtubeDao()
                val count = dao.playCountOf(videoId) ?: 0
                val entity = current?.toYouTubeRecentEntity(count + 1)
                    ?: com.auralis.app.data.db.YouTubeRecentEntity(
                        videoId = videoId,
                        title = videoId,
                        artist = "",
                        album = "",
                        durationMs = 0L,
                        thumbnailUrl = null,
                        playedAt = System.currentTimeMillis(),
                        playCount = count + 1,
                    )
                dao.upsertRecent(entity)
            }.onFailure { report("Couldn't save history", it) }
        }
        if (settings.value.youtubeSyncHistory && youTubeSignedIn()) {
            viewModelScope.launch {
                runCatching { container.youtube.reportPlayback(videoId, positionMs / 1000L) }
                    .onFailure { AppLog.d("YouTube", "History report skipped: ${it.message}") }
            }
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
        /** Recent searches shown on the empty Search page. */
        const val SEARCH_HISTORY_LIMIT = 20
        /** Queue entries warmed after a tap so Next is instant. */
        const val REMOTE_PREFETCH_COUNT = 3
        /** "Don't ask again" hides the Connect YouTube Music prompt for 48 hours. */
        const val YOUTUBE_PROMPT_SNOOZE_MS = 48L * 60L * 60L * 1000L
        /** YouTube Music charts landing page (public browse id); powers Trending and Popular Artists. */
        const val CHARTS_BROWSE_ID = "FEmusic_charts"
        const val HOME_ROW_SIZE = 20
        const val ARTIST_ROW_SIZE = 12
        // Stable section ids, kept across refreshes so a row's state updates in place.
        const val SECTION_QUICK_PICKS = "quick_picks"
        const val SECTION_TRENDING_IN = "trending_in"
        /** Prefix for dynamic signed-in shelf rows (`yt_shelf_0`, `yt_shelf_1`, …). */
        const val SECTION_YT_SHELF = "yt_shelf_"
        /** Large card row taken from the first albums/singles/mixes shelf. */
        const val SECTION_TOP_PICKS = "top_picks"
        /** `FEmusic_history` song rows. */
        const val SECTION_RECENTS = "recents"
        /** Mood/genre tiles; "Show all" opens Explore. */
        const val SECTION_MOODS = "moods"
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
