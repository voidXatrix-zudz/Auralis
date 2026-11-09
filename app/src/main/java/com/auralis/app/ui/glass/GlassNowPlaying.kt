package com.auralis.app.ui.glass

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.components.AudioInfoSheet
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.components.QualityBadgeContent
import com.auralis.app.ui.components.qualityBadgeContent
import com.auralis.app.ui.components.rememberBluetoothAudioOutputName
import com.auralis.app.ui.screens.LiquidLyricsScreen
import com.auralis.app.ui.theme.ArtworkPalette
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LyricsTypography
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Glassmorphic Now Playing + queue. Same inputs and callbacks as the Normal screen: all playback,
 * queue, lyrics and sleep-timer behaviour is still provided by the caller. Lyrics reuse [LiquidLyricsScreen].
 */
@Composable
fun GlassNowPlayingScreen(
    track: Track,
    queue: List<Track>,
    upNext: List<Track>,
    availableTracks: List<Track>,
    favorites: Set<String>,
    playback: PlaybackController.State,
    settings: AppSettings = AppSettings(),
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    lyricsAutoScroll: Boolean,
    lyricSizeSp: Int,
    lyricsOffsetMs: Int = 0,
    onLoadLyrics: (Track) -> Unit,
    onImportLyrics: (Track, Uri) -> Unit,
    onSaveLyrics: (Track, String) -> Unit,
    onSeekBy: (Long) -> Unit,
    doubleTapSeekSeconds: Int,
    gesturesEnabled: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
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
    modifier: Modifier,
) {
    var queueOpen by remember { mutableStateOf(false) }
    var addSongsOpen by remember { mutableStateOf(false) }
    var sleepTimerOpen by remember { mutableStateOf(false) }
    var customSleepMinutes by rememberSaveable { mutableStateOf("30") }
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    var draggingQueueId by remember { mutableStateOf<String?>(null) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var audioInfoOpen by remember(track.id) { mutableStateOf(false) }
    // ONE shared 0..1 progress drives the in-place lyrics morph. It is an Animatable so a second
    // tap mid-flight simply retargets from the current value/velocity.
    val lyricsProgress = remember { Animatable(if (lyricsOpen) 1f else 0f) }
    val morphScope = rememberCoroutineScope()
    var lyricsMenuOpen by remember { mutableStateOf(false) }
    var fullLyricsOpen by rememberSaveable { mutableStateOf(false) }
    var pasteOpen by rememberSaveable(track.id) { mutableStateOf(false) }
    var pasteText by rememberSaveable(track.id) { mutableStateOf("") }
    val applyLyricsMode: (Boolean) -> Unit = { open ->
        lyricsOpen = open
        if (!open) lyricsMenuOpen = false
        morphScope.launch {
            lyricsProgress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f),
            )
        }
    }
    val lyricImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportLyrics(track, uri)
    }
    LaunchedEffect(track.id) { onLoadLyrics(track) }
    val colors = MaterialTheme.colorScheme
    val bluetoothOutputName = rememberBluetoothAudioOutputName()
    val duration = playback.durationMs.takeIf { it > 0 } ?: track.durationMs
    val position = dragging ?: playback.positionMs.toFloat()
    BackHandler(enabled = queueOpen) { queueOpen = false }
    BackHandler(enabled = lyricsOpen && !queueOpen && !fullLyricsOpen) { applyLyricsMode(false) }

    val accentTarget = track.artColor?.let { Color(ArtworkPalette.readableAccent(it, true)) } ?: GlassTokens.Violet
    val accent by animateColorAsState(accentTarget, tween(850), label = "glassNowPlayingAccent")
    // Play/pause circle takes the album-art colour (already extracted off the main thread at scan
    // time), with a safe fallback to the theme accent when the art colour is missing.
    val playButtonTarget = track.artColor?.let { Color(ArtworkPalette.readableAccent(it, true)) } ?: colors.primary
    val playButtonColor by animateColorAsState(playButtonTarget, tween(300), label = "glassPlayButtonColor")

    Box(modifier.fillMaxSize()) {
        when {
            queueOpen -> {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent))))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Now Playing", { queueOpen = false })
                        Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        TextButton(onClick = { addSongsOpen = true }) { Icon(Icons.Rounded.Add, null); Text("Add songs") }
                        TextButton(onClick = { onClearQueue(); queueOpen = false; onBack() }, enabled = queue.isNotEmpty()) { Icon(Icons.Rounded.ClearAll, null); Text("Clear") }
                    }
                    if (queue.isEmpty()) Text("The queue is empty.", color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                            val isCurrent = item.id == playback.trackId
                            val latestIndex by rememberUpdatedState(index)
                            val latestLastIndex by rememberUpdatedState(queue.lastIndex)
                            val latestMove by rememberUpdatedState(onMoveQueueItem)
                            val density = LocalDensity.current
                            var dragRemainder by remember(item.id) { mutableFloatStateOf(0f) }
                            var dragTargetIndex by remember(item.id) { mutableIntStateOf(index) }
                            GlassPanel(
                                Modifier.fillMaxWidth(),
                                RoundedCornerShape(GlassTokens.RadiusMd),
                                onClick = { onPlayQueueItem(item) },
                            ) {
                                Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    GlassArtwork(item, Modifier.size(48.dp), 14.dp)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) GlassTokens.Violet else colors.onSurface)
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
                                        Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = if (draggingQueueId == item.id) GlassTokens.Violet else colors.onSurfaceVariant)
                                    }
                                    GlassIconAction(Icons.Rounded.ArrowUpward, "Move up", { onMoveQueueItem(item, index - 1) }, enabled = index > 0 && !isCurrent)
                                    GlassIconAction(Icons.Rounded.ArrowDownward, "Move down", { onMoveQueueItem(item, index + 1) }, enabled = index < queue.lastIndex && !isCurrent)
                                    GlassIconAction(Icons.Rounded.RemoveCircleOutline, "Remove from queue", { onRemoveFromQueue(item) })
                                }
                            }
                        }
                    }
                }
            }
            fullLyricsOpen -> Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
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
                    onSeek = { p -> onSeek(p) },
                    onClose = { fullLyricsOpen = false },
                    onImport = { lyricImporter.launch(arrayOf("text/plain", "application/octet-stream", "application/x-subrip", "*/*")) },
                    onSaveText = { text -> onSaveLyrics(track, text) },
                )
            }
            else -> {
                if (settings.playerBackground != PlayerBackground.NONE) PlayerBackgroundImage(settings.playerBackground)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent, Color.Transparent))))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Close now playing", onBack)
                        Text("NOW PLAYING", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        GlassIconButton(
                            Icons.Rounded.Lyrics,
                            if (lyricsOpen) "Hide lyrics" else "Show lyrics",
                            { applyLyricsMode(!lyricsOpen) },
                            tint = if (lyricsOpen) GlassTokens.Violet else colors.onSurface,
                        )
                        GlassIconButton(Icons.Rounded.Timer, "Sleep timer", { sleepTimerOpen = true }, tint = if (playback.sleepTimerRemainingMs > 0) GlassTokens.Violet else colors.onSurface)
                        GlassIconButton(Icons.Rounded.QueueMusic, "Open queue", { queueOpen = true })
                    }
                    Spacer(Modifier.height(18.dp))
                    var artworkDrag by remember(track.id) { mutableFloatStateOf(0f) }
                    val openThreshold = with(LocalDensity.current) { 72.dp.toPx() }
                    // The hero owns both the normal art/title block and the lyrics overlay. Its
                    // layout size never changes between modes, so everything below (seek bar,
                    // transport, tools) stays exactly where it is.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val heroWidth = maxWidth
                        val smallArt = heroWidth * 0.18f
                        var titleRowHeightPx by remember { mutableIntStateOf(0) }
                        val titleRowHeight = with(LocalDensity.current) { titleRowHeightPx.toDp() }
                        val lyricsTop = (if (titleRowHeight > smallArt) titleRowHeight else smallArt) + 10.dp
                        Column(Modifier.fillMaxWidth()) {
                            GlassArtwork(
                                track,
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        val pr = lyricsProgress.value
                                        val s = 1f - 0.82f * pr
                                        scaleX = s
                                        scaleY = s
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        translationY = -8.dp.toPx() * pr
                                    }
                                    .shadow(28.dp, RoundedCornerShape(34.dp), clip = false, ambientColor = accent, spotColor = accent)
                                    .then(
                                        if (gesturesEnabled) Modifier.pointerInput(track.id, doubleTapSeekSeconds) {
                                            detectTapGestures(onDoubleTap = { offset ->
                                                if (offset.x < size.width / 2f) onSeekBy(-doubleTapSeekSeconds * 1000L)
                                                else onSeekBy(doubleTapSeekSeconds * 1000L)
                                            })
                                        } else Modifier,
                                    )
                                    .pointerInput(track.id) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { artworkDrag = 0f },
                                            onDragEnd = { if (artworkDrag < -openThreshold) applyLyricsMode(true); artworkDrag = 0f },
                                            onDragCancel = { artworkDrag = 0f },
                                        ) { change, amount -> change.consume(); artworkDrag += amount }
                                    },
                                radius = 34.dp,
                            )
                            Spacer(Modifier.height(26.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { titleRowHeightPx = it.size.height }
                                    .graphicsLayer {
                                        translationY = -(heroWidth + 26.dp).toPx() * lyricsProgress.value
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .graphicsLayer {
                                            translationX = (smallArt + 14.dp).toPx() * lyricsProgress.value
                                        },
                                ) {
                                    Text(track.title.ifBlank { track.displayName }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.album.ifBlank { "Unknown album" }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                GlassIconButton(
                                    if (track.id in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    if (track.id in favorites) "Remove favorite" else "Add favorite",
                                    { onFavorite(track) },
                                    tint = if (track.id in favorites) GlassTokens.Violet else colors.onSurface,
                                )
                                // The "..." options live in the same row as the title/star, so they
                                // travel up with the rest of the header when lyrics open.
                                if (lyricsOpen) {
                                    Box {
                                        GlassIconButton(
                                            Icons.Rounded.MoreHoriz,
                                            "Lyrics options",
                                            { lyricsMenuOpen = true },
                                            tint = Color.White.copy(alpha = 0.82f),
                                        )
                                        DropdownMenu(expanded = lyricsMenuOpen, onDismissRequest = { lyricsMenuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Paste lyrics") },
                                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                                                onClick = {
                                                    lyricsMenuOpen = false
                                                    pasteText = lyrics?.plain.orEmpty()
                                                    pasteOpen = true
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Import LRC file") },
                                                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                                                onClick = {
                                                    lyricsMenuOpen = false
                                                    lyricImporter.launch(arrayOf("text/plain", "application/octet-stream", "application/x-subrip", "*/*"))
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Full lyrics view") },
                                                leadingIcon = { Icon(Icons.Rounded.Lyrics, null) },
                                                onClick = { lyricsMenuOpen = false; fullLyricsOpen = true },
                                            )
                                        }
                                    }
                                }
                            }
                            val qualityBadge = track.qualityBadgeContent()
                            if (qualityBadge != null || bluetoothOutputName != null) {
                                Column(
                                    Modifier.padding(top = 12.dp).graphicsLayer { alpha = 1f - lyricsProgress.value },
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    qualityBadge?.let { GlassQualityBadge(it, onClick = { audioInfoOpen = true }) }
                                    bluetoothOutputName?.let { GlassBluetoothBadge(it) }
                                }
                            }
                        }
                        InlineLyricsOverlay(
                            trackId = track.id,
                            lyrics = lyrics,
                            loading = lyricsLoading,
                            positionMs = playback.positionMs,
                            lyricsOffsetMs = lyricsOffsetMs,
                            lyricSizeSp = lyricSizeSp,
                            autoScroll = lyricsAutoScroll,
                            interactive = lyricsOpen,
                            onSeek = onSeek,
                            progress = { lyricsProgress.value },
                            topInset = lyricsTop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                        if (dragging != null) Text("Seek to ${time((dragging ?: 0f).toLong())}", style = MaterialTheme.typography.labelMedium, color = GlassTokens.Violet)
                    }
                    val total = duration.coerceAtLeast(1).toFloat()
                    val hasDuration = duration > 0
                    GlassWaveSeek(
                        seed = track.id,
                        progress = if (hasDuration) position / total else 0f,
                        onScrub = { fraction -> if (hasDuration) dragging = fraction * total },
                        onScrubEnd = {
                            if (hasDuration) onSeek((dragging ?: playback.positionMs.toFloat()).toLong())
                            dragging = null
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            time(if (dragging != null) (dragging ?: 0f).toLong() else playback.positionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        Text(
                            time(duration.coerceAtLeast(0L)),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        GlassIconAction(Icons.Rounded.Shuffle, "Shuffle", onShuffle, tint = if (playback.shuffle) GlassTokens.Violet else colors.onSurfaceVariant)
                        GlassIconAction(Icons.Rounded.SkipPrevious, "Previous", onPrevious, tint = colors.onSurface)
                        Box(
                            Modifier
                                .size(76.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = playButtonColor.copy(alpha = 0.45f),
                                    spotColor = playButtonColor.copy(alpha = 0.65f),
                                )
                                .clip(CircleShape)
                                .background(playButtonColor.copy(alpha = 0.85f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                                .clickable(role = Role.Button, onClick = onPlayPause),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playback.playing) "Pause" else "Play",
                                tint = if (playButtonColor.luminance() > 0.55f) Color.Black else Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        GlassIconAction(Icons.Rounded.SkipNext, "Next", onNext, tint = colors.onSurface)
                        GlassIconAction(Icons.Rounded.Repeat, "Repeat", onRepeat, tint = if (playback.repeat != 0) GlassTokens.Violet else colors.onSurfaceVariant)
                    }
                    playback.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(20.dp))
                    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusLg)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlassTool(Icons.Rounded.Equalizer, "Equalizer", onOpenEqualizer)
                            GlassTool(Icons.Rounded.Insights, "Wrapped", onOpenWrapped)
                            GlassTool(Icons.Rounded.CloudUpload, "Backup", onOpenBackup)
                            GlassTool(Icons.Rounded.Edit, "Edit tags", onEditMetadata)
                        }
                    }
                    if (playback.sleepTimerRemainingMs > 0) {
                        Text(
                            "Sleep timer · ${time(playback.sleepTimerRemainingMs)} remaining",
                            color = GlassTokens.Violet,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GlassSectionLabel("Up next", Modifier.weight(1f))
                        TextButton(onClick = { queueOpen = true }) { Text("Queue · ${queue.size}") }
                    }
                    upNext.take(3).forEach { item ->
                        GlassPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), RoundedCornerShape(GlassTokens.RadiusMd), onClick = { onPlayQueueItem(item) }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassArtwork(item, Modifier.size(48.dp), 14.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (upNext.isEmpty()) Text("Nothing queued after this track", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                    Spacer(Modifier.height(28.dp))
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
                        GlassArtwork(item, Modifier.size(42.dp), 12.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(item.title.ifBlank { item.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.Add, "Add to queue", tint = GlassTokens.Violet)
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

    if (pasteOpen) AlertDialog(
        onDismissRequest = { pasteOpen = false },
        title = { Text("Paste lyrics") },
        text = {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("LRC or plain lyrics") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSaveLyrics(track, pasteText); pasteOpen = false }, enabled = pasteText.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("Cancel") } },
    )
}

/**
 * The lyrics layer that fades in inside the hero. It is layered over the normal art/title block,
 * starts below the shrunken art row, and masks its own top/bottom edges. The only per-frame work
 * happens inside graphicsLayer/drawWithContent.
 */
@Composable
private fun InlineLyricsOverlay(
    trackId: String,
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    lyricsOffsetMs: Int,
    lyricSizeSp: Int,
    autoScroll: Boolean,
    interactive: Boolean,
    onSeek: (Long) -> Unit,
    progress: () -> Float,
    topInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val lines = lyrics?.synced.orEmpty()
    val plainLines = remember(lyrics?.plain) {
        lyrics?.plain?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
    val effectivePosition = (positionMs + lyricsOffsetMs).coerceAtLeast(0L)
    val active = remember(lines, effectivePosition) { LrcParser.activeLine(lines, effectivePosition) }
    val listState = rememberLazyListState()
    val blurAvailable = Build.VERSION.SDK_INT >= 31
    val hasLyrics = lines.isNotEmpty() || plainLines.isNotEmpty()

    // A brand-new track starts its own lyrics at the top, even if the previous list was scrolled.
    LaunchedEffect(trackId) { listState.scrollToItem(0) }

    LaunchedEffect(trackId, active, autoScroll, lines.size) {
        if (!interactive || !autoScroll || active < 0 || lines.isEmpty()) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0) return@LaunchedEffect
        val target = viewport * 0.26f
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
        if (info != null) {
            listState.animateScrollBy(
                value = info.offset - target,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f),
            )
        } else {
            listState.animateScrollToItem(active, -target.toInt())
        }
    }

    Box(
        modifier
            .padding(top = topInset)
            .graphicsLayer {
                val pr = progress()
                alpha = ((pr - 0.1f) / 0.9f).coerceIn(0f, 1f)
                translationY = (1f - pr) * 20.dp.toPx()
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.10f to Color.White,
                        0.88f to Color.White,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        when {
            loading && !hasLyrics -> CenteredLyricMessage("Finding lyrics…", lyricSizeSp)
            lines.isNotEmpty() -> LazyColumn(
                state = listState,
                userScrollEnabled = interactive,
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                contentPadding = PaddingValues(top = 44.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    val distance = if (active >= 0) abs(index - active) else index.coerceAtMost(4)
                    InlineLyricLine(
                        text = line.text,
                        active = index == active,
                        distance = distance,
                        sizeSp = lyricSizeSp,
                        blurAvailable = blurAvailable,
                        onClick = if (interactive && (line.timeMs > 0 || line.words.isNotEmpty())) {
                            { onSeek((line.timeMs - lyricsOffsetMs).coerceAtLeast(0L)) }
                        } else null,
                    )
                }
            }
            plainLines.isNotEmpty() -> LazyColumn(
                state = listState,
                userScrollEnabled = interactive,
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                contentPadding = PaddingValues(top = 44.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(plainLines) { _, line ->
                    InlineLyricLine(
                        text = line,
                        active = false,
                        distance = 4,
                        sizeSp = lyricSizeSp,
                        blurAvailable = blurAvailable,
                        onClick = null,
                    )
                }
            }
            else -> CenteredLyricMessage("No lyrics found", lyricSizeSp)
        }
    }
}

@Composable
private fun CenteredLyricMessage(text: String, sizeSp: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = LyricsTypography.familyFor(text),
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InlineLyricLine(
    text: String,
    active: Boolean,
    distance: Int,
    sizeSp: Int,
    blurAvailable: Boolean,
    onClick: (() -> Unit)?,
) {
    val family = remember(text) { LyricsTypography.familyFor(text) }
    val blurDp = when {
        active || !blurAvailable -> 0f
        distance <= 1 -> 2f
        distance == 2 -> 4f
        distance == 3 -> 6f
        else -> 8f
    }
    Text(
        text = text.ifBlank { "♪" },
        color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
        fontFamily = family,
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = (sizeSp + 4).sp,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (blurDp > 0f) Modifier.blur(blurDp.dp) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 5.dp),
    )
}

/** Audio-quality indicator built only from the file's own properties. */
@Composable
private fun GlassQualityBadge(content: QualityBadgeContent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier.clickable(role = Role.Button, onClick = onClick), CircleShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (content.tag != null) Text(content.tag, style = MaterialTheme.typography.labelSmall, color = GlassTokens.Ice, modifier = Modifier.padding(end = 8.dp))
            if (content.details.isNotEmpty()) Text(content.details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Connected Bluetooth output, using the same capsule geometry as the quality badge. */
@Composable
private fun GlassBluetoothBadge(name: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier, CircleShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(14.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GlassTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun time(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
