package com.auralis.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.auralis.app.data.youtube.YOUTUBE_PLAYBACK_SCHEME
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
    private var pending: Pair<List<Track>, Int>? = null
    private val pendingAdds = LinkedHashMap<String, Track>()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

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
        if (queuedBeforeAttach != null) {
            val (queue, index) = queuedBeforeAttach
            pending = null
            val combined = (queue + pendingAdds.values).distinctBy { it.id }
            pendingAdds.clear()
            start(combined, index)
        } else if (value != null && pendingAdds.isNotEmpty()) {
            val items = pendingAdds.values.mapNotNull(::toMediaItem)
            pendingAdds.clear()
            runCatching { value.setMediaItems(items) }.onFailure { setError(it.localizedMessage ?: "Couldn't restore the queued songs") }
        }
        if (value != null) ticker = scope.launch { while (isActive) { publishPosition(); delay(500) } }
        publish()
    }

    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        if (player == null) { pendingAdds.clear(); pending = tracks.distinctBy { it.id } to index.coerceIn(0, tracks.lastIndex); updatePendingState(); return }
        start(tracks, index)
    }

    /** Restores a saved queue only after the service controller is connected. */
    fun restore(tracks: List<Track>, index: Int, positionMs: Long, wasPlaying: Boolean, shuffle: Boolean, repeatMode: Int) {
        val p = player ?: return
        try {
            p.shuffleModeEnabled = shuffle
            p.repeatMode = repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE)
            start(tracks, index, positionMs.coerceAtLeast(0L), wasPlaying)
        } catch (error: Exception) {
            Log.e(TAG, "Could not restore playback", error)
            AppLog.e("Playback", "Could not restore playback", error)
            setError(error.localizedMessage ?: "Couldn't restore playback")
        }
    }

    private fun start(tracks: List<Track>, index: Int, positionMs: Long = C.TIME_UNSET, playWhenReady: Boolean = true) {
        val p = player ?: return
        try {
            val uniqueTracks = tracks.distinctBy { it.id }
            val items = uniqueTracks.mapNotNull(::toMediaItem)
            if (items.isEmpty()) { setError("No playable tracks in this list"); return }
            val wantedId = uniqueTracks.getOrNull(index)?.id
            val targetIndex = items.indexOfFirst { it.mediaId == wantedId }.takeIf { it >= 0 } ?: 0
            val targetItem = items.getOrNull(targetIndex)
            _state.value = _state.value.copy(
                error = null,
                trackId = targetItem?.mediaId ?: wantedId,
                artworkUri = targetItem?.mediaMetadata?.artworkUri,
            )
            p.setMediaItems(items, targetIndex, positionMs)
            p.prepare()
            if (playWhenReady) p.play() else p.pause()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start playback", error)
            AppLog.e("Playback", "Could not start playback", error)
            setError(error.localizedMessage ?: "Unable to start playback")
        }
    }

    /** Appends only tracks not already present in the actual Media3 timeline. */
    fun addToQueue(tracks: List<Track>) {
        val p = player
        if (p == null) {
            val existing = (pending?.first.orEmpty().map { it.id } + pendingAdds.keys).toMutableSet()
            tracks.forEach { if (existing.add(it.id)) pendingAdds[it.id] = it }
            if (pending != null) {
                val queue = (pending!!.first + pendingAdds.values).distinctBy { it.id }
                pending = queue to pending!!.second.coerceIn(0, queue.lastIndex)
                pendingAdds.clear()
            }
            updatePendingState()
            return
        }
        val existing = (0 until p.mediaItemCount).mapTo(HashSet()) { p.getMediaItemAt(it).mediaId }
        val additions = buildList {
          tracks.forEach { track ->
            if (track.id !in existing) {
                val item = toMediaItem(track) ?: return@forEach
                add(item)
                existing += track.id
            }
          }
        }
        if (additions.isNotEmpty()) safely { it.addMediaItems(additions) }
    }

    /** Places a track immediately after the current item, moving an existing entry instead of duplicating it. */
    fun playNext(track: Track) {
        val p = player
        if (p == null) {
            val currentQueue = pending?.first.orEmpty()
            val existing = (currentQueue.map { it.id } + pendingAdds.keys).toSet()
            if (track.id in existing) return
            if (pending != null) {
                val insertAt = (pending!!.second + 1).coerceIn(0, currentQueue.size)
                pending = (currentQueue.toMutableList().apply { add(insertAt, track) }) to pending!!.second
            } else {
                pending = (listOf(track) + pendingAdds.values).distinctBy { it.id } to 0
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
        val item = toMediaItem(track) ?: return
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
            pending?.let { (queue, index) ->
                val removed = queue.indexOfFirst { it.id == trackId }
                if (removed >= 0) {
                    val next = queue.filterNot { it.id == trackId }
                    pending = if (next.isEmpty()) null else next to (if (removed < index) index - 1 else index.coerceAtMost(next.lastIndex))
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

    fun playQueueItem(trackId: String) {
        val p = player ?: return
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == trackId } ?: return
        safely { it.seekTo(index, 0L); it.play() }
    }

    /**
     * Replaces a queued item's artwork once better square art is resolved (Part J), so the
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
        player?.let { safely { p -> p.clearMediaItems() } } ?: updatePendingState()
    }
    fun toggle() = safely { if (it.isPlaying) it.pause() else it.play() }

    /** User skip to the next song: always starts playback, even if the previous song was paused. */
    fun next() = safely { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); it.play() } }

    /**
     * Standard music-player behavior: past three seconds, "previous" restarts the current song;
     * earlier than that it moves back one item (or restarts the first item).
     */
    fun previous() = safely {
        if (it.currentPosition > PREVIOUS_RESTART_MS) {
            it.seekTo(0L)
        } else if (it.hasPreviousMediaItem()) {
            it.seekToPreviousMediaItem()
        } else {
            it.seekTo(0L)
        }
        // A user skip always begins playing, whether the previous song was playing or paused.
        it.play()
    }
    fun seekTo(positionMs: Long) = safely { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = safely { it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }
    fun toggleShuffle() = safely { it.shuffleModeEnabled = !it.shuffleModeEnabled }
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

    private fun toMediaItem(track: Track): MediaItem? {
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return null
        // auralis-yt://<videoId> is rewritten to a signed audio URL by PlaybackService.
        if (uri.scheme !in setOf("content", "file", "http", "https", YOUTUBE_PLAYBACK_SCHEME)) return null
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title.ifBlank { track.displayName })
            .setArtist(track.artist.ifBlank { "Unknown artist" })
            .setAlbumTitle(track.album.ifBlank { "Unknown album" })
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
        val ids = (pending?.first.orEmpty() + pendingAdds.values).map { it.id }.distinct()
        val currentTrack = pending?.first?.getOrNull((pending?.second ?: -1).coerceAtLeast(0))
        val currentUri = currentTrack?.artworkUrl?.let { Uri.parse(normalizeArtworkUrl(it)) }
            ?: currentTrack?.artKey?.let { Uri.fromFile(File(context.filesDir, "artwork/$it.jpg")) }
        _state.value = _state.value.copy(
            queueIds = ids,
            upNextIds = ids.drop((pending?.second ?: -1).coerceAtLeast(0)),
            currentIndex = pending?.second ?: -1,
            trackId = currentTrack?.id,
            artworkUri = currentUri,
        )
    }

    private fun publish(preserveError: Boolean = false) {
        val p = player ?: run { _state.value = _state.value.copy(connected = false, playing = false, positionMs = 0, durationMs = 0); return }
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
            currentIndex = p.currentMediaItemIndex,
        )
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
        val currentIndex: Int = -1,
        val sleepTimerRemainingMs: Long = 0L,
        val error: String? = null,
    )

    private companion object {
        const val TAG = "AuralisPlayback"
        const val PREVIOUS_RESTART_MS = 3_000L
    }
}
