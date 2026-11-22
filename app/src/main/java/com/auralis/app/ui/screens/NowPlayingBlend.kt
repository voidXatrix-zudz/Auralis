package com.auralis.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpeakerPhone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.playback.PlaybackController
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.AudioInfoSheet
import com.auralis.app.ui.components.LocalDownloadActions
import com.auralis.app.ui.components.PlayerBackgroundImage
import com.auralis.app.ui.components.QualityBadgeContent
import com.auralis.app.ui.components.qualityBadgeContent
import com.auralis.app.ui.components.rememberBluetoothAudioOutputName
import com.auralis.app.ui.glass.DialogBackdropBlur
import com.auralis.app.ui.glass.glassWindowPanelColor
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.LyricsTypography
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which middle panel, if any, is expanded. One at a time; all share the same header morph. */
private enum class NowPlayingPanel { NONE, LYRICS, QUEUE }

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
    var panel by rememberSaveable { mutableStateOf(NowPlayingPanel.NONE) }
    var addSongsOpen by rememberSaveable { mutableStateOf(false) }
    var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }
    var customSleepMinutes by rememberSaveable { mutableStateOf("30") }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsPasteOpen by rememberSaveable { mutableStateOf(false) }
    var audioInfoOpen by rememberSaveable { mutableStateOf(false) }
    var audioOutputOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }

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

    BackHandler(enabled = panel != NowPlayingPanel.NONE) { panel = NowPlayingPanel.NONE }
    BackHandler(enabled = panel == NowPlayingPanel.NONE && lyricsOpen) { lyricsOpen = false }
    BackHandler(enabled = panel == NowPlayingPanel.NONE && !lyricsOpen) { onBack() }

    Box(modifier.fillMaxSize()) {
        when {
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
                val density = LocalDensity.current
                val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
                val thumb = 56.dp
                val artLeft = 20.dp
                val artTop = statusTop + 34.dp
                val openHeaderHeight = artTop + thumb + 12.dp
                val side = LocalConfiguration.current.screenWidthDp.dp
                val collapsedScale = if (compact) 0.85f else 1f
                // Height of the flexible artwork region, captured from the layout and fed back to the
                // artwork overlay so the shrink animation starts from the real (taller) art bounds.
                var collapsedArtHeight by remember { mutableStateOf(0.dp) }
                // One clock for the whole morph: artwork scale, offset and corner radius, the height
                // the lyrics area gains, and the compact header fade. No second image, no cross-fade.
                val morph by animateFloatAsState(
                    targetValue = if (panel != NowPlayingPanel.NONE) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 300f),
                    label = "blendHeaderMorph",
                )
                val headerHeight = side * collapsedScale * (1f - morph) + openHeaderHeight * morph

                Column(Modifier.fillMaxSize()) {
                    // Fixed band between the header and the timeline. It is clipped, so no lyric line
                    // can ever draw over the header, the progress bar or the time labels.
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clipToBounds(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Crossfade(
                            targetState = panel,
                            modifier = Modifier.fillMaxSize(),
                            animationSpec = tween(220),
                            label = "blendMiddle",
                        ) { expanded ->
                            when (expanded) {
                            NowPlayingPanel.LYRICS -> Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.height(headerHeight))
                                LyricsContent(
                                    lyrics = lyrics,
                                    loading = lyricsLoading,
                                    playing = playback.playing,
                                    positionMs = playback.positionMs,
                                    durationMs = duration,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    autoScroll = lyricsAutoScroll,
                                    lyricSizeSp = lyricSizeSp,
                                    accent = palette.vibrant,
                                    onSeek = onSeek,
                                    onTryAnotherSource = { onTryAnotherLyricsSource(track) },
                                    onRetry = { onRetryLyrics(track) },
                                    onPaste = { lyricsPasteOpen = true },
                                    onImport = {
                                        lyricImporter.launch(
                                            arrayOf("text/plain", "application/octet-stream", "application/x-subrip", "*/*"),
                                        )
                                    },
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                                    anchorFraction = 0.27f,
                                    topPaddingFraction = 0.25f,
                                    bottomPaddingFraction = 0.65f,
                                    onDark = true,
                                    edgeFade = 40.dp,
                                    reanchorKey = morph > 0.98f,
                                )
                            }
                            NowPlayingPanel.QUEUE -> Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.height(headerHeight))
                                QueuePanel(
                                    queue = queue,
                                    currentId = playback.trackId,
                                    playing = playback.playing,
                                    onPlayQueueItem = onPlayQueueItem,
                                    onRemoveFromQueue = onRemoveFromQueue,
                                    onMoveQueueItem = onMoveQueueItem,
                                    onAddSongs = { addSongsOpen = true },
                                    onClear = onClearQueue,
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                            NowPlayingPanel.NONE -> {
                                // The artwork fills every pixel the fixed info group below it does not
                                // need, so no empty band can form above the timeline. The title and
                                // preview stay pinned right under the art as it fades into the compact
                                // row, so nothing jumps when a panel opens or closes.
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Spacer(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .onGloballyPositioned { coords ->
                                                collapsedArtHeight = with(density) { coords.size.height.toDp() }
                                            },
                                    )
                                    Column(
                                        Modifier
                                            .graphicsLayer { alpha = 1f - morph }
                                            .widthIn(max = 520.dp)
                                            .padding(horizontal = 32.dp),
                                    ) {
                                    TitleBlock(
                                        title = track.title.ifBlank { track.displayName }.ifBlank { "Unknown title" },
                                        subtitle = artistAlbum(track),
                                        isFavorite = isFavorite,
                                        moreOpen = moreOpen,
                                        onMoreOpenChange = { moreOpen = it },
                                        onFavorite = { onFavorite(track) },
                                        playback = playback,
                                        track = track,
                                        lyricsOffsetMs = lyricsOffsetMs,
                                        onAdjustLyricsOffset = onAdjustLyricsOffset,
                                        onResetLyricsOffset = onResetLyricsOffset,
                                        sleepTimerOpen = { sleepTimerOpen = true },
                                        onShuffle = onShuffle,
                                        onRepeat = onRepeat,
                                        onOpenEqualizer = onOpenEqualizer,
                                        onOpenWrapped = onOpenWrapped,
                                        onOpenBackup = onOpenBackup,
                                        onEditMetadata = onEditMetadata,
                                        onAudioInfo = { audioInfoOpen = true },
                                        onOpenFullLyrics = { lyricsOpen = true },
                                    )
                                    Spacer(Modifier.height(if (compact) 9.dp else 12.dp))
                                    LyricsPreviewRow(
                                        lyrics = lyrics,
                                        lyricsLoading = lyricsLoading,
                                        positionMs = playback.positionMs,
                                        lyricsOffsetMs = lyricsOffsetMs,
                                        onClick = { panel = NowPlayingPanel.LYRICS },
                                    )
                                    }
                                }
                            }
                            }
                        }
                    }
                    BlendControls(
                        positionMs = playback.positionMs,
                        durationMs = duration,
                        quality = quality,
                        onQualityClick = { audioOutputOpen = false; audioInfoOpen = true },
                        onSeek = onSeek,
                        playing = playback.playing,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        playbackError = playback.error,
                        bluetoothOutputName = bluetoothOutputName,
                        deviceName = deviceName,
                        lyricsActive = panel == NowPlayingPanel.LYRICS,
                        lyricsLabel = if (panel == NowPlayingPanel.LYRICS) lyricsByLabel(lyrics) else null,
                        onLyricsLabelClick = { lyricsOpen = true },
                        onLyricsToggle = {
                            panel = if (panel == NowPlayingPanel.LYRICS) NowPlayingPanel.NONE else NowPlayingPanel.LYRICS
                        },
                        queueActive = panel == NowPlayingPanel.QUEUE,
                        onQueueToggle = {
                            panel = if (panel == NowPlayingPanel.QUEUE) NowPlayingPanel.NONE else NowPlayingPanel.QUEUE
                        },
                        shuffleActive = playback.shuffle,
                        repeatMode = playback.repeat,
                        onShuffle = onShuffle,
                        onRepeat = onRepeat,
                        onOutput = { audioInfoOpen = false; audioOutputOpen = true },
                        onOutputLabelClick = { audioInfoOpen = false; audioOutputOpen = true },
                        compact = compact,
                        collapsedGaps = 1f - morph,
                    )
                }

                // The artwork itself, drawn above the layout so it can never be clipped away while
                // it shrinks: one image, laid out at its collapsed size and moved only through
                // graphicsLayer. It stays visible for the whole animation and while lyrics are open.
                BlendArtwork(
                    track = track,
                    artUri = playback.artworkUri,
                    placeholder = palette.darkMuted,
                    side = side,
                    artHeight = if (collapsedArtHeight > 0.dp) collapsedArtHeight else side,
                    thumb = thumb,
                    collapsedScale = collapsedScale,
                    leftInset = artLeft,
                    topInset = artTop,
                    morph = morph,
                    panelOpen = panel != NowPlayingPanel.NONE,
                    gesturesEnabled = gesturesEnabled,
                    doubleTapSeekSeconds = doubleTapSeekSeconds,
                    onSeekBy = onSeekBy,
                    onOpenLyrics = { panel = NowPlayingPanel.LYRICS },
                    modifier = Modifier.align(Alignment.TopStart),
                )

                if (morph > 0.02f) {
                    CompactHeaderRow(
                        alpha = morph,
                        topInset = artTop,
                        leftInset = artLeft,
                        thumbnailSize = thumb,
                        track = track,
                        isFavorite = isFavorite,
                        onFavorite = { onFavorite(track) },
                        moreOpen = moreOpen,
                        onMoreOpenChange = { moreOpen = it },
                        playback = playback,
                        lyricsOffsetMs = lyricsOffsetMs,
                        onAdjustLyricsOffset = onAdjustLyricsOffset,
                        onResetLyricsOffset = onResetLyricsOffset,
                        sleepTimerOpen = { sleepTimerOpen = true },
                        onShuffle = onShuffle,
                        onRepeat = onRepeat,
                        onOpenEqualizer = onOpenEqualizer,
                        onOpenWrapped = onOpenWrapped,
                        onOpenBackup = onOpenBackup,
                        onEditMetadata = onEditMetadata,
                        onAudioInfo = { audioInfoOpen = true },
                        onOpenFullLyrics = { lyricsOpen = true },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
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

    if (audioOutputOpen) {
        AudioOutputSheet(
            track = track,
            deviceName = deviceName,
            bluetoothOutputName = bluetoothOutputName,
            onOpenAudioInfo = {
                audioOutputOpen = false
                audioInfoOpen = true
            },
            onDismiss = { audioOutputOpen = false },
        )
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

    if (lyricsPasteOpen) {
        LyricsPasteDialog(
            track = track,
            onDismiss = { lyricsPasteOpen = false },
            onSave = { text ->
                onSaveLyrics(track, text)
                lyricsPasteOpen = false
            },
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Layout blocks                                                                                  */
/* --------------------------------------------------------------------------------------------- */

/**
 * The single artwork composable. It is laid out at its collapsed size and only ever moved through
 * graphicsLayer: the scale, the offset and the corner radius all come from the same [morph] value,
 * so one image shrinks into the top-left thumbnail with no cross-fade and no second bitmap. The melt
 * mask fades out with the morph, leaving a clean rounded square, and the art is never alpha'd away.
 */
@Composable
private fun BlendArtwork(
    track: Track,
    artUri: Any?,
    placeholder: Color,
    side: Dp,
    artHeight: Dp,
    thumb: Dp,
    collapsedScale: Float,
    leftInset: Dp,
    topInset: Dp,
    morph: Float,
    panelOpen: Boolean,
    gesturesEnabled: Boolean,
    doubleTapSeekSeconds: Int,
    onSeekBy: (Long) -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sidePx = with(density) { side.toPx() }
    val artHeightPx = with(density) { artHeight.toPx() }
    val thumbPx = with(density) { thumb.toPx() }
    // The art region is taller than it is wide, so each axis needs its own end scale; both land on a
    // [thumb]-square, so the shrink still ends exactly on the compact thumbnail.
    val targetScaleX = if (sidePx > 0f) (thumbPx / sidePx).coerceIn(0f, 1f) else 1f
    val targetScaleY = if (artHeightPx > 0f) (thumbPx / artHeightPx).coerceIn(0f, 1f) else 1f
    val artScaleX = lerp(collapsedScale, targetScaleX, morph)
    val artScaleY = lerp(collapsedScale, targetScaleY, morph)
    // Centred when the art is the smaller compact square; the shared clock moves it to the corner.
    val startX = sidePx * (1f - collapsedScale) / 2f
    val tx = lerp(startX, with(density) { leftInset.toPx() }, morph)
    val ty = lerp(0f, with(density) { topInset.toPx() }, morph)
    // The clip is applied in the layer's own (unscaled) space, so undo the scale to keep the
    // on-screen corner at 10 dp once the thumbnail has fully formed.
    val cornerLocal = with(density) {
        (10.dp.toPx() * morph / artScaleX.coerceAtLeast(0.0001f)).toDp()
    }
    val melt = (1f - morph).coerceIn(0f, 1f)

    var artworkDrag by remember(track.id) { mutableFloatStateOf(0f) }
    val openThreshold = with(density) { 72.dp.toPx() }

    ArtworkImage(
        track = track,
        modifier = modifier
            .fillMaxWidth()
            .height(artHeight)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = artScaleX
                scaleY = artScaleY
                translationX = tx
                translationY = ty
                // The melt mask needs an isolated layer; the formed thumbnail does not, so the
                // offscreen pass is dropped the moment the fade is gone.
                compositingStrategy =
                    if (melt > 0.002f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                if (morph > 0.001f) {
                    shape = RoundedCornerShape(cornerLocal)
                    clip = true
                }
            }
            .background(placeholder)
            .drawWithCache {
                if (melt <= 0.002f) {
                    onDrawWithContent { drawContent() }
                } else {
                    val mask = Brush.verticalGradient(
                        0f to Color.Black,
                        0.55f to Color.Black,
                        0.80f to Color.Black.copy(alpha = 0.45f * melt),
                        1f to Color.Transparent,
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                }
            }
            .pointerInput(track.id, panelOpen, gesturesEnabled, doubleTapSeekSeconds) {
                if (panelOpen) return@pointerInput
                detectTapGestures(onDoubleTap = { offset ->
                    if (gesturesEnabled) {
                        if (offset.x < size.width / 2f) onSeekBy(-doubleTapSeekSeconds * 1000L)
                        else onSeekBy(doubleTapSeekSeconds * 1000L)
                    }
                })
            }
            .pointerInput(track.id, panelOpen) {
                if (panelOpen) return@pointerInput
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

/** Title, artist, star and more, laid out beside the shrunken artwork thumbnail. */
@Composable
private fun CompactHeaderRow(
    alpha: Float,
    topInset: Dp,
    leftInset: Dp,
    thumbnailSize: Dp,
    track: Track,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    moreOpen: Boolean,
    onMoreOpenChange: (Boolean) -> Unit,
    playback: PlaybackController.State,
    lyricsOffsetMs: Int,
    onAdjustLyricsOffset: (Int) -> Unit,
    onResetLyricsOffset: () -> Unit,
    sleepTimerOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
    onAudioInfo: () -> Unit,
    onOpenFullLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleFamily = remember(track.title) { LyricsTypography.familyFor(track.title) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = topInset + thumbnailSize)
            // A small upward slide as it appears, so it reads as the same block moving up rather
            // than a second header dropping in.
            .graphicsLayer {
                this.alpha = alpha
                translationY = (1f - alpha) * 8.dp.toPx()
            }
            .padding(
                top = topInset,
                start = leftInset + thumbnailSize + 12.dp,
                end = 16.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { track.displayName }.ifBlank { "Unknown title" },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = titleFamily,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().fadeEnd(24.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { "Unknown artist" },
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 14.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().fadeEnd(24.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassCircleButton(
            icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            description = if (isFavorite) "Remove from favourites" else "Add to favourites",
            onClick = onFavorite,
            size = 36.dp,
        )
        Spacer(Modifier.width(6.dp))
        Box {
            GlassCircleButton(
                icon = Icons.Rounded.MoreHoriz,
                description = "More options",
                onClick = { onMoreOpenChange(true) },
                size = 36.dp,
            )
            PlayerMoreMenu(
                expanded = moreOpen,
                onDismiss = { onMoreOpenChange(false) },
                playback = playback,
                track = track,
                lyricsOffsetMs = lyricsOffsetMs,
                onAdjustLyricsOffset = onAdjustLyricsOffset,
                onResetLyricsOffset = onResetLyricsOffset,
                sleepTimerOpen = sleepTimerOpen,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onOpenEqualizer = onOpenEqualizer,
                onOpenWrapped = onOpenWrapped,
                onOpenBackup = onOpenBackup,
                onEditMetadata = onEditMetadata,
                onAudioInfo = onAudioInfo,
                onOpenFullLyrics = onOpenFullLyrics,
            )
        }
    }
}

/**
 * Bottom block: timeline, transport, volume, icon row and the output label. The gaps below the
 * timeline breathe out to the reference spacing while collapsed and settle back to the original
 * values as a panel opens, so the timeline lands exactly where it always did in Lyrics/Queue.
 */
@Composable
private fun BlendControls(
    positionMs: Long,
    durationMs: Long,
    quality: QualityBadgeContent?,
    onQualityClick: () -> Unit,
    onSeek: (Long) -> Unit,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playbackError: String?,
    bluetoothOutputName: String?,
    deviceName: String,
    lyricsActive: Boolean,
    lyricsLabel: String?,
    onLyricsLabelClick: () -> Unit,
    onLyricsToggle: () -> Unit,
    queueActive: Boolean,
    onQueueToggle: () -> Unit,
    shuffleActive: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOutput: () -> Unit,
    onOutputLabelClick: () -> Unit,
    compact: Boolean,
    // 1f while the collapsed layout is showing, 0f once a panel is open. The lower gaps breathe out
    // toward the reference spacing only in the collapsed view, so Lyrics/Queue land exactly as before.
    collapsedGaps: Float,
) {
    val gapLabelsTransport = if (compact) 8.dp else (14f + 22f * collapsedGaps).dp
    val gapTransportVolume = if (compact) 6.dp else (12f + 34f * collapsedGaps).dp
    val gapVolumeIcons = if (compact) 6.dp else (12f + 12f * collapsedGaps).dp
    val gapIconsLabel = if (compact) 6.dp else (6f + 14f * collapsedGaps).dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
        ) {
            BlendTimeline(
                positionMs = positionMs,
                durationMs = durationMs,
                quality = quality,
                centerLabel = lyricsLabel,
                onCenterLabelClick = {
                    if (lyricsLabel != null) onLyricsLabelClick() else onQualityClick()
                },
                onSeek = onSeek,
            )
            Spacer(Modifier.height(gapLabelsTransport))
            TransportRow(
                playing = playing,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )
            playbackError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(gapTransportVolume))
            VolumeRow()
            Spacer(Modifier.height(gapVolumeIcons))
            BottomIconRow(
                lyricsActive = lyricsActive,
                queueActive = queueActive,
                shuffleActive = shuffleActive,
                repeatMode = repeatMode,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onLyrics = onLyricsToggle,
                onOutput = onOutput,
                onQueue = onQueueToggle,
            )
            Spacer(Modifier.height(gapIconsLabel))
            Text(
                text = bluetoothOutputName ?: deviceName,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOutputLabelClick),
            )
            Spacer(Modifier.height(10.dp))
        }
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
    lyricsOffsetMs: Int,
    onAdjustLyricsOffset: (Int) -> Unit,
    onResetLyricsOffset: () -> Unit,
    sleepTimerOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
    onAudioInfo: () -> Unit,
    onOpenFullLyrics: () -> Unit,
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
                lyricsOffsetMs = lyricsOffsetMs,
                onAdjustLyricsOffset = onAdjustLyricsOffset,
                onResetLyricsOffset = onResetLyricsOffset,
                sleepTimerOpen = sleepTimerOpen,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onOpenEqualizer = onOpenEqualizer,
                onOpenWrapped = onOpenWrapped,
                onOpenBackup = onOpenBackup,
                onEditMetadata = onEditMetadata,
                onAudioInfo = onAudioInfo,
                onOpenFullLyrics = onOpenFullLyrics,
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
    lyricsOffsetMs: Int,
    onAdjustLyricsOffset: (Int) -> Unit,
    onResetLyricsOffset: () -> Unit,
    sleepTimerOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMetadata: () -> Unit,
    onAudioInfo: () -> Unit,
    onOpenFullLyrics: () -> Unit,
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
        DropdownMenuItem(
            text = { Text("Full screen lyrics") },
            leadingIcon = { Icon(Icons.Rounded.Lyrics, null) },
            onClick = { onDismiss(); onOpenFullLyrics() },
        )
        // Timing nudge, kept from the full-screen lyrics page so the offset stays adjustable here.
        DropdownMenuItem(
            text = { Text("Lyrics earlier by 0.5s") },
            leadingIcon = { Icon(Icons.Rounded.Remove, null) },
            enabled = lyricsOffsetMs > -5_000,
            onClick = { onAdjustLyricsOffset(-500) },
        )
        DropdownMenuItem(
            text = { Text("Lyrics later by 0.5s") },
            leadingIcon = { Icon(Icons.Rounded.Add, null) },
            enabled = lyricsOffsetMs < 5_000,
            onClick = { onAdjustLyricsOffset(500) },
        )
        if (lyricsOffsetMs != 0) {
            DropdownMenuItem(
                text = { Text("Reset lyrics timing") },
                leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                onClick = { onDismiss(); onResetLyricsOffset() },
            )
        }
    }
}

@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
) {
    Box(
        Modifier
            .size(size)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size - 8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                description,
                tint = Color.White,
                modifier = Modifier.size(if (size <= 40.dp) 18.dp else 20.dp),
            )
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
    centerLabel: String?,
    onCenterLabelClick: () -> Unit,
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
            if (centerLabel == null) {
                Icon(
                    Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = centerLabel ?: qualityLabel(quality),
                color = Color.White.copy(alpha = if (centerLabel != null) 0.7f else 0.85f),
                fontSize = if (centerLabel != null) 12.sp else 13.sp,
                textDecoration = if (centerLabel != null) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button, onClick = onCenterLabelClick),
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
    lyricsActive: Boolean,
    queueActive: Boolean,
    shuffleActive: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLyrics: () -> Unit,
    onOutput: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomIcon(Icons.Rounded.Lyrics, "Lyrics", onLyrics, active = lyricsActive)
        // While the queue is open the middle slot becomes the quick shuffle/repeat pill; the swap
        // cross-fades so the row height (and everything above it) never changes.
        Crossfade(
            targetState = queueActive,
            animationSpec = tween(180),
            label = "queueQuickControls",
        ) { inQueue ->
            if (inQueue) {
                QueueQuickControls(
                    shuffleActive = shuffleActive,
                    repeatMode = repeatMode,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                )
            } else {
                BottomIcon(Icons.Rounded.SurroundSound, "Audio output", onOutput)
            }
        }
        BottomIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue, active = queueActive)
    }
}

/** Compact glass pill shown in place of the output icon while the queue panel is open. */
@Composable
private fun QueueQuickControls(
    shuffleActive: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
            .padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickToggle(Icons.Rounded.Shuffle, "Shuffle", shuffleActive, onShuffle)
        QuickToggle(
            icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = "Repeat",
            active = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onRepeat,
        )
    }
}

@Composable
private fun QuickToggle(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f)),
            )
        }
        Icon(
            icon,
            description,
            tint = Color.White.copy(alpha = if (active) 1f else 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BottomIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Selected state: a subtle pressed glass square behind the lyrics glyph only.
        if (active) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
            )
        }
        Icon(
            icon,
            description,
            tint = Color.White.copy(alpha = if (active) 1f else 0.88f),
            modifier = Modifier.size(26.dp),
        )
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
    trackColor: Color = Color.White.copy(alpha = 0.48f),
    activeColor: Color = Color.White,
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
                .background(trackColor),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(activeColor),
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Queue                                                                                          */
/* --------------------------------------------------------------------------------------------- */

/**
 * The queue panel that now lives inside Now Playing, between the header and the timeline. It reuses
 * the exact same reorder/remove/play callbacks as the old full-screen page, and fades its top and
 * bottom edges so rows never collide with the header or the timeline.
 */
@Composable
private fun QueuePanel(
    queue: List<Track>,
    currentId: String?,
    playing: Boolean,
    onPlayQueueItem: (Track) -> Unit,
    onRemoveFromQueue: (Track) -> Unit,
    onMoveQueueItem: (Track, Int) -> Unit,
    onAddSongs: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var menuOpen by remember { mutableStateOf(false) }

    // Bring the playing track into view once, when the panel appears.
    LaunchedEffect(currentId) {
        val index = queue.indexOfFirst { it.id == currentId }
        if (index >= 0) listState.scrollToItem(index)
    }
    // Forget selections that left the queue (removed elsewhere, track changed).
    LaunchedEffect(queue) {
        if (selectedIds.isNotEmpty()) {
            val ids = queue.mapTo(HashSet()) { it.id }
            val kept = selectedIds.filter { it in ids }.toSet()
            if (kept.size != selectedIds.size) selectedIds = kept
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Text(
                    text = if (selectedIds.isEmpty()) "Select songs" else "${selectedIds.size} selected",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        queue.filter { it.id in selectedIds }.forEach(onRemoveFromQueue)
                        selectedIds = emptySet()
                        selectMode = false
                    },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("Remove", color = Color.White.copy(alpha = if (selectedIds.isEmpty()) 0.35f else 0.9f))
                }
                TextButton(onClick = { selectMode = false; selectedIds = emptySet() }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.9f))
                }
            } else {
                Text(
                    text = "Queue",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectMode = true }, enabled = queue.isNotEmpty()) {
                    Text("Select", color = Color.White.copy(alpha = if (queue.isEmpty()) 0.35f else 0.9f))
                }
                Box {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).clickable(role = Role.Button) { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            "Queue options",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add songs") },
                            leadingIcon = { Icon(Icons.Rounded.Add, null) },
                            onClick = { menuOpen = false; onAddSongs() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear queue") },
                            leadingIcon = { Icon(Icons.Rounded.ClearAll, null) },
                            enabled = queue.isNotEmpty(),
                            onClick = { menuOpen = false; onClear() },
                        )
                    }
                }
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Nothing up next", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Songs you queue will show up here.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = onAddSongs) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("Add songs", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (size.height > 0f) {
                            val band = (40.dp.toPx() / size.height).coerceIn(0f, 0.4f)
                            val mask = Brush.verticalGradient(
                                0f to Color.Transparent,
                                band to Color.Black,
                                1f - band to Color.Black,
                                1f to Color.Transparent,
                            )
                            drawRect(brush = mask, blendMode = BlendMode.DstIn)
                        }
                    },
            ) {
                var draggingId by remember { mutableStateOf<String?>(null) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        QueuePanelRow(
                            item = item,
                            index = index,
                            lastIndex = queue.lastIndex,
                            isCurrent = item.id == currentId,
                            playing = playing,
                            selected = item.id in selectedIds,
                            selectMode = selectMode,
                            dragging = draggingId == item.id,
                            onToggleSelected = {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            },
                            onClick = { if (selectMode) {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onPlayQueueItem(item)
                            } },
                            onRemove = { onRemoveFromQueue(item) },
                            onDragStart = { draggingId = item.id },
                            onDragEnd = { draggingId = null },
                            onDragMove = { target -> onMoveQueueItem(item, target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePanelRow(
    item: Track,
    index: Int,
    lastIndex: Int,
    isCurrent: Boolean,
    playing: Boolean,
    selected: Boolean,
    selectMode: Boolean,
    dragging: Boolean,
    onToggleSelected: () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val latestIndex by rememberUpdatedState(index)
    val latestLastIndex by rememberUpdatedState(lastIndex)
    val latestMove by rememberUpdatedState(onDragMove)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestEnd by rememberUpdatedState(onDragEnd)
    var dragRemainder by remember(item.id) { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember(item.id) { mutableStateOf(index) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color.White,
                    uncheckedColor = Color.White.copy(alpha = 0.6f),
                    checkmarkColor = Color.Black,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
        ArtworkImage(item, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        Icons.Rounded.Equalizer,
                        contentDescription = if (playing) "Playing" else "Paused",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = item.title.ifBlank { item.displayName },
                    color = Color.White,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (isCurrent) "Playing · ${item.artist.ifBlank { "Unknown artist" }}" else item.artist.ifBlank { "Unknown artist" },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .pointerInput(item.id, selectMode) {
                    if (selectMode) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            latestStart()
                            dragRemainder = 0f
                            dragTargetIndex = latestIndex
                        },
                        onDragEnd = { latestEnd(); dragRemainder = 0f },
                        onDragCancel = { latestEnd(); dragRemainder = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragRemainder += amount.y
                        val step = with(density) { 60.dp.toPx() }
                        while (dragRemainder >= step && dragTargetIndex < latestLastIndex) {
                            dragTargetIndex += 1
                            latestMove(dragTargetIndex)
                            dragRemainder -= step
                        }
                        while (dragRemainder <= -step && dragTargetIndex > 0) {
                            dragTargetIndex -= 1
                            latestMove(dragTargetIndex)
                            dragRemainder += step
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.DragHandle,
                "Drag to reorder",
                tint = if (dragging) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp),
            )
        }
        if (selectMode) {
            Box(
                Modifier
                    .size(36.dp)
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.RemoveCircleOutline,
                    "Remove from queue",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The old full-screen queue page. Now Playing no longer navigates to it, but it is kept for other
 * entry points; it shares the same callbacks as [QueuePanel].
 */
@Composable
internal fun QueueScreen(
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
/* Audio output sheet                                                                             */
/* --------------------------------------------------------------------------------------------- */

/** The route currently carrying media, refreshed as devices connect and disconnect. */
private data class ActiveOutputRoute(val type: Int, val productName: String?)

private const val ACTION_MEDIA_OUTPUT_PANEL = "android.settings.panel.action.MEDIA_OUTPUT"
private const val ACTION_VOLUME_PANEL = "android.settings.panel.action.VOLUME"

/** Reads the active media route without touching any permission-gated API. Best effort only. */
private fun readActiveOutputRoute(manager: AudioManager?): ActiveOutputRoute {
    val safe = manager ?: return ActiveOutputRoute(AudioDeviceInfo.TYPE_UNKNOWN, null)
    val device = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            safe.getAudioDevicesForAttributes(attributes).firstOrNull()
        } else {
            safe.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
        }
    }.getOrNull() ?: return ActiveOutputRoute(AudioDeviceInfo.TYPE_UNKNOWN, null)
    return ActiveOutputRoute(
        type = device.type,
        productName = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** Live active route, registered and unregistered with the sheet's lifecycle. */
@Composable
private fun rememberActiveOutputRoute(): ActiveOutputRoute {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var route by remember(manager) { mutableStateOf(readActiveOutputRoute(manager)) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                route = readActiveOutputRoute(manager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                route = readActiveOutputRoute(manager)
            }
        }
        val registered = runCatching {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        route = readActiveOutputRoute(manager)
        onDispose { if (registered) runCatching { manager.unregisterAudioDeviceCallback(callback) } }
    }
    return route
}

private fun isBluetoothType(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

private fun outputIcon(type: Int, bluetooth: Boolean): ImageVector = when {
    bluetooth -> Icons.Rounded.Bluetooth
    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> Icons.Rounded.Headphones
    type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> Icons.Rounded.Usb
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Rounded.SpeakerPhone
    else -> Icons.AutoMirrored.Rounded.VolumeUp
}

/** One-line stream summary for the Audio Pipeline row, e.g. "FLAC · 16-bit · 44.1 kHz". */
private fun audioPipelineSummary(track: Track): String {
    val codec = track.codec?.takeIf { it.isNotBlank() }?.uppercase()
        ?: track.ext.takeIf { it.isNotBlank() }?.uppercase()
    val bitDepth = track.bitDepth?.takeIf { it > 0 }
    val sampleRate = track.sampleRateHz?.takeIf { it > 0 }
    if (codec == null && bitDepth == null && sampleRate == null) return "Not reported"
    return buildList {
        add(codec ?: if (track.lossless) "Lossless" else "Lossy")
        bitDepth?.let { add("$it-bit") }
        sampleRate?.let { add("%.1f kHz".format(it / 1000f)) }
    }.joinToString(" · ")
}

/**
 * Opens the system's media-output surface. There is no public "media output switcher" intent, so this
 * tries the Android 13 output panel, then the volume panel, then Bluetooth settings, and only shows a
 * toast if none of them resolve. No Cast SDK and no new permission.
 */
private fun openSystemMediaOutput(context: Context) {
    val candidates = buildList {
        add(Intent(ACTION_MEDIA_OUTPUT_PANEL))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Intent(ACTION_VOLUME_PANEL))
        add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    for (intent in candidates) {
        val launched = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (launched) return
    }
    Toast.makeText(context, "Couldn't open audio output settings", Toast.LENGTH_SHORT).show()
}

/** A single rounded glass row inside the Audio output sheet. */
@Composable
private fun OutputRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primary.copy(alpha = 0.14f) else colors.onSurface.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.18f)),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/**
 * The Audio output bottom sheet: current route, Cast hand-off, the media volume slider (same
 * STREAM_MUSIC observer as the Now Playing row) and a shortcut into the existing Audio info sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioOutputSheet(
    track: Track,
    deviceName: String,
    bluetoothOutputName: String?,
    onOpenAudioInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val route = rememberActiveOutputRoute()
    val volume = rememberMediaVolume()
    val isBluetooth = bluetoothOutputName != null || isBluetoothType(route.type)
    val outputTitle = if (isBluetooth) (bluetoothOutputName ?: route.productName ?: deviceName) else deviceName

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (glass) glassWindowPanelColor() else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        if (glass) DialogBackdropBlur()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Audio output", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutputRow(
                icon = outputIcon(route.type, isBluetooth),
                title = outputTitle,
                subtitle = "Playing here",
                selected = true,
                trailing = {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Current output",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = null,
            )
            Spacer(Modifier.height(10.dp))
            OutputRow(
                icon = Icons.Rounded.Cast,
                title = "Cast",
                subtitle = "Chromecast and Google Cast speakers",
                selected = false,
                trailing = {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { openSystemMediaOutput(context) },
            )
            Spacer(Modifier.height(14.dp))
            val colors = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "Volume",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                SlimSlider(
                    value = volume.value,
                    onChange = volume.set,
                    modifier = Modifier.weight(1f),
                    trackColor = colors.onSurface.copy(alpha = 0.18f),
                    activeColor = colors.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            OutputRow(
                icon = Icons.Rounded.Equalizer,
                title = "Audio Pipeline",
                subtitle = audioPipelineSummary(track),
                selected = false,
                trailing = {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = onOpenAudioInfo,
            )
            Spacer(Modifier.height(24.dp))
        }
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

/**
 * "Lyrics by ..." for the timeline label while lyrics mode is open. Uses the same source names as the
 * provider chip it replaces, so moving it out of the header loses nothing.
 */
private fun lyricsByLabel(lyrics: Lyrics?): String? {
    val source = lyrics?.source ?: return null
    val name = when (source) {
        LyricsSource.USER -> "you"
        LyricsSource.EMBEDDED -> "the file"
        LyricsSource.LRC -> "LRC file"
        LyricsSource.LRCLIB -> lyrics.provider ?: "online"
    }
    return if (lyrics.outOfSync) "Lyrics by $name · out of sync" else "Lyrics by $name"
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
