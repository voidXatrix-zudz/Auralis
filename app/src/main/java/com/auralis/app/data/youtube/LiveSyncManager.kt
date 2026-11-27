package com.auralis.app.data.youtube

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.auralis.app.data.db.AuralisDatabase
import com.auralis.app.data.db.YouTubeLikeEntity
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.performance.DevicePerformanceMonitor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the signed-in YouTube Music account live inside AURALIS.
 *
 * InnerTube has no push channel, so "live" is efficient polling plus diffing. Three lanes run:
 *
 *  - **Fast** (~4 s foreground / ~10 s while a song plays): the first page of Liked Music. New likes
 *    are inserted at the top of the Room mirror; likes the user removed are dropped. A cheap hash
 *    pre-check skips the database work when nothing changed.
 *  - **Medium** (~30 s foreground / ~60 s while playing): the account playlist list. Only changed
 *    playlists reach the UI; the songs of an open playlist are refetched by the caller on change.
 *  - **Slow** (~5 min, plus at start and after sign-in): a full reconciliation that follows the
 *    liked-songs continuations and removes anything no longer in the account.
 *
 * All network work is IO-dispatched and guarded by a single in-flight flag, so a slow poll never
 * overlaps itself. Failures back off exponentially (4→8→16→32→60 s, at least 60 s on 429/403) and a
 * rejected session stops polling and reports [LiveSyncState.AuthExpired] instead of retrying.
 */
class LiveSyncManager(
    context: Context,
    private val youtube: YouTubeRepository,
    private val auth: YouTubeAuthStore,
    private val settings: SettingsRepository,
    private val database: AuralisDatabase,
    private val performance: DevicePerformanceMonitor,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncLock = Mutex()
    private val playlistStateLock = Any()
    private val polling = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var settingsJob: Job? = null

    private val _ui = MutableStateFlow(LiveSyncUiState())
    val ui: StateFlow<LiveSyncUiState> = _ui.asStateFlow()

    /** The account playlists as last seen by the medium lane; the UI mirrors this into its own state. */
    private val _accountPlaylists = MutableStateFlow<List<YtPlaylist>>(emptyList())
    val accountPlaylists: StateFlow<List<YtPlaylist>> = _accountPlaylists.asStateFlow()

    /**
     * Reused for the background history pull: folds `FEmusic_history` into the Room mirror so a song
     * played on another device lands before the next foreground. The foreground timer does the same
     * job while the app is open, so the worker only needs one pass per cycle.
     */
    private val historySync = YouTubeHistorySync(
        dao = database.youtubeDao(),
        fetch = { youtube.historySongsResult() },
    )

    @Volatile private var enabled = false
    @Volatile private var foreground = false
    @Volatile private var playbackActive = false
    @Volatile private var online = true
    @Volatile private var lowPerformance = false
    @Volatile private var failureStreak = 0
    @Volatile private var playlistAccountGeneration = 0L
    @Volatile private var lastLikedHash = 0
    private var lastTopIds: Set<String> = emptySet()
    private var lastPlaylistSignature: String? = null
    private var lastSyncedPersisted = 0L
    private var lastSyncedUi = 0L
    private var loadedPersisted = false

    private val pendingLikes = ConcurrentHashMap<String, PendingLikeAction>()
    private val random = Random(System.currentTimeMillis())

    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    init {
        runCatching { registerNetwork() }
            .onFailure { AppLog.d(TAG, "Connectivity callbacks unavailable: ${it.message}") }
        online = readOnline()
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    /** Starts polling if the integration is on, the user is signed in and live sync is enabled. */
    fun start() {
        if (enabled) return
        enabled = true
        loadPersistedLastSynced()
        observePerformanceProfile()
        scheduleWorker()
        restartLoops()
        // On app start / after sign-in run a full reconciliation once, then settle into the lanes.
        scope.launch {
            if (ready()) {
                fullReconcile()
                reconcilePlaylists()
            }
        }
    }

    fun stop() {
        if (!enabled && loopJob == null) return
        enabled = false
        loopJob?.cancel()
        loopJob = null
        settingsJob?.cancel()
        settingsJob = null
        cancelWorker()
        _ui.value = _ui.value.copy(state = LiveSyncState.Disabled)
    }

    /** Invalidates the previous account's snapshot so an old poll cannot restore it. */
    fun onYouTubeAccountChanged() {
        synchronized(playlistStateLock) {
            playlistAccountGeneration++
            lastPlaylistSignature = null
            _accountPlaylists.value = emptyList()
        }
    }

    /** Called when the Activity is resumed: poll at once and resume the foreground lanes. */
    fun onAppForeground() {
        foreground = true
        if (!online) {
            _ui.value = _ui.value.copy(state = LiveSyncState.Offline)
            return
        }
        restartLoops()
        scope.launch { pollLikeds() }
    }

    /** Called when the Activity is stopped: the background tier takes over from here. */
    fun onAppBackground() {
        foreground = false
        restartLoops()
    }

    /** The Media3 service reports playback so the lanes can use the slower playback cadence. */
    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        restartLoops()
    }

    /** "Sync now": one fast poll, one playlist refresh and one full reconciliation, right away. */
    fun forceRefresh() {
        scope.launch {
            online = readOnline()
            if (!backgroundReady()) return@launch
            val wasEnabled = enabled
            enabled = true
            try {
                pollLikeds()
                reconcilePlaylists()
                fullReconcile()
            } finally {
                if (!wasEnabled) enabled = false
            }
        }
    }

    /**
     * One-shot pass used by the WorkManager background tier. It runs even when no ViewModel ever
     * called [start] (a worker can wake the process on its own), so readiness is read from settings
     * and the stored session rather than the in-memory [enabled] flag.
     */
    suspend fun runBackgroundSync() {
        online = readOnline()
        if (!backgroundReady()) return
        enabled = true
        try {
            fullReconcile()
            reconcilePlaylists()
            syncHistory()
        } finally {
            enabled = false
        }
    }

    /** Records a like/unlike the user just made, so the poller leaves that id alone for a while. */
    fun notePendingLike(videoId: String, liked: Boolean) {
        if (videoId.isBlank()) return
        pendingLikes[videoId] = PendingLikeAction(liked = liked, expiresAt = System.currentTimeMillis() + PENDING_TTL_MS)
    }

    // ---- Loops -----------------------------------------------------------------------------

    private fun restartLoops() {
        loopJob?.cancel()
        loopJob = null
        if (!enabled) return
        val active = foreground || playbackActive
        if (!active) {
            if (_ui.value.state != LiveSyncState.Disabled && _ui.value.state != LiveSyncState.AuthExpired) {
                _ui.value = _ui.value.copy(state = LiveSyncState.Idle)
            }
            return
        }
        loopJob = scope.launch {
            launch { fastLoop() }
            launch { mediumLoop() }
            launch { slowLoop() }
        }
    }

    private suspend fun fastLoop() {
        while (scope.isActive && enabled && (foreground || playbackActive)) {
            delay(fastDelayMs())
            if (!enabled || (!foreground && !playbackActive)) break
            pollLikeds()
        }
    }

    private suspend fun mediumLoop() {
        while (scope.isActive && enabled && (foreground || playbackActive)) {
            delay(mediumDelayMs())
            if (!enabled || (!foreground && !playbackActive)) break
            // Force a fresh read: the library cache TTL is an hour, far longer than this lane.
            reconcilePlaylists()
        }
    }

    private suspend fun slowLoop() {
        while (scope.isActive && enabled) {
            delay(SLOW_MS)
            if (!enabled) break
            fullReconcile()
        }
    }

    // ---- Lanes -----------------------------------------------------------------------------

    private suspend fun pollLikeds() {
        if (!ready()) return
        // The fast lane pauses in Battery Saver so a 4-second cadence never fights the power policy.
        if (foreground && performance.conditions.value.batterySaver) return
        if (!polling.compareAndSet(false, true)) return
        try {
            if (_ui.value.state != LiveSyncState.Live) _ui.value = _ui.value.copy(state = LiveSyncState.Syncing)
            expirePending()
            val result = youtube.likedSongsSyncResult(fastPage = true)
            when (result) {
                is YouTubeLoadResult.Failure -> handleFailure(result.code)
                is YouTubeLoadResult.Success -> {
                    applyLikes(result.value.first, result.value.second, full = false)
                    onSuccess()
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppLog.w(TAG, "Liked-songs poll failed", error)
            onFailure()
        } finally {
            polling.set(false)
        }
    }

    private suspend fun fullReconcile() {
        if (!ready()) return
        syncLock.withLock {
            if (_ui.value.state != LiveSyncState.Live) _ui.value = _ui.value.copy(state = LiveSyncState.Syncing)
            expirePending()
            val result = runCatching { youtube.likedSongsSyncResult(fastPage = false) }
            result.onSuccess { outcome ->
                when (outcome) {
                    is YouTubeLoadResult.Failure -> handleFailure(outcome.code)
                    is YouTubeLoadResult.Success -> {
                        applyLikes(outcome.value.first, outcome.value.second, full = true)
                        onSuccess()
                    }
                }
            }.onFailure { error ->
                AppLog.w(TAG, "Liked-songs reconcile failed", error)
                onFailure()
            }
        }
    }

    private suspend fun reconcilePlaylists() {
        if (!ready()) return
        val generation = playlistAccountGeneration
        val result = try {
            Result.success(youtube.likedPlaylists(force = true))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Result.failure(error)
        }
        result.onSuccess { outcome ->
            synchronized(playlistStateLock) {
                if (generation != playlistAccountGeneration) return@onSuccess
                when (outcome) {
                    is YouTubeLoadResult.Failure -> handleFailure(outcome.code)
                    is YouTubeLoadResult.Success -> {
                        val list = outcome.value
                        val signature = list.joinToString("|") { "${it.id}:${it.title}:${it.trackCount}:${it.thumbnailUrl}" }
                        if (signature != lastPlaylistSignature) {
                            lastPlaylistSignature = signature
                            _accountPlaylists.value = list
                        }
                        onSuccess()
                    }
                }
            }
        }.onFailure { error ->
            synchronized(playlistStateLock) {
                if (generation != playlistAccountGeneration) return@onFailure
                AppLog.w(TAG, "Playlist refresh failed", error)
                onFailure()
            }
        }
    }

    /**
     * One history merge per background cycle. The foreground five-minute timer owns the pull while
     * the app is open, so this only has to cover a play made elsewhere while it was closed.
     */
    private suspend fun syncHistory() {
        val current = runCatching { settings.settings.first() }.getOrNull() ?: return
        if (!current.youtubeSyncHistory) return
        val result = runCatching { historySync.sync() }.getOrNull() ?: return
        when {
            result.failed -> AppLog.w(TAG, "History pull failed")
            result.merged > 0 -> AppLog.i(TAG, "Background history: +${result.merged}")
        }
    }

    private suspend fun applyLikes(kept: List<YtSong>, hidden: List<String>, full: Boolean) {
        val dao = database.youtubeDao()
        val hash = kept.joinToString("|") { "${it.videoId}:${it.durationMs}:${it.title}" }.hashCode()
        val pendingBusy = pendingLikes.isNotEmpty()
        if (!full && hash == lastLikedHash && !pendingBusy) {
            // Nothing changed since the last poll; skip all database work.
            return
        }
        withContext(Dispatchers.IO) {
            val existing = dao.likedRows().associateBy { it.videoId }
            val keptIds = kept.mapTo(HashSet()) { it.videoId }
            val now = System.currentTimeMillis()

            val inserts = ArrayList<YouTubeLikeEntity>()
            kept.forEachIndexed { index, song ->
                if (existing.containsKey(song.videoId)) return@forEachIndexed
                // A like the poller missed but the user just removed must not come back in.
                if (pendingLikes[song.videoId]?.let { !it.liked && it.expiresAt > now } == true) return@forEachIndexed
                inserts += song.toYouTubeLikeEntity(likedAt = now - index)
            }

            val removals = HashSet<String>()
            hidden.forEach { id -> if (existing.containsKey(id)) removals += id }
            if (full) {
                existing.keys.forEach { id ->
                    if (id in keptIds || id in removals) return@forEach
                    if (pendingLikes[id]?.let { it.liked && it.expiresAt > now } == true) return@forEach
                    removals += id
                }
            } else {
                // Fast lane: only the ids that were in the previous page can be judged as removed.
                lastTopIds.forEach { id ->
                    if (id in keptIds || id in removals) return@forEach
                    if (pendingLikes[id]?.let { it.liked && it.expiresAt > now } == true) return@forEach
                    removals += id
                }
            }

            if (inserts.isNotEmpty() || removals.isNotEmpty()) {
                dao.applyLikeSync(inserts, removals.toList())
                if (inserts.isNotEmpty()) _ui.value = _ui.value.copy(newItemsPulse = _ui.value.newItemsPulse + 1)
                AppLog.i(TAG, "Live sync: +${inserts.size} liked / -${removals.size} removed")
            }
            lastTopIds = keptIds
        }
        lastLikedHash = hash
    }

    // ---- Failure handling ------------------------------------------------------------------

    private fun handleFailure(code: Int) {
        when {
            code == 401 || code == 403 -> {
                runCatching { auth.markExpired(caller = "livesync:$code") }
                _ui.value = _ui.value.copy(state = LiveSyncState.AuthExpired)
                enabled = false
                loopJob?.cancel()
                loopJob = null
                cancelWorker()
            }
            code == 429 -> {
                failureStreak = BACKOFF_CAP_STREAK
                _ui.value = _ui.value.copy(state = LiveSyncState.Error)
            }
            else -> onFailure()
        }
    }

    private fun onFailure() {
        if (_ui.value.state != LiveSyncState.AuthExpired) {
            _ui.value = _ui.value.copy(state = if (online) LiveSyncState.Error else LiveSyncState.Offline)
        }
        failureStreak = (failureStreak + 1).coerceAtMost(BACKOFF_CAP_STREAK)
    }

    private fun onSuccess() {
        failureStreak = 0
        val now = System.currentTimeMillis()
        // Emit the timestamp at most every 10 s, so a 4-second poll cannot recompose the UI tree on
        // every pass. A genuine state change still emits immediately.
        if (now - lastSyncedUi > UI_STAMP_INTERVAL_MS) {
            lastSyncedUi = now
            _ui.value = _ui.value.copy(state = LiveSyncState.Live, lastSyncedAt = now)
        } else if (_ui.value.state != LiveSyncState.Live) {
            _ui.value = _ui.value.copy(state = LiveSyncState.Live)
        }
        // Persist a checkpoint at the slow-lane rate so "Last synced" survives a process restart
        // without writing DataStore on every 4-second poll.
        if (now - lastSyncedPersisted > PERSIST_INTERVAL_MS) {
            lastSyncedPersisted = now
            scope.launch { runCatching { settings.update { it.copy(liveSyncLastSyncedAt = now) } } }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun ready(): Boolean = enabled && online && auth.snapshot()?.signedIn == true

    /** Readiness for the worker tier, which has no ViewModel driving [start]/[stop]. */
    private suspend fun backgroundReady(): Boolean {
        val current = runCatching { settings.settings.first() }.getOrNull() ?: return false
        return online && current.youtubeIntegration && current.liveSyncEnabled &&
            auth.snapshot()?.signedIn == true
    }

    private fun expirePending() {
        if (pendingLikes.isEmpty()) return
        val now = System.currentTimeMillis()
        pendingLikes.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun fastDelayMs(): Long {
        val base = when {
            playbackActive && !foreground -> PLAYBACK_FAST_MS
            lowPerformance -> FAST_LOW_MS
            else -> FAST_MS
        }
        return scaledDelay(base) + jitter()
    }

    private fun mediumDelayMs(): Long {
        val base = when {
            playbackActive && !foreground -> PLAYBACK_MEDIUM_MS
            lowPerformance -> MEDIUM_LOW_MS
            else -> MEDIUM_MS
        }
        return scaledDelay(base) + jitter()
    }

    /** Exponential backoff (4→8→16→32→60 s), capped, reset to the base on the next success. */
    private fun scaledDelay(baseMs: Long): Long {
        val streak = failureStreak
        if (streak == 0) return baseMs
        return (baseMs shl streak.coerceAtMost(BACKOFF_CAP_STREAK)).coerceAtMost(BACKOFF_CAP_MS)
    }

    private fun jitter(): Long = random.nextLong(-JITTER_MS, JITTER_MS + 1)

    /** Keeps the low-performance cadence (8 s / 60 s) in step with the Settings choice. */
    private fun observePerformanceProfile() {
        if (settingsJob != null) return
        settingsJob = scope.launch {
            settings.settings.collect { lowPerformance = it.performanceProfile == PerformanceMode.LOW }
        }
    }

    private fun loadPersistedLastSynced() {
        if (loadedPersisted) return
        loadedPersisted = true
        scope.launch {
            val saved = runCatching { settings.settings.first().liveSyncLastSyncedAt }.getOrDefault(0L)
            lastSyncedPersisted = saved
            if (_ui.value.lastSyncedAt == 0L) _ui.value = _ui.value.copy(lastSyncedAt = saved)
        }
    }

    private fun readOnline(): Boolean {
        val manager = connectivity ?: return true
        return runCatching {
            val network = manager.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private fun registerNetwork() {
        val manager = connectivity ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (online) return
                online = true
                if (enabled) {
                    restartLoops()
                    scope.launch { pollLikeds() }
                }
            }

            override fun onLost(network: Network) {
                val stillOnline = manager.activeNetwork != null
                if (stillOnline) return
                online = false
                if (enabled && _ui.value.state != LiveSyncState.AuthExpired) {
                    _ui.value = _ui.value.copy(state = LiveSyncState.Offline)
                }
            }
        })
    }

    // ---- WorkManager tier ------------------------------------------------------------------

    private fun scheduleWorker() {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LiveSyncWorker>(WORK_INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { AppLog.d(TAG, "Could not schedule live-sync worker: ${it.message}") }
    }

    private fun cancelWorker() {
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME) }
            .onFailure { AppLog.d(TAG, "Could not cancel live-sync worker: ${it.message}") }
    }

    companion object {
        const val WORK_NAME = "auralis_live_sync"
        const val WORK_INTERVAL_MIN = 15L
        private const val TAG = "LiveSync"
        private const val FAST_MS = 4_000L
        private const val FAST_LOW_MS = 8_000L
        private const val PLAYBACK_FAST_MS = 10_000L
        private const val MEDIUM_MS = 30_000L
        private const val MEDIUM_LOW_MS = 60_000L
        private const val PLAYBACK_MEDIUM_MS = 60_000L
        private const val SLOW_MS = 5 * 60_000L
        private const val JITTER_MS = 500L
        private const val PENDING_TTL_MS = 20_000L
        private const val BACKOFF_CAP_MS = 60_000L
        /** Shift that saturates the base interval at [BACKOFF_CAP_MS]. */
        private const val BACKOFF_CAP_STREAK = 5
        private const val PERSIST_INTERVAL_MS = 4 * 60_000L
        /** Minimum spacing between timestamp-driven UI emissions. */
        private const val UI_STAMP_INTERVAL_MS = 10_000L
    }
}
