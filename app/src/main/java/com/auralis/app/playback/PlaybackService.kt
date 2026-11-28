package com.auralis.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.auralis.app.AuralisApp
import com.auralis.app.R
import com.auralis.app.data.db.YouTubeLikeEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.youtube.ResolverKind
import com.auralis.app.data.youtube.StreamResolverStatus
import com.auralis.app.data.youtube.YOUTUBE_PLAYBACK_SCHEME
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.data.youtube.YOUTUBE_USER_AGENT
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.data.youtube.videoIdFromPlaybackUri
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
 * Predefined MediaSession custom command behind the notification's heart button. Defined at file
 * scope so both the session callback and the notification provider can recognise it.
 */
private const val ACTION_LIKE = "com.auralis.app.action.LIKE"

/** Notification shuffle button: the service owns the flag and re-anchors the order itself. */
private const val ACTION_SHUFFLE = "com.auralis.app.action.SHUFFLE"

/**
 * Whether the currently playing media id is a favourite. Local library songs live in `favorites`,
 * streamed YouTube songs are keyed by their video id in the liked-songs table. Pure so it can be
 * unit-tested without an Android runtime.
 */
internal fun resolveLiked(
    mediaId: String?,
    favoriteIds: Set<String>,
    youtubeLikedIds: Set<String>,
): Boolean {
    val id = mediaId?.takeIf { it.isNotBlank() } ?: return false
    return if (id.startsWith(YOUTUBE_TRACK_PREFIX)) {
        youtubeLikedIds.contains(id.removePrefix(YOUTUBE_TRACK_PREFIX))
    } else {
        favoriteIds.contains(id)
    }
}

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
    /** Set while a user-initiated skip is resolving; cleared once playback actually starts or on pause. */
    private var autoPlayOnReady = false
    /** Consecutive "stream failed → skipped to next" attempts, to avoid an infinite failure loop. */
    private var skipRetries = 0
    /** per-song stream-resolve retries before giving up and skipping. */
    private var retryMediaId: String? = null
    private var retryCount = 0
    /** Tap generation for which a "tap → audible" line was logged, so it is written once per tap. */
    private var audibleLoggedGeneration: Int = -1
    /** Set for the next data-source open when a stream error triggers a re-resolve. */
    @Volatile private var forcedResolver: ResolverKind? = null
    @Volatile private var bypassResolveCache = false

    /** Favourite ids (local library) and liked-song ids (YouTube) mirrored from Room. */
    private var favoriteIds: Set<String> = emptySet()
    private var youtubeLikedIds: Set<String> = emptySet()
    /** Last heart state pushed to the notification, so the button is only rebuilt when it flips. */
    private var likeButtonState = false
    /** Last shuffle state pushed to the notification, so the shuffle glyph only flips when it changes. */
    private var shuffleButtonState = false
    /** True while a favourite write is in flight, so rapid taps cannot interleave. */
    private var likeToggleInFlight = false
    /** Custom MediaSession command behind the notification heart button. */
    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    /** Custom MediaSession command behind the notification shuffle button. */
    private val shuffleCommand = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
    /**
     * Guards the anchored shuffle order against a timeline/notification loop: [ExoPlayer.setShuffleOrder]
     * re-shapes the timeline, which must never trigger another anchor.
     */
    private var anchoringShuffle = false

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
        // Replace Media3's generic play glyph with the monochrome AURALIS waveform mark, and put
        // the Like heart ahead of the transport buttons.
        val notificationProvider = AuralisMediaNotificationProvider(this)
        notificationProvider.setSmallIcon(R.drawable.ic_stat_auralis)
        setMediaNotificationProvider(notificationProvider)
        // Remote queue items carry auralis-yt://<videoId>. The resolving source swaps in the signed
        // URL on Media3's loading thread, so the queue can be built instantly and streams resolve
        // just in time without blocking the UI.
        // HTTP(S) audio goes through the ONE shared OkHttp client. That client's connection pool is
        // already warmed by stream prefetching, so the first frame no longer pays DNS + TCP + TLS to
        // the googlevideo host. content:// and file:// still use the default data sources.
        val httpFactory = OkHttpDataSource.Factory((application as AuralisApp).container.playbackHttp)
            .setUserAgent(YOUTUBE_USER_AGENT)
        val resolvingFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory),
        ) { dataSpec ->
            resolveRemoteUri(dataSpec)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                PLAYBACK_REBUFFER_MS,
            )
            .build()
        val ownedPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                volume = settings.volume
            }
        player = ownedPlayer
        crossfade = CrossfadeController(ownedPlayer)
        ownedPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.i(TAG, "audio session changed to $audioSessionId")
                runCatching { AudioEffectsEngine.attach(audioSessionId, settings) }
                    .onFailure { Log.w(TAG, "Could not attach audio effects", it) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                crossfade?.onMediaItemTransition(reason)
                // A new song gets a fresh stream-retry budget.
                if (mediaItem?.mediaId != retryMediaId) {
                    retryMediaId = mediaItem?.mediaId
                    retryCount = 0
                    forcedResolver = null
                    bypassResolveCache = false
                }
                // The heart always reflects the song that is playing now, never the previous one.
                refreshMediaButtons()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // Turning shuffle on (from the app, the notification, Bluetooth, Auto or the lock
                // screen) re-anchors the context block on the song that is playing.
                applyAnchoredShuffle()
                refreshMediaButtons()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Live sync uses a slower cadence while a song is playing (still inside this FGS, so
                // no second notification is ever created).
                runCatching { (application as AuralisApp).container.liveSync.setPlaybackActive(isPlaying) }
                if (isPlaying) {
                    // A skip's play landed; the latch is no longer needed.
                    autoPlayOnReady = false
                    // One end-to-end "tap → audible" line per tap, for the Logs screen. The
                    // generation (not the media id) is the key so re-tapping the same song logs again.
                    val gen = PlaybackTiming.tapGeneration()
                    if (gen != audibleLoggedGeneration && PlaybackTiming.isFreshTap()) {
                        audibleLoggedGeneration = gen
                        AppLog.i("Timing", "tap→audible in ${PlaybackTiming.sinceTap()} ms")
                    }
                    // Some devices only expose a valid session id once the first frame is rendered,
                    // so make sure the effects are attached even if the change callback was missed.
                    runCatching { AudioEffectsEngine.attach(ownedPlayer.audioSessionId, settings) }
                        .onFailure { Log.w(TAG, "Could not attach audio effects", it) }
                    crossfade?.onPlaybackResumed()
                    startTicker()
                } else {
                    crossfade?.reset()
                    stopTicker()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    skipRetries = 0
                    if (PlaybackTiming.isFreshTap()) {
                        AppLog.i("Timing", "tap→ready in ${PlaybackTiming.sinceTap()} ms")
                    }
                    // Lazy resolution finished: honour a pending user skip by starting playback
                    // unless the user paused it in the meantime (which clears the latch).
                    if (autoPlayOnReady) {
                        autoPlayOnReady = false
                        ownedPlayer.play()
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val mediaId = ownedPlayer.currentMediaItem?.mediaId
                if (retryMediaId != mediaId) {
                    retryMediaId = mediaId
                    retryCount = 0
                }
                // a remote stream can fail because its signed URL expired or was blocked
                // (403/410) or the source broke. Re-resolve with the OTHER resolver and resume the
                // same position, respecting a user pause, up to MAX_STREAM_RETRIES times per song.
                if (mediaId?.startsWith(YOUTUBE_TRACK_PREFIX) == true &&
                    retryCount < MAX_STREAM_RETRIES &&
                    isRetryableStreamError(error)
                ) {
                    retryCount++
                    val base = resolverFromStatus()
                    forcedResolver = if (retryCount == 1) base.other else base
                    bypassResolveCache = true
                    val position = ownedPlayer.currentPosition
                    val resume = ownedPlayer.playWhenReady
                    AppLog.w(
                        "Playback",
                        "Stream error (${error.errorCode}); re-resolving via ${forcedResolver?.label} " +
                            "at ${position} ms (attempt $retryCount)",
                        error,
                    )
                    runCatching {
                        ownedPlayer.stop()
                        ownedPlayer.seekTo(position)
                        ownedPlayer.prepare()
                        if (resume) ownedPlayer.play()
                    }
                    return
                }
                // Otherwise skip to the following song and keep playing; log the reason. Give up
                // after a few failures in a row so we never spin forever.
                if (skipRetries >= MAX_SKIP_RETRIES || !ownedPlayer.hasNextMediaItem()) {
                    AppLog.e("Playback", "Stream failed and there is no further song to try", error)
                    return
                }
                skipRetries++
                AppLog.w("Playback", "Stream failed; skipping to the next song", error)
                autoPlayOnReady = true
                runCatching {
                    ownedPlayer.seekToNextMediaItem()
                    ownedPlayer.play()
                }
            }
        })
        runCatching { AudioEffectsEngine.attach(ownedPlayer.audioSessionId, settings) }
            .onFailure { Log.w(TAG, "Could not attach audio effects", it) }

        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = MediaLibrarySession.Builder(this, TransportPlayer(ownedPlayer), libraryCallback)
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
                runCatching { AudioEffectsEngine.apply(newSettings) }
                    .onFailure { Log.w(TAG, "Could not apply audio effects", it) }
            }
        }

        // Mirror the favourite/liked tables so the notification heart tracks the same state the UI
        // reads. Room's observable queries invalidate on every write, whichever surface made it, so
        // a like from Now Playing updates the notification and vice versa.
        val container = (application as AuralisApp).container
        scope.launch {
            container.database.favoriteDao().observeIds().collectLatest { ids ->
                favoriteIds = ids.toSet()
                refreshMediaButtons()
            }
        }
        scope.launch {
            container.database.youtubeDao().observeLikedIds().collectLatest { ids ->
                youtubeLikedIds = ids.toSet()
                refreshMediaButtons()
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
                if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Every controller (including the media notification) may send the like and shuffle
            // commands, and the app controller the anchor command. Both buttons are seeded here so
            // they are present and correct on the very first notification.
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(likeCommand)
                .add(shuffleCommand)
                .add(SessionCommand(ACTION_ANCHOR_SHUFFLE, Bundle.EMPTY))
                .build()
            val shuffled = session.player.shuffleModeEnabled
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .setMediaButtonPreferences(listOf(likeButton(likeButtonState), shuffleButton(shuffled)))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_LIKE -> {
                    toggleLike()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SHUFFLE -> {
                    // The Player.Listener above anchors the order when the flag flips.
                    player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_ANCHOR_SHUFFLE -> {
                    applyAnchoredShuffle()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
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
        val remoteArtwork = track.artworkUrl?.let(::normalizeArtworkUrl)
        if (!remoteArtwork.isNullOrBlank()) {
            metadata.setArtworkUri(Uri.parse(remoteArtwork))
        } else {
            val artwork = track.artKey?.let { File(filesDir, "artwork/$it.jpg").takeIf(File::isFile) }
            if (artwork != null) metadata.setArtworkUri(Uri.fromFile(artwork))
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.uri))
            .setMimeType(track.mime.takeIf { it.startsWith("audio/") })
            .setMediaMetadata(metadata.build())
            .build()
    }

    /** Rewrites `auralis-yt://<videoId>` to the signed audio URL Media3 should actually open. */
    private fun resolveRemoteUri(dataSpec: DataSpec): DataSpec {
        val videoId = videoIdFromPlaybackUri(dataSpec.uri.toString())
            ?: return dataSpec
        val startedAt = System.currentTimeMillis()
        val quality = com.auralis.app.data.network.NetworkQuality.effective(this, settings)
        //  fallback: when a retry is armed, force the other resolver and ignore the failed URL.
        val force = forcedResolver
        val bypass = bypassResolveCache
        forcedResolver = null
        bypassResolveCache = false
        val resolved = (application as AuralisApp).container.youtube
            .resolveStreamBlocking(videoId, quality, force, bypass)
        val resolveMs = System.currentTimeMillis() - startedAt
        AppLog.i(
            "Timing",
            if (PlaybackTiming.isFreshTap()) "tap→stream URL in ${PlaybackTiming.sinceTap()} ms (race ${resolveMs} ms)"
            else "stream URL ready in ${resolveMs} ms",
        )
        return dataSpec.withUri(Uri.parse(resolved.url))
    }

    /** Which resolver produced the last successful URL; the retry then prefers the other one. */
    private fun resolverFromStatus(): ResolverKind = when {
        StreamResolverStatus.lastResolver.contains("InnerTube") -> ResolverKind.INNERTUBE
        StreamResolverStatus.lastResolver.contains("YouTube.js") -> ResolverKind.YOUTUBEJS
        else -> ResolverKind.NEWPIPE
    }

    /**
     * True for failures a different stream URL can plausibly fix: an expired/blocked HTTP status
     * (403/410 → [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS]), connection loss, or a source
     * format the resolver picked badly.
     */
    private fun isRetryableStreamError(error: androidx.media3.common.PlaybackException): Boolean =
        when (error.errorCode) {
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> true
            else -> false
        }

    /** The last item that belongs to the list the user started from, or -1 for an empty queue. */
    private fun lastContextIndex(player: Player): Int =
        (0 until player.mediaItemCount).lastOrNull { !player.getMediaItemAt(it).isAutoplayItem() } ?: -1

    /** The first context item; the wrap target for repeat-all. */
    private fun firstContextIndex(player: Player): Int =
        (0 until player.mediaItemCount).firstOrNull { !player.getMediaItemAt(it).isAutoplayItem() } ?: 0

    /** True when the recommendation engine has appended anything after the context block. */
    private fun hasAutoplayAfter(player: Player, contextEnd: Int): Boolean =
        (contextEnd + 1 until player.mediaItemCount).any { player.getMediaItemAt(it).isAutoplayItem() }

    // --- Anchored shuffle order -----------------------------------------------------------------

    /**
     * Rebuilds ExoPlayer's shuffle permutation so the song that is playing is FIRST and every other
     * song appears exactly once after it. Context songs come first; the radio/autoplay tail stays
     * after them so a shuffle can never skip the list the user started from.
     *
     * Called when shuffle is switched on (from anywhere) and whenever a new queue is handed over
     * while shuffle is on ([ACTION_ANCHOR_SHUFFLE], sent by [PlaybackController]). It is never
     * called from the timeline callback and is guarded while it runs, so it cannot loop.
     */
    private fun applyAnchoredShuffle() {
        val ownedPlayer = player ?: return
        if (!ownedPlayer.shuffleModeEnabled) return
        val count = ownedPlayer.mediaItemCount
        if (count < 2) return
        if (anchoringShuffle) return
        anchoringShuffle = true
        try {
            val current = ownedPlayer.currentMediaItemIndex.coerceIn(0, count - 1)
            val context = (0 until count).filterNot { ownedPlayer.getMediaItemAt(it).isAutoplayItem() }
            val autoplay = (0 until count).filter { ownedPlayer.getMediaItemAt(it).isAutoplayItem() }
            val seed = System.nanoTime()
            val order = (if (context.isEmpty()) {
                anchoredShuffle(count, current, seed).asList()
            } else {
                val anchorInContext = context.indexOf(current)
                if (anchorInContext >= 0) {
                    anchoredShuffle(context.size, anchorInContext, seed).map { context[it] } + autoplay
                } else {
                    // Playing from the radio tail: the saved context block comes first so it is
                    // never skipped, then the tail is anchored on the song that is playing.
                    context + anchoredShuffle(autoplay.size, autoplay.indexOf(current), seed).map { autoplay[it] }
                }
            }).toIntArray()
            ownedPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, seed))
        } catch (error: Throwable) {
            AppLog.w("Playback", "Could not build the anchored shuffle order", error)
        } finally {
            anchoringShuffle = false
        }
    }

    /**
     * Exposes next/previous to every MediaSession controller and applies the ONE skip policy on the
     * service side. This covers the notification, lock screen, Bluetooth, Android Auto and the
     * in-app buttons, which all route through the same session player.
     */
    private inner class TransportPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        // Every user skip (in-app, mini-player, notification, lock screen, Bluetooth/headset, Android
        // Auto) starts playback, even if the previous song was paused. A skip to a not-yet-resolved
        // remote stream is honoured by the STATE_READY latch in the service's player listener.
        private fun beginUserSkip() {
            autoPlayOnReady = true
            play()
        }

        /**
         * The next item a manual Next should play, or null when playback must stay on the current
         * song. Context songs are always walked in saved order; the autoplay tail is entered only
         * at the end of the context and only when autoplay (the YouTube integration) is on.
         */
        private fun manualNextTarget(): Int? {
            val count = mediaItemCount
            val current = currentMediaItemIndex
            if (count == 0 || current < 0) return null
            // Shuffle walks the anchored permutation installed by applyAnchoredShuffle(): the
            // current song is first and the context block follows, so the autoplay tail is still
            // entered only at the end. Repeat All wraps to the anchor; Repeat Off ends there.
            if (shuffleModeEnabled) {
                val effectiveRepeat =
                    if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeatMode
                val next = currentTimeline.getNextWindowIndex(current, effectiveRepeat, true)
                return next.takeIf { it >= 0 }
            }
            val lastContext = lastContextIndex(this)
            if (lastContext < 0) return if (current + 1 < count) current + 1 else null
            val next = current + 1
            val repeat = repeatMode
            if (current < lastContext) {
                // Walk the context in its saved order; shuffle is handled by the anchored order
                // installed through setShuffleOrder above.
                return next.takeIf { it <= lastContext }
            }
            val autoplayAvailable = hasAutoplayAfter(this, lastContext)
            if (current > lastContext) {
                // Already inside the autoplay tail: keep walking it, wrapping only with repeat on.
                return when {
                    next < count -> next
                    repeat == Player.REPEAT_MODE_OFF -> null
                    else -> firstContextIndex(this)
                }
            }
            // On the last context song. Repeat-all / repeat-one wrap to the first context song;
            // repeat-off may continue into autoplay, but with autoplay off the queue ends here.
            if (repeat != Player.REPEAT_MODE_OFF) return firstContextIndex(this)
            return if (autoplayAvailable && settings.youtubeIntegration) next.takeIf { it < count } else null
        }

        private fun skipNext() {
            val target = manualNextTarget() ?: return
            beginUserSkip()
            seekTo(target, 0L)
        }

        private fun skipPrevious() {
            if (currentPosition > PREVIOUS_RESTART_MS) {
                beginUserSkip()
                seekTo(0L)
                return
            }
            val previous = previousMediaItemIndex
            val lastContext = lastContextIndex(this)
            // With shuffle/repeat the previous item is the real played history. Only a repeat-all
            // WRAP into the autoplay tail is clamped back to the last context song (see
            // previousSkipTarget); stepping back inside the tail goes to the song just heard.
            val target = previousSkipTarget(previous, currentMediaItemIndex, lastContext)
            beginUserSkip()
            seekTo(target ?: currentMediaItemIndex.coerceAtLeast(0), 0L)
        }

        override fun seekToNext() = skipNext()

        override fun seekToNextMediaItem() = skipNext()

        override fun seekToPrevious() = skipPrevious()

        override fun seekToPreviousMediaItem() = skipPrevious()

        // A user pause while a skip is still resolving wins: drop the pending auto-play.
        override fun pause() {
            autoPlayOnReady = false
            super.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (!playWhenReady) autoPlayOnReady = false
            super.setPlayWhenReady(playWhenReady)
        }
    }

    // --- Notification Like button ---------------------------------------------------------------

    /** Builds the heart action; the icon flips between outlined and filled with the like state. */
    private fun likeButton(liked: Boolean): CommandButton = CommandButton.Builder(
        if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
    )
        .setSessionCommand(likeCommand)
        .setDisplayName(
            getString(if (liked) R.string.notification_remove_favorite else R.string.notification_add_favorite),
        )
        .setEnabled(true)
        .build()

    /** Builds the shuffle action; Media3 1.7.1 ships both glyphs, so no drawable is needed. */
    private fun shuffleButton(enabled: Boolean): CommandButton = CommandButton.Builder(
        if (enabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
    )
        .setSessionCommand(shuffleCommand)
        .setDisplayName(
            getString(if (enabled) R.string.notification_shuffle_on else R.string.notification_shuffle_off),
        )
        .setEnabled(true)
        .build()

    /** Whether the song on screen right now is a favourite. */
    private fun isCurrentTrackLiked(): Boolean =
        resolveLiked(player?.currentMediaItem?.mediaId, favoriteIds, youtubeLikedIds)

    /**
     * Pushes the heart and the shuffle glyph to the notification when either state changes, so a
     * toggle from the app, the notification, Bluetooth or the lock screen always flips the icon.
     */
    private fun refreshMediaButtons(force: Boolean = false) {
        val liked = isCurrentTrackLiked()
        val shuffled = player?.shuffleModeEnabled == true
        if (!force && liked == likeButtonState && shuffled == shuffleButtonState) return
        likeButtonState = liked
        shuffleButtonState = shuffled
        session?.setMediaButtonPreferences(listOf(likeButton(liked), shuffleButton(shuffled)))
    }

    /** Toggles the current song's favourite state, reusing the same tables the UI reads. */
    private fun toggleLike() {
        val item = player?.currentMediaItem ?: return
        val mediaId = item.mediaId
        if (mediaId.isBlank()) return
        // One write at a time: a burst of taps must not interleave a read-modify-write and flip the
        // heart the wrong way. Extra taps during the (few-ms) write are dropped, not queued.
        if (likeToggleInFlight) return
        likeToggleInFlight = true
        scope.launch {
            try {
                runCatching {
                    if (mediaId.startsWith(YOUTUBE_TRACK_PREFIX)) {
                        toggleRemoteLike(mediaId, item)
                    } else {
                        (application as AuralisApp).container.database.favoriteDao()
                            .toggle(mediaId, System.currentTimeMillis())
                    }
                }.onFailure { AppLog.w("Playback", "Could not update the favourite from the notification", it) }
            } finally {
                likeToggleInFlight = false
            }
        }
    }

    /**
     * Remote (YouTube) likes: mirror into Room first, tell the live-sync poller to leave the id
     * alone, then update the account best-effort and roll the mirror back if the account refuses.
     * Same contract as AppViewModel.toggleYouTubeLike, so the two surfaces cannot disagree.
     */
    private suspend fun toggleRemoteLike(mediaId: String, item: MediaItem) {
        val container = (application as AuralisApp).container
        val videoId = mediaId.removePrefix(YOUTUBE_TRACK_PREFIX)
        if (videoId.isBlank()) return
        val dao = container.database.youtubeDao()
        val wasLiked = dao.isLiked(videoId)
        val entity = YouTubeLikeEntity(
            videoId = videoId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            durationMs = player?.duration?.takeIf { it > 0L } ?: 0L,
            thumbnailUrl = item.mediaMetadata.artworkUri?.toString(),
            likedAt = System.currentTimeMillis(),
        )
        if (wasLiked) dao.deleteLike(videoId) else dao.upsertLike(entity)
        container.liveSync.notePendingLike(videoId, liked = !wasLiked)
        if (!settings.youtubeSyncLikes || container.youtubeAuth.snapshot()?.signedIn != true) return
        val synced = runCatching { container.youtube.setLiked(videoId, !wasLiked) }.getOrDefault(false)
        if (!synced) {
            runCatching {
                if (wasLiked) dao.upsertLike(entity) else dao.deleteLike(videoId)
            }
        }
    }

    /**
     * Media3's default notification appends extra custom buttons to the overflow (end) slot. This
     * moves the Like heart to the front, so the expanded controls read
     * "Like | Previous | Play/Pause | Next | Shuffle" (the shuffle button stays in the end slot,
     * where Media3 already placed it). The compact view keeps the three standard transport
     * buttons, because Android only allows three there.
     */
    private class AuralisMediaNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(context) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> {
            val base = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
            val likeAt = base.indexOfFirst { it.sessionCommand?.customAction == ACTION_LIKE }
            if (likeAt <= 0) return base
            val reordered = base.toMutableList()
            reordered.add(0, reordered.removeAt(likeAt))
            return ImmutableList.copyOf(reordered)
        }
    }

    companion object {
        const val ACTION_START_SLEEP_TIMER = "com.auralis.app.action.START_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.auralis.app.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MS = "sleep_timer_duration_ms"
        /**
         * Sent by the app controller after it hands over a new queue (or re-anchors a tapped Up Next
         * song) while shuffle is on: the service rebuilds the anchored permutation around the
         * current song. No arguments.
         */
        const val ACTION_ANCHOR_SHUFFLE = "com.auralis.app.action.ANCHOR_SHUFFLE"
        private const val MAX_SLEEP_TIMER_MS = 12 * 60 * 60 * 1_000L
        private const val RECONNECT_DELAY_MS = 700L
        /** Start playback after 500 ms of buffered audio; 1 s more after a rebuffer. */
        private const val PLAYBACK_BUFFER_MS = 500
        private const val PLAYBACK_REBUFFER_MS = 1_000
        private const val PREVIOUS_RESTART_MS = 3_000L
        /** How many consecutive failed streams to auto-skip past before giving up. */
        private const val MAX_SKIP_RETRIES = 3
        /** re-resolve attempts with the other resolver before skipping a failed song. */
        private const val MAX_STREAM_RETRIES = 2
        private const val TAG = "AuralisService"
        private const val ROOT = "auralis_root"
        private const val LIBRARY = "auralis_library"
        private const val ALBUMS = "auralis_albums"
        private const val ARTISTS = "auralis_artists"
        private const val PLAYLISTS = "auralis_playlists"
    }
}
