package com.auralis.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.domain.model.Track
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.AudioInfoSheet
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.components.QualityBadgeContent
import com.auralis.app.ui.components.qualityBadgeContent
import com.auralis.app.ui.components.rememberBluetoothAudioOutputName
import com.auralis.app.ui.theme.LyricsTypography
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The blended Now Playing screen: edge-to-edge artwork that melts into a palette-driven mesh, one
 * line of live lyrics, a thick timeline, big transport, media volume and a compact icon row.
 *
 * All playback, queue, lyrics, favourite and menu actions are the existing callbacks; this file only
 * restyles and repositions them. Landscape keeps the previous layouts (see NowPlayingScreen).
 */
@Composable
fun BlendNowPlayingScreen(
    track: Track,
    queue: List<Track>,
    upNext: List<Track>,
    availableTracks: List<Track>,
    favorites: Set<String>,
    playback: PlaybackController.State,
    settings: AppSettings = AppSettings(),
    lyrics: Lyrics? = null,
    lyricsLoading: Boolean = false,
    lyricsAutoScroll: Boolean = true,
    lyricSizeSp: Int = 22,
    lyricsOffsetMs: Int = 0,
    onLoadLyrics: (Track) -> Unit = {},
    onImportLyrics: (Track, Uri) -> Unit = { _, _ -> },
    onSaveLyrics: (Track, String) -> Unit = { _, _ -> },
    onTryAnotherLyricsSource: (Track) -> Unit = {},
    onRetryLyrics: (Track) -> Unit = {},
    onAdjustLyricsOffset: (Int) -> Unit = {},
    onResetLyricsOffset: () -> Unit = {},
    onSeekBy: (Long) -> Unit = {},
    doubleTapSeekSeconds: Int = 10,
    gesturesEnabled: Boolean = true,
    onOpenEqualizer: () -> Unit = {},
    onOpenWrapped: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onEditMetadata: () -> Unit = {},
    onOpenAudioAnalysis: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onRemoveFromQueue: (Track) -> Unit,
    onMoveQueueItem: (Track, Int) -> Unit,
    onPlayQueueItem: (Track) -> Unit,
    onClearQueue: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var addSongsOpen by rememberSaveable { mutableStateOf(false) }
    var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }
    var customSleepMinutes by rememberSaveable { mutableStateOf("30") }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var audioInfoOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var draggingQueueId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id) { onLoadLyrics(track) }
    ForceWhiteSystemIcons()
    val lyricImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportLyrics(track, uri)
    }

    val context = LocalContext.current
    val visuals = rememberArtVisuals(track, playback.artworkUri)
    val palette = visuals.palette
    val bluetoothOutputName = rememberBluetoothAudioOutputName()
    val deviceName = remember(context) { deviceDisplayName(context) }
    val duration = playback.durationMs.takeIf { it > 0 } ?: track.durationMs
    val quality = remember(track) { track.qualityBadgeContent() }
    val isFavorite = track.id in favorites

    BackHandler(enabled = queueOpen) { queueOpen = false }
    BackHandler(enabled = lyricsOpen && !queueOpen) { lyricsOpen = false }
    BackHandler(enabled = !queueOpen && !lyricsOpen) { onBack() }

    Box(modifier.fillMaxSize()) {
        when {
            queueOpen -> QueueScreen(
                queue = queue,
                currentId = playback.trackId,
                draggingId = draggingQueueId,
                onDraggingId = { draggingQueueId = it },
                onPlayQueueItem = onPlayQueueItem,
                onRemoveFromQueue = onRemoveFromQueue,
                onMoveQueueItem = onMoveQueueItem,
                onAddSongs = { addSongsOpen = true },
                onClear = { onClearQueue(); queueOpen = false; onBack() },
                onClose = { queueOpen = false },
            )
            lyricsOpen -> LiquidLyricsScreen(
                track = track,
                lyrics = lyrics,
                loading = lyricsLoading,
                positionMs = playback.positionMs,
                durationMs = duration,
                playing = playback.playing,
                lyricSizeSp = lyricSizeSp,
                autoScroll = lyricsAutoScroll,
                lyricsOffsetMs = lyricsOffsetMs,
                isFavorite = isFavorite,
                onToggleFavorite = { onFavorite(track) },
                onSeek = { position -> onSeek(position) },
                onClose = { lyricsOpen = false },
                onImport = { lyricImporter.launch(arrayOf("text/plain", "application/octet-stream", "application/x-subrip", "*/*")) },
                onSaveText = { text -> onSaveLyrics(track, text) },
                onTryAnotherSource = { onTryAnotherLyricsSource(track) },
                onRetry = { onRetryLyrics(track) },
                onAdjustOffset = onAdjustLyricsOffset,
                onResetOffset = onResetLyricsOffset,
            )
            else -> {
                if (settings.playerBackground != PlayerBackground.NONE) {
                    PlayerBackgroundImage(settings.playerBackground)
                } else {
                    NowPlayingBlendBackground(palette = palette, art = visuals.bitmap)
                }

                val compact = LocalConfiguration.current.screenHeightDp < 640
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MeltArtwork(
                            track = track,
                            artUri = playback.artworkUri,
                            placeholder = palette.darkMuted,
                            gesturesEnabled = gesturesEnabled,
                            doubleTapSeekSeconds = doubleTapSeekSeconds,
                            onSeekBy = onSeekBy,
                            onOpenLyrics = { lyricsOpen = true },
                            modifier = Modifier
                                .fillMaxWidth(if (compact) 0.85f else 1f)
                                .aspectRatio(1f),
                        )
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .navigationBarsPadding()
                            .padding(horizontal = 32.dp),
                    ) {
                        Gap(0.24f, 14.dp, compact)
                        TitleBlock(
                            title = track.title.ifBlank { track.displayName }.ifBlank { "Unknown title" },
                            subtitle = artistAlbum(track),
                            isFavorite = isFavorite,
                            moreOpen = moreOpen,
                            onMoreOpenChange = { moreOpen = it },
                            onFavorite = { onFavorite(track) },
                            playback = playback,
                            track = track,
                            sleepTimerOpen = { sleepTimerOpen = true },
                            onShuffle = onShuffle,
                            onRepeat = onRepeat,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenWrapped = onOpenWrapped,
                            onOpenBackup = onOpenBackup,
                            onEditMetadata = onEditMetadata,
                            onAudioInfo = { audioInfoOpen = true },
                        )
                        Gap(0.18f, 14.dp, compact)
                        LyricsPreviewRow(
                            lyrics = lyrics,
                            lyricsLoading = lyricsLoading,
                            positionMs = playback.positionMs,
                            lyricsOffsetMs = lyricsOffsetMs,
                            onClick = { lyricsOpen = true },
                        )
                        Gap(0.05f, 8.dp, compact)
                        BlendTimeline(
                            positionMs = playback.positionMs,
                            durationMs = duration,
                            quality = quality,
                            onQualityClick = { audioInfoOpen = true },
                            onSeek = onSeek,
                        )
                        Gap(0.14f, 12.dp, compact)
                        TransportRow(
                            playing = playback.playing,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                        )
                        playback.error?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        Gap(0.09f, 10.dp, compact)
                        VolumeRow()
                        Gap(0.10f, 10.dp, compact)
                        BottomIconRow(
                            onLyrics = { lyricsOpen = true },
                            onOutput = { audioInfoOpen = true },
                            onQueue = { queueOpen = true },
                        )
                        Gap(0.05f, 8.dp, compact)
                        Text(
                            text = bluetoothOutputName ?: deviceName,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    }
                }

                // Drag handle: the only persistent close affordance, plus swipe-down to dismiss.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                    contentAlignment = Alignment.Center,
                ) {
                    DragHandle(onClose = onBack)
                }
            }
        }
    }

    if (audioInfoOpen) {
        AudioInfoSheet(
            track = track,
            settings = settings,
            onDismiss = { audioInfoOpen = false },
            onOpenAnalysis = {
                audioInfoOpen = false
                onOpenAudioAnalysis(track.id)
            },
        )
    }

    if (addSongsOpen) AddSongsDialog(
        availableTracks = availableTracks,
        queue = queue,
        onAdd = onAddToQueue,
        onDismiss = { addSongsOpen = false },
    )

    if (sleepTimerOpen) SleepTimerDialog(
        remainingMs = playback.sleepTimerRemainingMs,
        customMinutes = customSleepMinutes,
        onCustomMinutesChange = { customSleepMinutes = it },
        onStart = { minutes -> onStartSleepTimer(minutes) },
        onCancel = onCancelSleepTimer,
        onDismiss = { sleepTimerOpen = false },
    )
}

/* --------------------------------------------------------------------------------------------- */
/* Layout blocks                                                                                  */
/* --------------------------------------------------------------------------------------------- */

/** Weighted spacer in the flexible layout, fixed spacer when the screen is short and scrolls. */
@Composable
private fun ColumnScope.Gap(weight: Float, compactHeight: Dp, compact: Boolean) {
    if (compact) Spacer(Modifier.height(compactHeight)) else Spacer(Modifier.weight(weight))
}

/**
 * Full-bleed square artwork whose bottom third is dissolved with a DstIn mask, so the palette mesh
 * underneath shows through the art itself rather than a rectangle painted on top of it.
 */
@Composable
private fun MeltArtwork(
    track: Track,
    artUri: Any?,
    placeholder: Color,
    gesturesEnabled: Boolean,
    doubleTapSeekSeconds: Int,
    onSeekBy: (Long) -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var artworkDrag by remember(track.id) { mutableFloatStateOf(0f) }
    val openThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        modifier
            .background(placeholder)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val mask = Brush.verticalGradient(
                    0f to Color.Black,
                    0.55f to Color.Black,
                    0.80f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = mask, blendMode = BlendMode.DstIn)
                }
            },
    ) {
        ArtworkImage(
            track = track,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (gesturesEnabled) {
                        Modifier.pointerInput(track.id, doubleTapSeekSeconds) {
                            detectTapGestures(onDoubleTap = { offset ->
                                if (offset.x < size.width / 2f) onSeekBy(-doubleTapSeekSeconds * 1000L)
                                else onSeekBy(doubleTapSeekSeconds * 1000L)
                            })
                        }
                    } else {
                        Modifier
                    },
                )
                .pointerInput(track.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { artworkDrag = 0f },
                        onDragEnd = {
                            if (artworkDrag < -openThreshold) onOpenLyrics()
                            artworkDrag = 0f
                        },
                        onDragCancel = { artworkDrag = 0f },
                    ) { change, amount ->
                        change.consume()
                        artworkDrag += amount
                    }
                },
            shape = RectangleShape,
            artUri = artUri,
        )
    }
}

@Composable
private fun TitleBlock(
    title: String,
    subtitle: String,
    isFavorite: Boolean,
    moreOpen: Boolean,
    onMoreOpenChange: (Boolean) -> Unit,
    onFavorite: () -> Unit,
    playback: PlaybackController.State,
    track: Track,
    sleepTimerOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
    onAudioInfo: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().fadeEnd(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().fadeEnd(24.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        GlassCircleButton(
            icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            description = if (isFavorite) "Remove from favourites" else "Add to favourites",
            onClick = onFavorite,
        )
        Spacer(Modifier.width(10.dp))
        Box {
            GlassCircleButton(
                icon = Icons.Rounded.MoreHoriz,
                description = "More options",
                onClick = { onMoreOpenChange(true) },
            )
            PlayerMoreMenu(
                expanded = moreOpen,
                onDismiss = { onMoreOpenChange(false) },
                playback = playback,
                track = track,
                sleepTimerOpen = sleepTimerOpen,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onOpenEqualizer = onOpenEqualizer,
                onOpenWrapped = onOpenWrapped,
                onOpenBackup = onOpenBackup,
                onEditMetadata = onEditMetadata,
                onAudioInfo = onAudioInfo,
            )
        }
    }
}

/** Everything that does not fit the screenshot lives here, so no existing action is lost. */
@Composable
private fun PlayerMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    playback: PlaybackController.State,
    track: Track,
    sleepTimerOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
    onAudioInfo: () -> Unit,
) {
    val downloads = LocalDownloadActions.current
    val downloaded = (track.remoteId ?: track.id.removePrefix("yt:")) in downloads.downloadedIds
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Shuffle · ${if (playback.shuffle) "On" else "Off"}") },
            leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
            onClick = { onDismiss(); onShuffle() },
        )
        DropdownMenuItem(
            text = {
                val label = when (playback.repeat) {
                    Player.REPEAT_MODE_ONE -> "One"
                    Player.REPEAT_MODE_ALL -> "All"
                    else -> "Off"
                }
                Text("Repeat · $label")
            },
            leadingIcon = { Icon(Icons.Rounded.Repeat, null) },
            onClick = { onDismiss(); onRepeat() },
        )
        DropdownMenuItem(
            text = {
                val remaining = playback.sleepTimerRemainingMs
                Text(if (remaining > 0) "Sleep timer · ${formatTime(remaining)}" else "Sleep timer")
            },
            leadingIcon = { Icon(Icons.Rounded.Timer, null) },
            onClick = { onDismiss(); sleepTimerOpen() },
        )
        if (track.isRemote) {
            DropdownMenuItem(
                text = { Text(if (downloaded) "Delete download" else "Download") },
                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                onClick = {
                    onDismiss()
                    if (downloaded) downloads.onDeleteDownload(track) else downloads.onDownload(track)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Audio info") },
            leadingIcon = { Icon(Icons.Rounded.Info, null) },
            onClick = { onDismiss(); onAudioInfo() },
        )
        DropdownMenuItem(
            text = { Text("Equalizer") },
            leadingIcon = { Icon(Icons.Rounded.Equalizer, null) },
            onClick = { onDismiss(); onOpenEqualizer() },
        )
        DropdownMenuItem(
            text = { Text("Wrapped") },
            leadingIcon = { Icon(Icons.Rounded.Insights, null) },
            onClick = { onDismiss(); onOpenWrapped() },
        )
        DropdownMenuItem(
            text = { Text("Backup") },
            leadingIcon = { Icon(Icons.Rounded.CloudUpload, null) },
            onClick = { onDismiss(); onOpenBackup() },
        )
        DropdownMenuItem(
            text = { Text("Edit tags") },
            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            onClick = { onDismiss(); onEditMetadata() },
        )
    }
}

@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * One line of the current synced lyric, cross-faded when the line changes. The row keeps a reserved
 * height when there is nothing to show, so the timeline below never moves.
 */
@Composable
private fun LyricsPreviewRow(
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    positionMs: Long,
    lyricsOffsetMs: Int,
    onClick: () -> Unit,
) {
    val positionState = rememberUpdatedState(positionMs)
    val line by remember(lyrics, lyricsOffsetMs, lyricsLoading) {
        derivedStateOf { previewLine(lyrics, positionState.value, lyricsOffsetMs) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "lyricsPreview",
            ) { text ->
                Text(
                    text = text.ifEmpty { " " },
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = LyricsTypography.familyFor(text.ifEmpty { " " }),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth().fadeEnd(18.dp),
                )
            }
        }
        if (line.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Open lyrics",
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Thick rounded timeline plus elapsed / quality / remaining labels. */
@Composable
private fun BlendTimeline(
    positionMs: Long,
    durationMs: Long,
    quality: QualityBadgeContent?,
    onQualityClick: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val total = durationMs.coerceAtLeast(0L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val played = dragFraction ?: if (total > 0L) {
        (positionMs.toFloat() / total).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownPosition = dragFraction?.let { (it * total).toLong() } ?: positionMs.coerceAtLeast(0L)
    SlimSlider(
        value = played,
        onChange = { dragFraction = it },
        onCommit = { fraction ->
            dragFraction = null
            if (total > 0L) onSeek((fraction * total).toLong())
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(shownPosition),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Headphones,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = qualityLabel(quality),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button, onClick = onQualityClick),
            )
        }
        Text(
            text = "-" + formatTime((total - shownPosition).coerceAtLeast(0L)),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TransportRow(
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TransportButton(Icons.Rounded.SkipPrevious, "Previous", 56.dp, 34.dp, onPrevious)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(100), label = "playScale")
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onPlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = playing, animationSpec = tween(120), label = "playPauseIcon") { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TransportButton(Icons.Rounded.SkipNext, "Next", 56.dp, 34.dp, onNext)
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(100), label = "transportScale")
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun VolumeRow() {
    val volume = rememberMediaVolume()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeMute,
            contentDescription = "Volume",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        SlimSlider(
            value = volume.value,
            onChange = volume.set,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "Volume up",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun BottomIconRow(
    onLyrics: () -> Unit,
    onOutput: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomIcon(Icons.Rounded.Lyrics, "Lyrics", onLyrics)
        BottomIcon(Icons.Rounded.SurroundSound, "Audio output", onOutput)
        BottomIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue)
    }
}

@Composable
private fun BottomIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun DragHandle(onClose: () -> Unit) {
    var dragY by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onDragEnd = {
                        if (dragY > threshold) onClose()
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                ) { change, amount ->
                    change.consume()
                    dragY += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.10f)
                .height(4.5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(Color.White.copy(alpha = 0.85f)),
        )
    }
}

/**
 * Minimal rounded slider used for both the timeline and the volume row. One gesture handles tap and
 * drag; the value is committed on release so seeking happens once.
 */
@Composable
private fun SlimSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onCommit: ((Float) -> Unit)? = null,
) {
    val fraction = value.coerceIn(0f, 1f)
    val changeState = rememberUpdatedState(onChange)
    val commitState = rememberUpdatedState(onCommit)
    Box(
        modifier
            .height(26.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = (down.position.x / size.width).coerceIn(0f, 1f)
                    changeState.value(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val next = (change.position.x / size.width).coerceIn(0f, 1f)
                        if (next != last) {
                            last = next
                            changeState.value(next)
                        }
                        change.consume()
                        if (change.changedToUpIgnoreConsumed()) break
                    }
                    commitState.value?.invoke(last)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.48f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White),
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Queue                                                                                          */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun QueueScreen(
    queue: List<Track>,
    currentId: String?,
    draggingId: String?,
    onDraggingId: (String?) -> Unit,
    onPlayQueueItem: (Track) -> Unit,
    onRemoveFromQueue: (Track) -> Unit,
    onMoveQueueItem: (Track, Int) -> Unit,
    onAddSongs: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back to Now Playing",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(40.dp).clickable(role = Role.Button, onClick = onClose).padding(8.dp),
            )
            Text(
                "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onAddSongs) {
                Icon(Icons.Rounded.Add, null)
                Text("Add songs")
            }
            TextButton(onClick = onClear, enabled = queue.isNotEmpty()) {
                Icon(Icons.Rounded.ClearAll, null)
                Text("Clear")
            }
        }
        if (queue.isEmpty()) {
            Text(
                "The queue is empty.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                val isCurrent = item.id == currentId
                val latestIndex by rememberUpdatedState(index)
                val latestLastIndex by rememberUpdatedState(queue.lastIndex)
                val latestMove by rememberUpdatedState(onMoveQueueItem)
                val density = LocalDensity.current
                var dragRemainder by remember(item.id) { mutableFloatStateOf(0f) }
                var dragTargetIndex by remember(item.id) { mutableStateOf(index) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlayQueueItem(item) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(item, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            item.title.ifBlank { item.displayName },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (isCurrent) "Playing · ${item.artist}" else item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .pointerInput(item.id, isCurrent) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        if (!isCurrent) {
                                            onDraggingId(item.id)
                                            dragRemainder = 0f
                                            dragTargetIndex = latestIndex
                                        }
                                    },
                                    onDragEnd = { onDraggingId(null); dragRemainder = 0f },
                                    onDragCancel = { onDraggingId(null); dragRemainder = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    if (isCurrent) return@detectDragGesturesAfterLongPress
                                    dragRemainder += amount.y
                                    val step = with(density) { 64.dp.toPx() }
                                    while (dragRemainder >= step && dragTargetIndex < latestLastIndex) {
                                        dragTargetIndex += 1
                                        latestMove(item, dragTargetIndex)
                                        dragRemainder -= step
                                    }
                                    while (dragRemainder <= -step && dragTargetIndex > 0) {
                                        dragTargetIndex -= 1
                                        latestMove(item, dragTargetIndex)
                                        dragRemainder += step
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            "Drag to reorder",
                            tint = if (draggingId == item.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    QueueIconAction(
                        Icons.Rounded.ArrowUpward,
                        "Move up",
                        enabled = index > 0 && !isCurrent,
                    ) { onMoveQueueItem(item, index - 1) }
                    QueueIconAction(
                        Icons.Rounded.ArrowDownward,
                        "Move down",
                        enabled = index < queue.lastIndex && !isCurrent,
                    ) { onMoveQueueItem(item, index + 1) }
                    QueueIconAction(Icons.Rounded.RemoveCircleOutline, "Remove from queue") {
                        onRemoveFromQueue(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueIconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Dialogs                                                                                        */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun AddSongsDialog(
    availableTracks: List<Track>,
    queue: List<Track>,
    onAdd: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add songs to queue") },
        text = {
            val queuedIds = remember(queue) { queue.mapTo(mutableSetOf()) { it.id } }
            val candidates = remember(availableTracks, queuedIds) {
                availableTracks.filterNot { it.id in queuedIds }
            }
            if (candidates.isEmpty()) {
                Text("All library songs are already queued.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(candidates, key = { _, item -> item.id }) { _, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(item) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtworkImage(item, Modifier.size(42.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.Rounded.Add, "Add to queue", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SleepTimerDialog(
    remainingMs: Long,
    customMinutes: String,
    onCustomMinutesChange: (String) -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text(
                    if (remainingMs > 0) "Playback will pause in ${formatTime(remainingMs)}."
                    else "Pause playback after:",
                )
                if (remainingMs == 0L) {
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        TextButton(
                            onClick = { onStart(minutes); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("$minutes minutes") }
                    }
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { value ->
                            if (value.length <= 3 && (value.isEmpty() || value.all(Char::isDigit))) {
                                onCustomMinutesChange(value)
                            }
                        },
                        label = { Text("Custom minutes (1–720)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (remainingMs > 0) {
                TextButton(onClick = { onCancel(); onDismiss() }) { Text("Cancel timer") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
                TextButton(
                    onClick = { onStart(customMinutes.toInt()); onDismiss() },
                    enabled = customMinutes.toIntOrNull() in 1..720,
                ) { Text("Start") }
            }
        },
    )
}

/* --------------------------------------------------------------------------------------------- */
/* Media volume                                                                                   */
/* --------------------------------------------------------------------------------------------- */

private class MediaVolume(val value: Float, val set: (Float) -> Unit)

/** Live media-stream volume, kept in sync with the hardware keys through a settings observer. */
@Composable
private fun rememberMediaVolume(): MediaVolume {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var volume by remember(audioManager) { mutableFloatStateOf(readMediaVolume(audioManager)) }
    DisposableEffect(audioManager, context) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volume = readMediaVolume(audioManager)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer,
            )
        }.isSuccess
        onDispose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return remember(volume, audioManager) {
        MediaVolume(volume) { requested ->
            val manager = audioManager ?: return@MediaVolume
            val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            if (max <= 0) return@MediaVolume
            val index = (requested.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
            runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
            volume = index.toFloat() / max
        }
    }
}

private fun readMediaVolume(manager: AudioManager?): Float {
    val safe = manager ?: return 0f
    return runCatching {
        val max = safe.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) 0f else safe.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }.getOrDefault(0f)
}

/* --------------------------------------------------------------------------------------------- */
/* Helpers                                                                                        */
/* --------------------------------------------------------------------------------------------- */

/** Right-edge dissolve for a single-line text, mirrored for RTL. */
private fun Modifier.fadeEnd(fade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val fadePx = fade.toPx().coerceIn(0f, size.width)
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        val brush = Brush.horizontalGradient(
            0f to Color.Black,
            1f to Color.Transparent,
            startX = if (rtl) fadePx else size.width - fadePx,
            endX = if (rtl) 0f else size.width,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }

private fun artistAlbum(track: Track): String {
    val artist = track.artist.ifBlank { "Unknown artist" }
    val album = track.album.takeIf { it.isNotBlank() && !it.equals("Unknown album", ignoreCase = true) }
    return if (album != null) "$artist, $album" else artist
}

private fun qualityLabel(quality: QualityBadgeContent?): String {
    if (quality == null) return "High quality"
    val tag = quality.tag?.takeIf { it.isNotBlank() }
    val details = quality.details.takeIf { it.isNotBlank() }
    return when {
        tag != null && details != null -> "$tag · $details"
        tag != null -> tag
        details != null -> details
        else -> "High quality"
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun deviceDisplayName(context: Context): String {
    val system = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()
    return system?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "This device"
}

/**
 * Best-effort single line for the lyrics preview. Never throws: missing lyrics, an empty list or an
 * out-of-range index all return "".
 */
private fun previewLine(lyrics: Lyrics?, positionMs: Long, lyricsOffsetMs: Int): String {
    return runCatching {
        val synced = lyrics?.synced.orEmpty()
        if (synced.isNotEmpty()) {
            val index = LrcParser.activeLine(synced, positionMs + lyricsOffsetMs)
            val text = synced.getOrNull(index)?.text
            sanitizeLyric(text)
        } else {
            val plain = lyrics?.plain
            val first = plain?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
            sanitizeLyric(first)
        }
    }.getOrDefault("")
}

private fun sanitizeLyric(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace(Regex("^\\[[^\\]]*]"), "")
        .replace(Regex("^v\\d+:\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
}

/** White status/navigation icons over the artwork, restored when the screen leaves. */
@Composable
private fun ForceWhiteSystemIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context.findActivity())?.window
    SideEffect {
        window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        onDispose {
            if (controller != null) {
                previousStatus?.let { controller.isAppearanceLightStatusBars = it }
                previousNavigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
