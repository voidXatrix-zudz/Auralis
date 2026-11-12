package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassNowPlayingScreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.auralis.app.ui.theme.ArtworkPalette
import com.auralis.app.ui.theme.Auralis
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.Lyrics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.AudioInfoSheet
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.components.QualityBadgeContent
import com.auralis.app.ui.components.qualityBadgeContent
import com.auralis.app.ui.components.rememberBluetoothAudioOutputName

@Composable
fun NowPlayingScreen(
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
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassNowPlayingScreen(
            track = track, queue = queue, upNext = upNext, availableTracks = availableTracks, favorites = favorites, playback = playback,
            settings = settings,
            lyrics = lyrics, lyricsLoading = lyricsLoading, lyricsAutoScroll = lyricsAutoScroll, lyricSizeSp = lyricSizeSp,
            lyricsOffsetMs = lyricsOffsetMs,
            onLoadLyrics = onLoadLyrics, onImportLyrics = onImportLyrics, onSaveLyrics = onSaveLyrics, onSeekBy = onSeekBy,
            onTryAnotherLyricsSource = onTryAnotherLyricsSource,
            onAdjustLyricsOffset = onAdjustLyricsOffset, onResetLyricsOffset = onResetLyricsOffset,
            doubleTapSeekSeconds = doubleTapSeekSeconds, gesturesEnabled = gesturesEnabled,
            onOpenEqualizer = onOpenEqualizer, onOpenWrapped = onOpenWrapped,
            onOpenBackup = onOpenBackup, onEditMetadata = onEditMetadata, onOpenAudioAnalysis = onOpenAudioAnalysis,
            onBack = onBack, onPlayPause = onPlayPause, onPrevious = onPrevious,
            onNext = onNext, onSeek = onSeek, onShuffle = onShuffle, onRepeat = onRepeat, onFavorite = onFavorite, onAddToQueue = onAddToQueue,
            onRemoveFromQueue = onRemoveFromQueue, onMoveQueueItem = onMoveQueueItem, onPlayQueueItem = onPlayQueueItem, onClearQueue = onClearQueue,
            onStartSleepTimer = onStartSleepTimer, onCancelSleepTimer = onCancelSleepTimer, modifier = modifier,
        )
        return
    }
    var queueOpen by remember { mutableStateOf(false) }
    var addSongsOpen by remember { mutableStateOf(false) }
    var sleepTimerOpen by remember { mutableStateOf(false) }
    var customSleepMinutes by rememberSaveable { mutableStateOf("30") }
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    var draggingQueueId by remember { mutableStateOf<String?>(null) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var audioInfoOpen by remember(track.id) { mutableStateOf(false) }
    val lyricImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportLyrics(track, uri)
    }
    LaunchedEffect(track.id) { onLoadLyrics(track) }
    val colors = MaterialTheme.colorScheme
    val bluetoothOutputName = rememberBluetoothAudioOutputName()
    val duration = playback.durationMs.takeIf { it > 0 } ?: track.durationMs
    val progress = dragging ?: playback.positionMs.toFloat()
    BackHandler(enabled = queueOpen) { queueOpen = false }

    Surface(modifier = modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
      if (queueOpen) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { queueOpen = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Now Playing") }
                Text("Playback queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { addSongsOpen = true }) { Icon(Icons.Rounded.Add, null); Text("Add songs") }
                TextButton(onClick = { onClearQueue(); queueOpen = false; onBack() }, enabled = queue.isNotEmpty()) { Icon(Icons.Rounded.ClearAll, null); Text("Clear") }
            }
            if (queue.isEmpty()) Text("The queue is empty.", color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                    val isCurrent = item.id == playback.trackId
                    val latestIndex by rememberUpdatedState(index)
                    val latestLastIndex by rememberUpdatedState(queue.lastIndex)
                    val latestMove by rememberUpdatedState(onMoveQueueItem)
                    val density = LocalDensity.current
                    var dragRemainder by remember(item.id) { mutableFloatStateOf(0f) }
                    var dragTargetIndex by remember(item.id) { mutableIntStateOf(index) }
                    Row(Modifier.fillMaxWidth().clickable { onPlayQueueItem(item) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkImage(item, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) colors.primary else colors.onSurface)
                            Text(if (isCurrent) "Playing · ${item.artist}" else item.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .pointerInput(item.id, isCurrent) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { if (!isCurrent) { draggingQueueId = item.id; dragRemainder = 0f; dragTargetIndex = latestIndex } },
                                        onDragEnd = { draggingQueueId = null; dragRemainder = 0f },
                                        onDragCancel = { draggingQueueId = null; dragRemainder = 0f },
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
                            Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = if (draggingQueueId == item.id) colors.primary else colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { onMoveQueueItem(item, index - 1) }, enabled = index > 0 && !isCurrent) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                        IconButton(onClick = { onMoveQueueItem(item, index + 1) }, enabled = index < queue.lastIndex && !isCurrent) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                        IconButton(onClick = { onRemoveFromQueue(item) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove from queue", tint = colors.onSurfaceVariant) }
                    }
                }
            }
        }
      } else if (lyricsOpen) {
        LiquidLyricsScreen(
            track = track,
            lyrics = lyrics,
            loading = lyricsLoading,
            positionMs = playback.positionMs,
            durationMs = duration,
            lyricSizeSp = lyricSizeSp,
            autoScroll = lyricsAutoScroll,
            lyricsOffsetMs = lyricsOffsetMs,
            isFavorite = favorites.contains(track.id),
            onToggleFavorite = { onFavorite(track) },
            onSeek = { position -> onSeek(position) },
            onClose = { lyricsOpen = false },
            onImport = { lyricImporter.launch(arrayOf("text/plain", "application/octet-stream", "application/x-subrip", "*/*")) },
            onSaveText = { text -> onSaveLyrics(track, text) },
            onTryAnotherSource = { onTryAnotherLyricsSource(track) },
            onAdjustOffset = onAdjustLyricsOffset,
            onResetOffset = onResetLyricsOffset,
        )
      } else {
        val photoBackground = settings.playerBackground
        val photoActive = photoBackground != PlayerBackground.NONE
        // Over a photo the theme colours are forced light so text, controls and the waveform stay readable.
        val colors = if (photoActive) colors.copy(
            onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.78f),
            onBackground = Color.White,
        ) else colors
        Box(Modifier.fillMaxSize()) {
        if (photoActive) PlayerBackgroundImage(photoBackground) else NowPlayingBackdrop(track, playback.artworkUri)
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close now playing") }
                Text("NOW PLAYING", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                IconButton(onClick = { lyricsOpen = true }) { Icon(Icons.Rounded.Lyrics, "Open liquid lyrics") }
                IconButton(onClick = { sleepTimerOpen = true }) { Icon(Icons.Rounded.Timer, "Sleep timer", tint = if (playback.sleepTimerRemainingMs > 0) colors.primary else colors.onSurface) }
                IconButton(onClick = { queueOpen = true }) { Icon(Icons.Rounded.QueueMusic, "Open queue") }
            }
            Spacer(Modifier.height(14.dp))
            var artworkDrag by remember(track.id) { mutableFloatStateOf(0f) }
            val openThreshold = with(LocalDensity.current) { 72.dp.toPx() }
            ArtworkImage(track, Modifier.fillMaxWidth().aspectRatio(1f).then(
                if (gesturesEnabled) Modifier.pointerInput(track.id, doubleTapSeekSeconds) {
                    detectTapGestures(onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) onSeekBy(-doubleTapSeekSeconds * 1000L)
                        else onSeekBy(doubleTapSeekSeconds * 1000L)
                    })
                } else Modifier
            ).pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { artworkDrag = 0f },
                    onDragEnd = { if (artworkDrag < -openThreshold) lyricsOpen = true; artworkDrag = 0f },
                    onDragCancel = { artworkDrag = 0f },
                ) { change, amount -> change.consume(); artworkDrag += amount }
            }, artUri = playback.artworkUri)
            Spacer(Modifier.height(8.dp))
            Text("Swipe the artwork sideways for liquid lyrics", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title.ifBlank { track.displayName }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.album.ifBlank { "Unknown album" }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onFavorite(track) }) {
                    Icon(if (track.id in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (track.id in favorites) "Remove favorite" else "Add favorite", tint = if (track.id in favorites) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
                if (track.isRemote) {
                    val dl = LocalDownloadActions.current
                    val downloaded = (track.remoteId ?: track.id.removePrefix("yt:")) in dl.downloadedIds
                    IconButton(onClick = { if (downloaded) dl.onDeleteDownload(track) else dl.onDownload(track) }) {
                        Icon(Icons.Rounded.Download, if (downloaded) "Delete download" else "Download", tint = if (downloaded) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                }
            }
            val qualityBadge = track.qualityBadgeContent()
            if (qualityBadge != null || bluetoothOutputName != null) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualityBadge?.let { NormalQualityBadge(it, onClick = { audioInfoOpen = true }) }
                    bluetoothOutputName?.let { NormalBluetoothBadge(it) }
                }
            }
            // 28dp under the capsules (or the song info when none). The seek label is
            // transient, so it no longer reserves a fixed-height empty box.
            Spacer(Modifier.height(28.dp))
            if (dragging != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        color = colors.primary.copy(alpha = 0.16f),
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            "Seek to ${time((dragging ?: 0f).toLong())}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                        )
                    }
                }
            }
            Slider(value = progress.coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { dragging = it }, onValueChangeFinished = { onSeek((dragging ?: playback.positionMs.toFloat()).toLong()); dragging = null }, valueRange = 0f..duration.coerceAtLeast(1).toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(if (dragging != null) (dragging ?: 0f).toLong() else playback.positionMs), color = if (dragging != null) colors.primary else colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(time(duration), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (playback.shuffle) colors.primary else colors.onSurfaceVariant) }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp)) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Pause" else "Play", modifier = Modifier.size(38.dp)) }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onRepeat) { Icon(Icons.Rounded.Repeat, "Repeat", tint = if (playback.repeat != 0) colors.primary else colors.onSurfaceVariant) }
            }
            playback.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PlayerTool(Icons.Rounded.Equalizer, "Equalizer", onOpenEqualizer)
                PlayerTool(Icons.Rounded.Insights, "Wrapped", onOpenWrapped)
                PlayerTool(Icons.Rounded.CloudUpload, "Backup", onOpenBackup)
                PlayerTool(Icons.Rounded.Edit, "Edit tags", onEditMetadata)
            }
            if (playback.sleepTimerRemainingMs > 0) {
                Text(
                    "Sleep timer · ${time(playback.sleepTimerRemainingMs)} remaining",
                    color = colors.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Up Next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { queueOpen = true }) { Text("Queue · ${queue.size}") }
            }
            upNext.take(3).forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { onPlayQueueItem(item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(item, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (upNext.isEmpty()) Text("Nothing queued after this track", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(28.dp))
        }
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

    if (addSongsOpen) AlertDialog(
        onDismissRequest = { addSongsOpen = false },
        title = { Text("Add songs to queue") },
        text = {
            val queuedIds = remember(queue) { queue.mapTo(mutableSetOf()) { it.id } }
            val candidates = remember(availableTracks, queuedIds) { availableTracks.filterNot { it.id in queuedIds } }
            if (candidates.isEmpty()) Text("All library songs are already queued.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(candidates, key = { _, item -> item.id }) { _, item ->
                    Row(Modifier.fillMaxWidth().clickable { onAddToQueue(item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkImage(item, Modifier.size(42.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.Add, "Add to queue", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addSongsOpen = false }) { Text("Done") } },
    )

    if (sleepTimerOpen) AlertDialog(
        onDismissRequest = { sleepTimerOpen = false },
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text(if (playback.sleepTimerRemainingMs > 0) "Playback will pause in ${time(playback.sleepTimerRemainingMs)}." else "Pause playback after:")
                if (playback.sleepTimerRemainingMs == 0L) {
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        TextButton(onClick = { onStartSleepTimer(minutes); sleepTimerOpen = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("$minutes minutes")
                        }
                    }
                    OutlinedTextField(
                        value = customSleepMinutes,
                        onValueChange = { value -> if (value.length <= 3 && (value.isEmpty() || value.all(Char::isDigit))) customSleepMinutes = value },
                        label = { Text("Custom minutes (1–720)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (playback.sleepTimerRemainingMs > 0) TextButton(onClick = { onCancelSleepTimer(); sleepTimerOpen = false }) { Text("Cancel timer") }
            else {
                TextButton(onClick = { sleepTimerOpen = false }) { Text("Close") }
                TextButton(
                    onClick = { onStartSleepTimer(customSleepMinutes.toInt()); sleepTimerOpen = false },
                    enabled = customSleepMinutes.toIntOrNull() in 1..720,
                ) { Text("Start") }
            }
        },
    )
}

private fun time(ms: Long): String { val total = (ms.coerceAtLeast(0) / 1000).toInt(); return "%d:%02d".format(total / 60, total % 60) }

@Composable
private fun NowPlayingBackdrop(track: Track, artUri: Any? = null) {
    val dark = Auralis.extras.isDark
    val profile = com.auralis.app.ui.performance.LocalPerformanceProfile.current
    val fallback = MaterialTheme.colorScheme.primary
    val target = track.artColor
        ?.let { Color(ArtworkPalette.readableAccent(it, dark)) }
        ?: fallback
    val accent by animateColorAsState(target, tween(850), label = "nowPlayingAccent")
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.26f),
                        background.copy(alpha = 0.92f),
                        background,
                    ),
                ),
            ),
        )
        if (profile.backgroundBlurEnabled) {
            ArtworkImage(
                track,
                Modifier.fillMaxSize().alpha(0.16f).blur(56.dp),
                artUri = artUri,
            )
        } else {
            // No full-screen blur in Medium/Low: a dim static wash keeps the same mood.
            ArtworkImage(
                track,
                Modifier.fillMaxSize().alpha(0.12f),
                artUri = artUri,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(900f, 260f),
                    radius = 1200f,
                ),
            ),
        )
    }
}

@Composable
private fun PlayerTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun NormalQualityBadge(content: QualityBadgeContent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        color = colors.primary.copy(alpha = 0.16f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (content.tag != null) Text(content.tag, style = MaterialTheme.typography.labelSmall, color = colors.primary, modifier = Modifier.padding(end = 8.dp))
            if (content.details.isNotEmpty()) Text(content.details, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun NormalBluetoothBadge(name: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        color = colors.primary.copy(alpha = 0.16f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.padding(end = 6.dp).size(14.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
