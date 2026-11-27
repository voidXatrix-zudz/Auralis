package com.auralis.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.auralis.app.data.youtube.ResolvedStream
import com.auralis.app.data.youtube.ResolvedStreams
import com.auralis.app.data.youtube.YOUTUBE_PLAYBACK_SCHEME
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import com.auralis.app.playback.PlaybackService.Companion.ACTION_CANCEL_SLEEP_TIMER
import com.auralis.app.playback.PlaybackService.Companion.ACTION_START_SLEEP_TIMER
import com.auralis.app.playback.PlaybackService.Companion.EXTRA_SLEEP_TIMER_MS
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI bridge to the service-owned MediaController. Queue state always comes from its Media3 timeline. */
class PlaybackController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: Player? = null
    private var ticker: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerDeadlineMs = 0L

    /** A queue handed over before the service controller connected, with its context and roles. */
    private data class PendingQueue(
        val tracks: List<Track>,
        val index: Int,
        val context: PlaybackContext?,
        val autoplayIds: Set<String>,
        val shuffle: Boolean = false,
    )

    private var pending: PendingQueue? = null
    private val pendingAdds = LinkedHashMap<String, Track>()
    private val pendingAutoplayIds = mutableSetOf<String>()

    /**
     * Shuffle intent while no controller is connected yet; applied on [attach]. Once a player
     * exists, Media3's own [Player.getShuffleModeEnabled] is the single source of truth, so the
     * in-app toggle, the notification button and the service can never disagree.
     */
    private var pendingShuffle: Boolean? = null

    /** The one command the service understands for rebuilding the anchored shuffle order. */
    private val anchorShuffleCommand = SessionCommand(PlaybackService.ACTION_ANCHOR_SHUFFLE, Bundle.EMPTY)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        // The resolver publishes the winning stream as soon as it resolves (including a prefetch of
        // the next song), so the current song's real format becomes visible the moment it lands.
        scope.launch {
            ResolvedStreams.streams.collect { applyResolvedStream() }
        }
    }

    /** The video id of the current track, or null for a local file. */
    private fun currentVideoId(): String? =
        _state.value.trackId
            ?.takeIf { it.startsWith(YOUTUBE_TRACK_PREFIX) }
            ?.removePrefix(YOUTUBE_TRACK_PREFIX)

    /**
     * Refreshes [State.resolvedStream] from the registry for the current track. Keyed by the current
     * video id, so a track change leaves the old format behind and reads as unresolved until the new
     * song's own stream is published.
     */
    private fun applyResolvedStream() {
        val stream = ResolvedStreams.current(currentVideoId())
        if (_state.value.resolvedStream != stream) {
            _state.value = _state.value.copy(resolvedStream = stream)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = publish()
        override fun onEvents(player: Player, events: Player.Events) = publish()
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed", error)
            AppLog.e("Playback", "Playback failed", error)
            _state.value = _state.value.copy(error = error.localizedMessage ?: "Unable to play this file")
            publish()
        }
    }

    fun attach(value: Player?) {
        if (player === value) return
        player?.removeListener(listener)
        ticker?.cancel()
        player = value
        value?.addListener(listener)
        val queuedBeforeAttach = pending
        // The service keeps the shuffle flag; restore the intent captured while disconnected before
        // the queue is handed over, so start() can anchor on the right song.
        val shuffleIntent = pendingShuffle ?: queuedBeforeAttach?.shuffle
        pendingShuffle = null
        if (value != null && shuffleIntent != null) value.shuffleModeEnabled = shuffleIntent
        if (queuedBeforeAttach != null) {
            pending = null
            val combined = (queuedBeforeAttach.tracks + pendingAdds.values).distinctBy { it.id }
            val autoplay = queuedBeforeAttach.autoplayIds + pendingAutoplayIds
            pendingAdds.clear()
            pendingAutoplayIds.clear()
            if (combined.isNotEmpty()) {
                start(
                    combined,
                    queuedBeforeAttach.index.coerceIn(0, combined.lastIndex),
                    queuedBeforeAttach.context,
                    autoplayIds = autoplay,
                )
            }
        } else if (value != null && pendingAdds.isNotEmpty()) {
            val items = pendingAdds.values.mapNotNull { toMediaItem(it, roleOf(it.id, pendingAutoplayIds)) }
            pendingAdds.clear()
            pendingAutoplayIds.clear()
            runCatching { value.setMediaItems(items) }.onFailure { setError(it.localizedMessage ?: "Couldn't restore the queued songs") }
            if (value.shuffleModeEnabled) requestAnchoredShuffle()
        }
        if (value != null) ticker = scope.launch { while (isActive) { publishPosition(); delay(500) } }
        publish()
    }

    fun play(tracks: List<Track>, index: Int, context: PlaybackContext? = null) {
        if (tracks.isEmpty()) return
        if (player == null) {
            pendingAdds.clear()
            pendingAutoplayIds.clear()
            pending = PendingQueue(
                tracks = tracks.distinctBy { it.id },
                index = index.coerceIn(0, tracks.lastIndex),
                context = context,
                autoplayIds = emptySet(),
                shuffle = _state.value.shuffle,
            )
            updatePendingState()
            return
        }
        start(tracks, index, context)
    }

    /** Restores a saved queue only after the service controller is connected. */
    fun restore(
        tracks: List<Track>,
        index: Int,
        positionMs: Long,
        wasPlaying: Boolean,
        shuffle: Boolean,
        repeatMode: Int,
        context: PlaybackContext? = null,
    ) {
        val p = player ?: return
        try {
            // The service owns the flag; setting it here means start() anchors the restored queue
            // on the saved song instead of on Media3's unanchored default permutation.
            p.shuffleModeEnabled = shuffle
            p.repeatMode = repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE)
            start(tracks, index, context, positionMs.coerceAtLeast(0L), wasPlaying)
        } catch (error: Exception) {
            Log.e(TAG, "Could not restore playback", error)
            AppLog.e("Playback", "Could not restore playback", error)
            setError(error.localizedMessage ?: "Couldn't restore playback")
        }
    }

    private fun start(
        tracks: List<Track>,
        index: Int,
        context: PlaybackContext?,
        positionMs: Long = C.TIME_UNSET,
        playWhenReady: Boolean = true,
        autoplayIds: Set<String> = emptySet(),
    ) {
        val p = player ?: return
        try {
            val uniqueTracks = tracks.distinctBy { it.id }
            val built = uniqueTracks.mapNotNull { toMediaItem(it, roleOf(it.id, autoplayIds)) }
            if (built.isEmpty()) { setError("No playable tracks in this list"); return }
            val wantedId = uniqueTracks.getOrNull(index)?.id
            // Media3 keeps the shuffle FLAG; the service installs the anchored permutation. A new
            // setMediaItems resets that order, so start() asks for a re-anchor on the tapped song.
            val targetIndex = built.indexOfFirst { it.mediaId == wantedId }.takeIf { it >= 0 } ?: 0
            val targetItem = built.getOrNull(targetIndex)
            _state.value = _state.value.copy(
                error = null,
                context = context,
                shuffle = p.shuffleModeEnabled,
                trackId = targetItem?.mediaId ?: wantedId,
                artworkUri = targetItem?.mediaMetadata?.artworkUri,
            )
            // Clear/refresh the format for the newly selected song immediately, so the first frame
            // after a tap never shows the previous song's format.
            applyResolvedStream()
            p.setMediaItems(built, targetIndex, positionMs)
            p.prepare()
            if (playWhenReady) p.play() else p.pause()
            requestAnchoredShuffle()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start playback", error)
            AppLog.e("Playback", "Could not start playback", error)
            setError(error.localizedMessage ?: "Unable to start playback")
        }
    }

    /**
     * Appends only tracks not already present in the actual timeline. Context items go before the
     * first autoplay item so the user's explicitly queued songs stay reachable; autoplay (radio)
     * items go at the very end.
     */
    fun addToQueue(tracks: List<Track>, role: String = QUEUE_ROLE_CONTEXT) {
        val p = player
        if (p == null) {
            val existing = (pending?.tracks.orEmpty().map { it.id } + pendingAdds.keys).toMutableSet()
            tracks.forEach {
                if (existing.add(it.id)) {
                    pendingAdds[it.id] = it
                    if (role == QUEUE_ROLE_AUTOPLAY) pendingAutoplayIds += it.id
                }
            }
            pending?.let { queued ->
                val queue = (queued.tracks + pendingAdds.values).distinctBy { it.id }
                pending = queued.copy(tracks = queue, index = queued.index.coerceIn(0, queue.lastIndex))
                pendingAdds.clear()
            }
            updatePendingState()
            return
        }
        val existing = (0 until p.mediaItemCount).mapTo(HashSet()) { p.getMediaItemAt(it).mediaId }
        val additions = buildList {
            tracks.forEach { track ->
                if (track.id !in existing) {
                    toMediaItem(track, role)?.let {
                        add(it)
                        existing += track.id
                    }
                }
            }
        }
        if (additions.isEmpty()) return
        val insertAt = if (role == QUEUE_ROLE_AUTOPLAY) p.mediaItemCount else firstAutoplayIndex(p)
        // Appending keeps the current song playing; Media3 extends the live shuffle order over the
        // new entries, so they are reachable exactly once like every other item.
        safely { it.addMediaItems(insertAt.coerceIn(0, it.mediaItemCount), additions) }
    }

    /** Appends recommendation tracks after the context block (never interleaved with it). */
    fun addAutoplay(tracks: List<Track>) = addToQueue(tracks, QUEUE_ROLE_AUTOPLAY)

    /** Places a track immediately after the current item, moving an existing entry instead of duplicating it. */
    fun playNext(track: Track) {
        val p = player
        if (p == null) {
            val currentQueue = pending?.tracks.orEmpty()
            val existing = (currentQueue.map { it.id } + pendingAdds.keys).toSet()
            if (track.id in existing) return
            if (pending != null) {
                val insertAt = (pending!!.index + 1).coerceIn(0, currentQueue.size)
                pending = pending!!.copy(tracks = currentQueue.toMutableList().apply { add(insertAt, track) })
            } else {
                pending = PendingQueue(
                    tracks = (listOf(track) + pendingAdds.values).distinctBy { it.id },
                    index = 0,
                    context = _state.value.context,
                    autoplayIds = emptySet(),
                )
                pendingAdds.clear()
            }
            updatePendingState()
            return
        }
        val current = p.currentMediaItemIndex
        val target = if (current >= 0) current + 1 else 0
        val existingIndex = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == track.id }
        if (existingIndex != null) {
            if (existingIndex == current || existingIndex == target) return
            val destination = if (existingIndex < current) current else target
            safely { it.moveMediaItem(existingIndex, destination.coerceIn(0, it.mediaItemCount - 1)) }
            return
        }
        val item = toMediaItem(track, QUEUE_ROLE_CONTEXT) ?: return
        if (p.mediaItemCount == 0) {
            safely { it.setMediaItem(item); it.prepare(); it.play() }
            return
        }
        safely { it.addMediaItem(target.coerceIn(0, it.mediaItemCount), item) }
    }

    fun removeFromQueue(trackId: String) {
        val p = player
        if (p == null) {
            pendingAdds.remove(trackId)
            pendingAutoplayIds -= trackId
            pending?.let { queued ->
                val removed = queued.tracks.indexOfFirst { it.id == trackId }
                if (removed >= 0) {
                    val next = queued.tracks.filterNot { it.id == trackId }
                    pending = if (next.isEmpty()) null else queued.copy(
                        tracks = next,
                        index = if (removed < queued.index) queued.index - 1 else queued.index.coerceAtMost(next.lastIndex),
                    )
                }
            }
            updatePendingState()
            return
        }
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == trackId } ?: return
        safely { it.removeMediaItem(index) }
    }

    fun moveQueueItem(fromTrackId: String, toIndex: Int) {
        val p = player ?: return
        val from = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == fromTrackId } ?: return
        if (p.mediaItemCount < 2) return
        safely { it.moveMediaItem(from, toIndex.coerceIn(0, it.mediaItemCount - 1)) }
    }

    /**
     * Plays a queue entry the user tapped in Up Next. With shuffle on the order is re-anchored on
     * that song, so it is first and the rest of the queue is freshly randomised behind it.
     */
    fun playQueueItem(trackId: String) {
        val p = player ?: return
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == trackId } ?: return
        safely { it.seekTo(index, 0L); it.play() }
        requestAnchoredShuffle()
    }

    /**
     * Replaces the context block of the live queue (a playlist edit, or an explicit "add to this
     * playlist" tap) while keeping the song that is playing, its position and every autoplay item.
     * The autoplay tail stays after the new context so Next still walks the edited list first.
     */
    fun syncContext(tracks: List<Track>) {
        val p = player ?: return
        val unique = tracks.distinctBy { it.id }
        if (unique.isEmpty()) return
        val currentId = p.currentMediaItem?.mediaId
        val position = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.isPlaying
        val contextItems = unique.mapNotNull { toMediaItem(it, QUEUE_ROLE_CONTEXT) }
        val tail = (firstAutoplayIndex(p) until p.mediaItemCount).map { p.getMediaItemAt(it) }
        val base = contextItems + tail
        runCatching {
            val targetIndex = base.indexOfFirst { it.mediaId == currentId }.takeIf { it >= 0 } ?: 0
            p.setMediaItems(base, targetIndex, position)
            p.prepare()
            if (wasPlaying) p.play() else p.pause()
            requestAnchoredShuffle()
        }.onFailure {
            Log.w(TAG, "Could not sync the queue with the playlist", it)
            AppLog.w("Playback", "Could not sync the queue with the playlist", it)
        }
    }

    /**
     * Replaces a queued item's artwork once better square art is resolved, so the
     * notification and lock screen show the same image as the app.
     */
    fun updateArtwork(trackId: String, artworkUrl: String) {
        val p = player ?: return
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == trackId } ?: return
        val item = p.getMediaItemAt(index)
        val metadata = item.mediaMetadata.buildUpon().setArtworkUri(Uri.parse(artworkUrl)).build()
        safely { it.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build()) }
    }

    fun clearQueue() {
        pending = null
        pendingAdds.clear()
        pendingAutoplayIds.clear()
        player?.let { safely { p -> p.clearMediaItems() } } ?: updatePendingState()
    }
    fun toggle() = safely { if (it.isPlaying) it.pause() else it.play() }

    /**
     * Next/Previous are deliberately thin: the service's [PlaybackService.TransportPlayer] owns the
     * one policy (context order, repeat, autoplay, shuffle history) so the in-app buttons, the
     * mini-player, the notification, the lock screen, headsets and Android Auto cannot disagree.
     */
    fun next() = safely { it.seekToNextMediaItem() }
    fun previous() = safely { it.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) = safely { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = safely { it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }
    fun toggleShuffle() {
        val p = player
        if (p == null) {
            val next = !_state.value.shuffle
            pendingShuffle = next
            _state.value = _state.value.copy(shuffle = next)
            return
        }
        // The service owns the flag and re-anchors its shuffle order; publish() below reads back
        // the real state, so the UI and the notification glyph cannot disagree.
        safely { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Turns shuffle on or off explicitly (Playlist ⋮ "Shuffle", YouTube playlist Shuffle). */
    fun setShuffleEnabled(enabled: Boolean) {
        val p = player
        if (p == null) {
            pendingShuffle = enabled
            _state.value = _state.value.copy(shuffle = enabled)
            return
        }
        if (p.shuffleModeEnabled == enabled) {
            publish()
            return
        }
        safely { it.shuffleModeEnabled = enabled }
    }
    fun cycleRepeat() = safely {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun startSleepTimer(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 720)
        val durationMs = safeMinutes * 60_000L
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START_SLEEP_TIMER
                putExtra(EXTRA_SLEEP_TIMER_MS, durationMs)
            })
        }.onFailure { error -> setError(error.localizedMessage ?: "Couldn't start the sleep timer"); return }
        sleepTimerJob?.cancel()
        sleepTimerDeadlineMs = SystemClock.elapsedRealtime() + durationMs
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (sleepTimerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
                if (remaining == 0L) break
                delay(minOf(1_000L, remaining))
            }
        }
    }

    fun cancelSleepTimer() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_CANCEL_SLEEP_TIMER)) }
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerDeadlineMs = 0L
        _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
    }

    private fun roleOf(trackId: String, autoplayIds: Set<String>): String =
        if (trackId in autoplayIds) QUEUE_ROLE_AUTOPLAY else QUEUE_ROLE_CONTEXT

    private fun firstAutoplayIndex(p: Player): Int =
        (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).isAutoplayItem() } ?: p.mediaItemCount

    /**
     * Asks the service to rebuild its anchored shuffle permutation around the current song. Sent
     * after every queue hand-over while shuffle is on; the service ignores it when shuffle is off,
     * so this can be called unconditionally.
     */
    private fun requestAnchoredShuffle() {
        val p = player as? MediaController ?: return
        if (!p.shuffleModeEnabled) return
        runCatching { p.sendCustomCommand(anchorShuffleCommand, Bundle.EMPTY) }
            .onFailure { AppLog.w("Playback", "Could not anchor the shuffle order", it) }
    }

    private fun toMediaItem(track: Track, role: String): MediaItem? {
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return null
        // auralis-yt://<videoId> is rewritten to a signed audio URL by PlaybackService.
        if (uri.scheme !in setOf("content", "file", "http", "https", YOUTUBE_PLAYBACK_SCHEME)) return null
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title.ifBlank { track.displayName })
            .setArtist(track.artist.ifBlank { "Unknown artist" })
            .setAlbumTitle(track.album.ifBlank { "Unknown album" })
            .setExtras(Bundle().apply { putString(QUEUE_ROLE_EXTRA, role) })
        val remoteArtwork = track.artworkUrl?.let(::normalizeArtworkUrl)
        if (!remoteArtwork.isNullOrBlank()) {
            metadata.setArtworkUri(Uri.parse(remoteArtwork))
        } else {
            val artworkFile = track.artKey?.let { File(context.filesDir, "artwork/$it.jpg") }
            if (artworkFile?.isFile == true) metadata.setArtworkUri(Uri.fromFile(artworkFile))
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            // Remote songs are WebM/Opus; declaring it keeps Media3 on the progressive path.
            .setMimeType(track.mime.takeIf { it.startsWith("audio/") })
            .setMediaMetadata(metadata.build())
            .build()
    }

    private inline fun safely(action: (Player) -> Unit) {
        val p = player ?: return
        try { action(p); _state.value = _state.value.copy(error = null); publish() }
        catch (error: Exception) { Log.w(TAG, "Playback control failed", error); AppLog.w("Playback", "Playback control failed", error); setError(error.localizedMessage ?: "Playback control failed") }
    }

    private fun setError(message: String) { _state.value = _state.value.copy(error = message) }

    private fun updatePendingState() {
        val ordered = (pending?.tracks.orEmpty() + pendingAdds.values).distinctBy { it.id }
        val currentTrack = pending?.tracks?.getOrNull((pending?.index ?: -1).coerceAtLeast(0))
        val currentUri = currentTrack?.artworkUrl?.let { Uri.parse(normalizeArtworkUrl(it)) }
            ?: currentTrack?.artKey?.let { Uri.fromFile(File(context.filesDir, "artwork/$it.jpg")) }
        _state.value = _state.value.copy(
            queueIds = ordered.map { it.id },
            upNextIds = ordered.drop((pending?.index ?: -1).coerceAtLeast(0)).map { it.id },
            contextIds = ordered.filterNot { it.id in pendingAutoplayIds }.map { it.id },
            autoplayIds = ordered.filter { it.id in pendingAutoplayIds }.map { it.id },
            currentIndex = pending?.index ?: -1,
            trackId = currentTrack?.id,
            artworkUri = currentUri,
        )
        applyResolvedStream()
    }

    private fun publish(preserveError: Boolean = false) {
        val p = player ?: run { _state.value = _state.value.copy(connected = false, playing = false, positionMs = 0, durationMs = 0); return }
        val contextIds = mutableListOf<String>()
        val autoplayIds = mutableListOf<String>()
        for (index in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(index)
            if (item.isAutoplayItem()) autoplayIds += item.mediaId else contextIds += item.mediaId
        }
        val timelineIds = buildList { for (index in 0 until p.mediaItemCount) add(p.getMediaItemAt(index).mediaId) }
        val nextIds = mutableListOf<String>()
        val seen = mutableSetOf<Int>()
        if (p.currentMediaItemIndex >= 0) seen += p.currentMediaItemIndex
        var next = p.nextMediaItemIndex
        while (next >= 0 && next < p.mediaItemCount && seen.add(next) && nextIds.size < minOf(20, p.mediaItemCount)) {
            nextIds += p.getMediaItemAt(next).mediaId
            next = p.currentTimeline.getNextWindowIndex(next, p.repeatMode, p.shuffleModeEnabled)
        }
        val artUri = p.mediaMetadata.artworkUri
            ?: p.currentMediaItem?.mediaMetadata?.artworkUri
            ?: _state.value.artworkUri
        _state.value = _state.value.copy(
            connected = true,
            trackId = p.currentMediaItem?.mediaId ?: _state.value.trackId,
            artworkUri = artUri,
            playing = p.isPlaying,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it >= 0 } ?: 0,
            queueIds = timelineIds,
            upNextIds = nextIds,
            contextIds = contextIds,
            autoplayIds = autoplayIds,
            currentIndex = p.currentMediaItemIndex,
        )
        applyResolvedStream()
    }

    private fun publishPosition() {
        val p = player ?: return
        val artUri = p.mediaMetadata.artworkUri
            ?: p.currentMediaItem?.mediaMetadata?.artworkUri
            ?: _state.value.artworkUri
        _state.value = _state.value.copy(
            connected = true,
            trackId = p.currentMediaItem?.mediaId ?: _state.value.trackId,
            artworkUri = artUri,
            playing = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it >= 0 } ?: 0,
        )
        applyResolvedStream()
    }

    data class State(
        val connected: Boolean = false,
        val trackId: String? = null,
        val artworkUri: Uri? = null,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val queueIds: List<String> = emptyList(),
        val upNextIds: List<String> = emptyList(),
        /** Queue ids that belong to the list the user started from, in saved order. */
        val contextIds: List<String> = emptyList(),
        /** Queue ids contributed by the radio/autoplay engine, after the context. */
        val autoplayIds: List<String> = emptyList(),
        /** The list playback started from; null for legacy/unlabelled playback. */
        val context: PlaybackContext? = null,
        val currentIndex: Int = -1,
        val sleepTimerRemainingMs: Long = 0L,
        /** The resolved audio stream for the current song, or null until it has been resolved. */
        val resolvedStream: ResolvedStream? = null,
        val error: String? = null,
    )

    private companion object {
        const val TAG = "AuralisPlayback"
    }
}