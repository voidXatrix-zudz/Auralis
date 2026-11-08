package com.auralis.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.auralis.app.AuralisApp
import com.auralis.app.R
import com.auralis.app.data.db.toDomain
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Owns playback so audio and system controls stay active when the UI is closed. It is also a
 * MediaLibraryService, which is the supported route for Android Auto browsing.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var crossfade: CrossfadeController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepTimer: Runnable? = null
    private var ticker: Runnable? = null
    private var settings = AppSettings()
    private var pendingOutputResume = false
    private var outputCallbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val p = player ?: return
            if (p.isPlaying || p.playWhenReady) {
                pendingOutputResume = true
                p.pause()
            }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!pendingOutputResume) return
            if (!settings.resumeOnOutputReconnect) {
                pendingOutputResume = false
                return
            }
            handler.postDelayed({
                if (pendingOutputResume) {
                    pendingOutputResume = false
                    player?.play()
                }
            }, RECONNECT_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Replace Media3's generic play glyph with the monochrome AURALIS waveform mark.
        val notificationProvider = DefaultMediaNotificationProvider(this)
        notificationProvider.setSmallIcon(R.drawable.ic_stat_auralis)
        setMediaNotificationProvider(notificationProvider)
        val ownedPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            volume = settings.volume
        }
        player = ownedPlayer
        crossfade = CrossfadeController(ownedPlayer)
        ownedPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.i(TAG, "audio session changed to $audioSessionId")
                AudioEffectsEngine.attach(audioSessionId, settings)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                crossfade?.onMediaItemTransition(reason)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // Some devices only expose a valid session id once the first frame is rendered,
                    // so make sure the effects are attached even if the change callback was missed.
                    AudioEffectsEngine.attach(ownedPlayer.audioSessionId, settings)
                    crossfade?.onPlaybackResumed()
                    startTicker()
                } else {
                    crossfade?.reset()
                    stopTicker()
                }
            }
        })
        AudioEffectsEngine.attach(ownedPlayer.audioSessionId, settings)

        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = MediaLibrarySession.Builder(this, ownedPlayer, libraryCallback)
        if (sessionActivity != null) builder.setSessionActivity(sessionActivity)
        try {
            session = builder.build()
        } catch (error: Throwable) {
            AppLog.e("Playback", "Could not create the media session", error)
            ownedPlayer.release()
            player = null
            throw error
        }

        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, handler) }
            .onSuccess { outputCallbackRegistered = true }
            .onFailure { Log.w(TAG, "Audio device callback unavailable", it); AppLog.w("Playback", "Audio device callback unavailable", it) }

        scope.launch {
            (application as AuralisApp).container.settings.settings.collectLatest { newSettings ->
                settings = newSettings
                player?.let { p ->
                    crossfade?.update(newSettings)
                    if (!p.isPlaying) p.volume = newSettings.volume
                    runCatching { p.playbackParameters = PlaybackParameters(newSettings.speed.coerceIn(0.5f, 2f), 1f) }
                }
                AudioEffectsEngine.apply(newSettings)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SLEEP_TIMER -> scheduleSleepTimer(intent.getLongExtra(EXTRA_SLEEP_TIMER_MS, 0L))
            ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = object : Runnable {
            override fun run() {
                crossfade?.tick()
                handler.postDelayed(this, 100L)
            }
        }.also { handler.post(it) }
    }

    private fun stopTicker() {
        ticker?.let(handler::removeCallbacks)
        ticker = null
    }

    private fun scheduleSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        if (durationMs <= 0L) return
        sleepTimer = Runnable { player?.pause(); sleepTimer = null }
        handler.postDelayed(sleepTimer!!, durationMs.coerceAtMost(MAX_SLEEP_TIMER_MS))
    }

    private fun cancelSleepTimer() {
        sleepTimer?.let(handler::removeCallbacks)
        sleepTimer = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        cancelSleepTimer()
        stopTicker()
        scope.cancel()
        AudioEffectsEngine.release()
        if (outputCallbackRegistered) {
            runCatching { (getSystemService(AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(deviceCallback) }
            outputCallbackRegistered = false
        }
        player?.let { owned ->
            session?.run {
                // MediaSessionService releases the player through the session on some versions.
                runCatching { release() }
            }
            runCatching { owned.release() }
        }
        session = null
        player = null
        crossfade = null
        super.onDestroy()
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseItem(ROOT, "AURALIS"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val items = runBlocking(Dispatchers.IO) { children(parentId, page, pageSize) }
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = runBlocking(Dispatchers.IO) { itemFor(mediaId) }
            return Futures.immediateFuture(
                if (item == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                else LibraryResult.ofItem(item, null),
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            Futures.immediateFuture(LibraryResult.ofVoid(params))

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val results = runBlocking(Dispatchers.IO) {
                val search = query.trim()
                if (search.isBlank()) return@runBlocking emptyList()
                val tracks = containerTracks()
                tracks.filter {
                    it.title.contains(search, true) || it.artist.contains(search, true) ||
                        it.album.contains(search, true) || it.genres.any { genre -> genre.contains(search, true) }
                }.drop(page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).take(pageSize.coerceAtLeast(50))
                    .map(::toMediaItem)
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(results, params))
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
    }

    private fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val safeSize = pageSize.coerceIn(1, 500)
        val offset = page.coerceAtLeast(0) * safeSize
        return when {
            parentId == ROOT -> listOf(
                browseItem(LIBRARY, "All songs"),
                browseItem(ALBUMS, "Albums"),
                browseItem(ARTISTS, "Artists"),
                browseItem(PLAYLISTS, "Playlists"),
            )
            parentId == LIBRARY -> containerTracks().drop(offset).take(safeSize).map(::toMediaItem)
            parentId == ALBUMS -> containerTracks().groupBy { it.album.ifBlank { "Unknown album" } }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER).keys.drop(offset).take(safeSize)
                .map { browseItem("album:$it", it) }
            parentId == ARTISTS -> containerTracks().groupBy { it.artist.ifBlank { "Unknown artist" } }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER).keys.drop(offset).take(safeSize)
                .map { browseItem("artist:$it", it) }
            parentId == PLAYLISTS -> runBlocking(Dispatchers.IO) {
                (application as AuralisApp).container.database.playlistDao().getAll()
                    .drop(offset).take(safeSize).map { browseItem("playlist:${it.id}", it.name) }
            }
            parentId.startsWith("album:") -> containerTracks()
                .filter { it.album.ifBlank { "Unknown album" } == parentId.removePrefix("album:") }
                .sortedWith(compareBy<Track> { it.discNumber ?: 0 }.thenBy { it.trackNumber ?: Int.MAX_VALUE })
                .drop(offset).take(safeSize).map(::toMediaItem)
            parentId.startsWith("artist:") -> containerTracks()
                .filter { it.artist.ifBlank { "Unknown artist" } == parentId.removePrefix("artist:") }
                .sortedBy { it.title.lowercase() }.drop(offset).take(safeSize).map(::toMediaItem)
            parentId.startsWith("playlist:") -> {
                val playlistId = parentId.removePrefix("playlist:").toLongOrNull() ?: return emptyList()
                val db = (application as AuralisApp).container.database
                val entries = runBlocking(Dispatchers.IO) { db.playlistDao().getAllEntries().filter { it.playlistId == playlistId } }
                val byId = containerTracks().associateBy { it.id }
                entries.sortedBy { it.position }.mapNotNull { byId[it.trackId] }.drop(offset).take(safeSize).map(::toMediaItem)
            }
            else -> emptyList()
        }
    }

    private fun itemFor(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> browseItem(ROOT, "AURALIS")
        mediaId == LIBRARY -> browseItem(LIBRARY, "All songs")
        mediaId == ALBUMS -> browseItem(ALBUMS, "Albums")
        mediaId == ARTISTS -> browseItem(ARTISTS, "Artists")
        mediaId == PLAYLISTS -> browseItem(PLAYLISTS, "Playlists")
        mediaId.startsWith("album:") || mediaId.startsWith("artist:") ->
            browseItem(mediaId, mediaId.substringAfter(':'))
        mediaId.startsWith("playlist:") -> runBlocking(Dispatchers.IO) {
            val id = mediaId.removePrefix("playlist:").toLongOrNull() ?: return@runBlocking null
            (application as AuralisApp).container.database.playlistDao().getAll().firstOrNull { it.id == id }
                ?.let { browseItem(mediaId, it.name) }
        }
        else -> containerTracks().firstOrNull { it.id == mediaId }?.let(::toMediaItem)
    }

    private fun containerTracks(): List<Track> = runBlocking(Dispatchers.IO) {
        (application as AuralisApp).container.database.trackDao().getAllNow().map { it.toDomain() }
    }

    private fun browseItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun toMediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title.ifBlank { track.displayName })
            .setArtist(track.artist.ifBlank { "Unknown artist" })
            .setAlbumTitle(track.album.ifBlank { "Unknown album" })
            .setIsBrowsable(false)
            .setIsPlayable(true)
        val artwork = track.artKey?.let { File(filesDir, "artwork/$it.jpg").takeIf(File::isFile) }
        if (artwork != null) metadata.setArtworkUri(Uri.fromFile(artwork))
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.uri))
            .setMimeType(track.mime.takeIf { it.startsWith("audio/") })
            .setMediaMetadata(metadata.build())
            .build()
    }

    companion object {
        const val ACTION_START_SLEEP_TIMER = "com.auralis.app.action.START_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.auralis.app.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MS = "sleep_timer_duration_ms"
        private const val MAX_SLEEP_TIMER_MS = 12 * 60 * 60 * 1_000L
        private const val RECONNECT_DELAY_MS = 700L
        private const val TAG = "AuralisService"
        private const val ROOT = "auralis_root"
        private const val LIBRARY = "auralis_library"
        private const val ALBUMS = "auralis_albums"
        private const val ARTISTS = "auralis_artists"
        private const val PLAYLISTS = "auralis_playlists"
    }
}
