package com.auralis.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auralis.app.AppContainer
import com.auralis.app.data.db.PlayEventEntity
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.QueueItemEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.metadata.TagData
import com.auralis.app.data.settings.PlaybackSnapshot
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val sources: StateFlow<List<MusicSource>> = container.sources.observeSources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tracks: StateFlow<List<Track>> = container.database.trackDao().observeAll().map { rows -> rows.map { it.toDomain() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites: StateFlow<Set<String>> = container.database.favoriteDao().observeIds().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val playlists = container.database.playlistDao().observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlistEntries = container.database.playlistDao().observeEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = container.database.historyDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playEvents = container.database.playEventDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private var loadedLyricsTrackId: String? = null
    private var prefetchedLyricsTrackId: String? = null
    private var lyricsPrefetchJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var lastRecordedTrackId: String? = null

    init {
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
                    runCatching { container.database.queueDao().replaceAll(ids.mapIndexed { i, id -> QueueItemEntity(i, id, i) }) }
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
            ) { trackId, library -> trackId?.let { id -> library.firstOrNull { it.id == id } } }
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
                val next = tracks.value.firstOrNull { it.id == nextId } ?: return@collect
                prefetchedLyricsTrackId = next.id
                lyricsPrefetchJob?.cancel()
                lyricsPrefetchJob = viewModelScope.launch {
                    container.lyrics.prefetch(next)
                }
            }
        }
    }

    fun selectTab(tab: Tab) { _nav.update { it.selectTab(tab) } }
    fun push(destination: Destination) { _nav.update { it.push(destination) } }
    fun back() { _nav.update { it.pop() } }
    fun updateSettings(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { runCatching { container.settings.update(transform) }.onFailure { report("Couldn't save settings", it) } } }

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
    fun enqueuePlaylist(playlistId: Long) {
        viewModelScope.launch {
            runCatching {
                val entries = playlistEntries.value.filter { it.playlistId == playlistId }.sortedBy { it.position }
                val byId = tracks.value.associateBy { it.id }
                enqueueAll(entries.mapNotNull { byId[it.trackId] })
            }.onFailure { report("Couldn't add this playlist to the queue", it) }
        }
    }
    fun playPlaylist(playlistId: Long) {
        val entries = playlistEntries.value.filter { it.playlistId == playlistId }.sortedBy { it.position }
        val byId = tracks.value.associateBy { it.id }
        val ordered = entries.mapNotNull { byId[it.trackId] }
        ordered.firstOrNull()?.let { play(it, ordered) }
    }
    fun toggleFavorite(track: Track) { viewModelScope.launch { runCatching { container.database.favoriteDao().toggle(track.id, System.currentTimeMillis()) }.onFailure { report("Couldn't update favorite", it) } } }
    fun createPlaylist(name: String) { viewModelScope.launch {
        if (name.isBlank()) return@launch
        runCatching { container.database.playlistDao().insert(PlaylistEntity(name=name.trim(),createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis())) }
            .onSuccess { _messages.trySend("Playlist created") }.onFailure { report("Couldn't create playlist", it) }
    } }
    fun addToPlaylist(playlistId: Long, track: Track) { viewModelScope.launch { runCatching {
        val dao=container.database.playlistDao();val position=dao.maxPosition(playlistId)+1
        dao.insertEntries(listOf(PlaylistEntryEntity(playlistId=playlistId,trackId=track.id,position=position)));dao.touch(playlistId,System.currentTimeMillis())
    }.onSuccess { _messages.trySend("Added to playlist") }.onFailure { report("Couldn't add song to playlist", it) } } }

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
                // Cached lyrics first, then embedded/sidecar, then a live LRCLIB lookup.
                container.lyrics.load(track)
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
        viewModelScope.launch { runCatching {
            if (ids.isEmpty()) container.database.queueDao().clear()
            else container.database.queueDao().replaceAll(ids.mapIndexed { i, id -> QueueItemEntity(i, id, i) })
        }.onFailure { report("Couldn't save the playback queue", it) } }
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
    private companion object { const val TAG = "AuralisViewModel" }
}
