package com.auralis.app.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.lyrics.WordRanges
import com.auralis.app.ui.lyrics.rememberLyricsPosition
import com.auralis.app.ui.lyrics.wordRanges
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.theme.ArtworkPalette
import com.auralis.app.ui.theme.Auralis
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.LyricsTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A single list row: either a lyric line or the instrumental-gap dots that follow it. */
private sealed interface LyricRow {
    val key: String

    data class Line(val index: Int, val line: LrcLine) : LyricRow {
        override val key: String get() = "line-$index"
    }

    data class Gap(val index: Int, val endMs: Long) : LyricRow {
        override val key: String get() = "gap-$index"
    }
}

/**
 * Full-screen lyrics surface. Line-synced LRC drives a spring auto-follow that keeps the active
 * line about a third from the top; the active line grows/brightens while neighbours shrink, fade and
 * blur. Enhanced LRC word tags fill word by word from a smooth interpolated clock. Everything reads
 * the real player position plus the global offset, so seeking and pausing never desync.
 */
@Composable
fun LiquidLyricsScreen(
    track: Track,
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    lyricSizeSp: Int,
    autoScroll: Boolean,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onImport: () -> Unit,
    onSaveText: (String) -> Unit,
    lyricsOffsetMs: Int = 0,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onTryAnotherSource: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAdjustOffset: (Int) -> Unit = {},
    onResetOffset: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    var pasteOpen by rememberSaveable(track.id) { mutableStateOf(false) }
    var moreOpen by rememberSaveable(track.id) { mutableStateOf(false) }
    val lines = lyrics?.synced.orEmpty()
    val plain = lyrics?.plain
    val effectivePosition = (positionMs + lyricsOffsetMs).coerceAtLeast(0L)
    // Per-frame player position, read only by the active word-timed line so the rest of the list
    // never recomposes on a frame. Advances while playing, freezes on pause, re-bases on every
    // position snapshot (seek) and includes the user's timing offset plus a small lookahead.
    val livePosition = rememberLyricsPosition(positionMs, playing, lyricsOffsetMs)
    // The active line follows the same smooth clock as the word fill, so line emphasis and the word
    // highlight never disagree. derivedStateOf only notifies readers when the line index changes, so
    // neither the list nor the auto-scroll run every frame.
    val active by remember(lines, lyricsOffsetMs) {
        derivedStateOf { LrcParser.activeLine(lines, livePosition.value) }
    }
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val profile = LocalPerformanceProfile.current
    val reduceMotion = rememberReduceMotion()
    val motionEnabled = profile.ambientMotionEnabled && !reduceMotion
    val gradientScale = if (profile.tier == VisualPerformanceTier.MEDIUM) 1.7f else 1f
    val blurAvailable = profile.blurEnabled && Build.VERSION.SDK_INT >= 31 && !reduceMotion

    // Manual scrolling pauses auto-follow and clears the blur; both resume after ~3 s idle.
    var manualScroll by remember { mutableStateOf(false) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            manualScroll = true
        } else if (manualScroll) {
            delay(3_000)
            manualScroll = false
        }
    }
    val clearForTouch = manualScroll

    val fallbackAccent = colors.primary
    val targetAccent = track.artColor
        ?.let { Color(ArtworkPalette.readableAccent(it, Auralis.extras.isDark)) }
        ?: fallbackAccent
    val accent by animateColorAsState(targetAccent, tween(900), label = "lyricsAccent")
    val accent2 = Color(ArtworkPalette.mix(accent.toArgb(), colors.tertiary.toArgb(), 0.55f))
    val accent3 = Color(ArtworkPalette.mix(accent.toArgb(), colors.secondary.toArgb(), 0.45f))
    val base = when {
        glass -> GlassTokens.Indigo
        Auralis.extras.isDark -> Color(0xFF06070B)
        else -> colors.background
    }
    val baseTypography = MaterialTheme.typography
    val lyricsTypography = remember(baseTypography) {
        LyricsTypography.withFamily(baseTypography)
    }
    val headerTitleFamily = remember(track.title) { LyricsTypography.familyFor(track.title) }
    val headerArtistFamily = remember(track.artist) { LyricsTypography.familyFor(track.artist) }

    val flow = if (motionEnabled) rememberInfiniteTransition(label = "lyricsFlow") else null
    val phase = if (flow != null) {
        flow.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween((18_000L * gradientScale).toInt(), easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "lyricsPhase",
        ).value
    } else {
        0f
    }
    val drift = if (flow != null) {
        flow.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween((26_000L * gradientScale).toInt(), easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "lyricsDrift",
        ).value
    } else {
        0.35f
    }

    val rows = remember(lines, durationMs) {
        buildList<LyricRow> {
            lines.forEachIndexed { index, line ->
                add(LyricRow.Line(index, line))
                // A long instrumental stretch after this line (to the next line, or to the end of a
                // trailing section) shows breathing dots, so a gap never looks like a freeze.
                val next = lines.getOrNull(index + 1)
                val gapEnd = next?.timeMs ?: durationMs.takeIf { it > line.timeMs }
                if (gapEnd != null && gapEnd - line.timeMs >= 6_000L) {
                    add(LyricRow.Gap(index, gapEnd))
                }
            }
        }
    }

    // Auto-follow keeps the active line about a third from the top, easing with a critically damped
    // spring so it never overshoots or stutters.
    LaunchedEffect(active, autoScroll, lines.size, clearForTouch) {
        if (!autoScroll || clearForTouch || active < 0 || rows.isEmpty()) return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { it is LyricRow.Line && it.index == active }
        if (rowIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0) return@LaunchedEffect
        val target = viewport * 0.33f
        if (reduceMotion) {
            listState.scrollToItem(rowIndex, -target.toInt())
            return@LaunchedEffect
        }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rowIndex }
        if (info != null) {
            listState.animateScrollBy(
                value = info.offset - target,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            )
        } else {
            listState.animateScrollToItem(rowIndex, -target.toInt())
        }
    }

    var dragX by remember { mutableFloatStateOf(0f) }
    val closeThreshold = with(LocalDensity.current) { 88.dp.toPx() }

    MaterialTheme(typography = lyricsTypography) {
    Surface(modifier.fillMaxSize(), color = base, contentColor = colors.onBackground) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = { if (dragX > closeThreshold) onClose(); dragX = 0f },
                        onDragCancel = { dragX = 0f },
                    ) { change, amount -> change.consume(); dragX += amount }
                },
        ) {
            // Animated artwork-colour wash.
            Canvas(Modifier.fillMaxSize()) {
                val r = min(size.width, size.height)
                if (glass) {
                    // Glass keeps the flat indigo field; no off-palette vertical wash.
                    drawRect(GlassTokens.Indigo)
                } else {
                    drawRect(Brush.verticalGradient(listOf(base, Color(0xFF0A0C12), base)))
                }
                val p = phase * 2f * Math.PI.toFloat()
                val topCenter = Offset(
                    size.width * (0.22f + 0.10f * sin(p)),
                    size.height * (0.26f + 0.05f * cos(p * 0.8f)),
                )
                val bottomCenter = Offset(
                    size.width * (0.80f + 0.08f * cos(p * 0.7f + 1.4f)),
                    size.height * (0.70f + 0.07f * sin(p * 0.9f + 0.6f)),
                )
                val sideCenter = Offset(
                    size.width * (0.55f + 0.18f * sin(p * 0.5f + 2.2f)),
                    size.height * (0.48f + drift * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.36f), Color.Transparent),
                        center = topCenter,
                        radius = r * 0.72f,
                    ),
                    radius = r * 0.72f,
                    center = topCenter,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent2.copy(alpha = 0.24f), Color.Transparent),
                        center = bottomCenter,
                        radius = r * 0.62f,
                    ),
                    radius = r * 0.62f,
                    center = bottomCenter,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent3.copy(alpha = 0.18f), Color.Transparent),
                        center = sideCenter,
                        radius = r * 0.55f,
                    ),
                    radius = r * 0.55f,
                    center = sideCenter,
                )
            }
            // Readability scrim, keeps the top and bottom quiet.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                base.copy(alpha = 0.72f),
                                Color.Transparent,
                                Color.Transparent,
                                base.copy(alpha = 0.82f),
                            ),
                        ),
                    ),
            )

            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to player")
                    }
                    ArtworkImage(
                        track,
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, colors.onSurface.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            track.title.ifBlank { track.displayName },
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = headerTitleFamily),
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                track.artist.ifBlank { "Unknown artist" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = headerArtistFamily),
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            SourceChip(lyrics)
                            if (lyrics?.outOfSync == true) {
                                Spacer(Modifier.width(6.dp))
                                OutOfSyncChip()
                            }
                        }
                    }
                    RoundGlassButton(
                        icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        description = if (isFavorite) "Remove from favourites" else "Add to favourites",
                        tint = if (isFavorite) accent else colors.onSurface,
                        onClick = onToggleFavorite,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        RoundGlassButton(
                            icon = Icons.Rounded.MoreHoriz,
                            description = "Lyrics options",
                            onClick = { moreOpen = true },
                        )
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Try another source") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { moreOpen = false; onTryAnotherSource() },
                            )
                            // Per-song timing nudge (Part J): −5 s…+5 s, saved for this track.
                            DropdownMenuItem(
                                text = { Text("Lyrics earlier by 0.5s") },
                                leadingIcon = { Icon(Icons.Rounded.Remove, null) },
                                enabled = lyricsOffsetMs > -5_000,
                                onClick = { onAdjustOffset(-500) },
                            )
                            DropdownMenuItem(
                                text = { Text("Lyrics later by 0.5s") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                enabled = lyricsOffsetMs < 5_000,
                                onClick = { onAdjustOffset(500) },
                            )
                            if (lyricsOffsetMs != 0) {
                                DropdownMenuItem(
                                    text = { Text("Reset timing (now ${if (lyricsOffsetMs > 0) "+" else ""}${lyricsOffsetMs} ms)") },
                                    leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                                    onClick = { moreOpen = false; onResetOffset() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Paste lyrics") },
                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                                onClick = { moreOpen = false; pasteOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Import LRC file") },
                                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                                onClick = { moreOpen = false; onImport() },
                            )
                        }
                    }
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val centerPadding = maxHeight * 0.50f
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Finding lyrics...",
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        lines.isNotEmpty() -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(top = centerPadding, bottom = centerPadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
                                when (row) {
                                    is LyricRow.Line -> {
                                        val distance = if (active >= 0) abs(row.index - active) else row.index.coerceAtMost(4)
                                        LyricLineRow(
                                            line = row.line,
                                            active = row.index == active,
                                            passed = active >= 0 && row.index < active,
                                            distance = distance,
                                            accent = accent,
                                            accentGlow = profile.glowEnabled,
                                            sizeSp = lyricSizeSp,
                                            blurAvailable = blurAvailable,
                                            blurScale = profile.blurScale,
                                            clearForTouch = clearForTouch,
                                            motionMs = if (reduceMotion) 0 else 420,
                                            positionState = if (row.index == active && row.line.words.isNotEmpty()) livePosition else null,
                                            lyricsOffsetMs = lyricsOffsetMs,
                                            onSeek = onSeek,
                                        )
                                    }
                                    is LyricRow.Gap -> {
                                        val line = lines[row.index]
                                        val visible = active == row.index &&
                                            effectivePosition - line.timeMs > 2_000 &&
                                            row.endMs - effectivePosition > 500
                                        InstrumentalGap(
                                            visible = visible,
                                            accent = accent,
                                            animated = motionEnabled,
                                        )
                                    }
                                }
                            }
                        }
                        !plain.isNullOrBlank() -> UnsyncedLyrics(
                            plain = plain,
                            accent = accent,
                        )
                        else -> EmptyLyricsState(
                            accent = accent,
                            onTryAnother = onTryAnotherSource,
                            onRetry = onRetry,
                            onPaste = { pasteOpen = true },
                            onImport = onImport,
                        )
                    }
                }

                BottomStatus(
                    accent = accent,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasTimeline = lines.isNotEmpty(),
                    offsetMs = lyricsOffsetMs,
                )
            }
        }
    }

    if (pasteOpen) {
        LyricsPasteDialog(
            track = track,
            onDismiss = { pasteOpen = false },
            onSave = { text ->
                onSaveText(text)
                pasteOpen = false
            },
        )
    }
    }
}

@Composable
private fun RoundGlassButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val shape = CircleShape
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.22f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), shape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SourceChip(lyrics: Lyrics?) {
    val source = lyrics?.source ?: return
    val base = when (source) {
        LyricsSource.USER -> "Manual"
        LyricsSource.EMBEDDED -> "Embedded"
        LyricsSource.LRC -> "LRC file"
        LyricsSource.LRCLIB -> lyrics.provider ?: "Online"
    }
    // Part J: the chip shows the online provider plus a match-confidence percentage when known.
    val confidence = lyrics.confidence
    val label = if (source == LyricsSource.LRCLIB && confidence != null) {
        "$base · ${(confidence * 100).toInt()}%"
    } else {
        base
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown for a slowed/sped-up song whose synced lyrics could not be verified against its length. */
@Composable
private fun OutOfSyncChip() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.22f)),
    ) {
        Text(
            "may be out of sync",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun LyricLineRow(
    line: LrcLine,
    active: Boolean,
    passed: Boolean,
    distance: Int,
    accent: Color,
    accentGlow: Boolean,
    sizeSp: Int,
    blurAvailable: Boolean,
    blurScale: Float,
    clearForTouch: Boolean,
    motionMs: Int,
    positionState: State<Long>?,
    lyricsOffsetMs: Int,
    onSeek: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val alpha by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance == 1 -> 0.80f
            distance == 2 -> 0.66f
            distance == 3 -> 0.55f
            else -> 0.44f
        } * if (passed && !active) 0.9f else 1f,
        animationSpec = tween(motionMs),
        label = "lineAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance == 1 -> 0.84f
            distance == 2 -> 0.80f
            else -> 0.76f
        },
        animationSpec = tween(motionMs),
        label = "lineScale",
    )
    val targetBlur = when {
        !blurAvailable || clearForTouch || active -> 0f
        distance == 1 -> 0f
        distance == 2 -> blurScale
        else -> 2.5f * blurScale
    }
    val blurDp by animateDpAsState(targetBlur.dp, tween(motionMs), label = "lineBlur")
    val shift by animateFloatAsState(
        targetValue = if (active) 0f else if (passed) -3f else 3f,
        animationSpec = tween(motionMs),
        label = "lineShift",
    )

    val shape = RoundedCornerShape(14.dp)
    val clickable = line.timeMs > 0 || line.words.isNotEmpty()
    val activeColor = when {
        glass -> GlassTokens.Cream
        Auralis.extras.isDark -> Color.White
        else -> colors.onBackground
    }
    val family = remember(line.text) { LyricsTypography.familyFor(line.text) }
    val activeTextStyle = remember(sizeSp, family, activeColor, accentGlow, accent) {
        TextStyle(
            color = activeColor,
            fontFamily = family,
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = (sizeSp + 9).sp,
            letterSpacing = 0.2.sp,
            shadow = if (accentGlow) {
                Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 16f)
            } else {
                null
            },
        )
    }
    val inactiveTextStyle = remember(sizeSp, family, colors.onSurfaceVariant) {
        TextStyle(
            color = colors.onSurfaceVariant,
            fontFamily = family,
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (sizeSp + 9).sp,
            letterSpacing = 0.1.sp,
        )
    }
    // Precomputed once per line; null means the word tags do not map cleanly onto the display text,
    // so the row falls back to line-level highlighting instead of drawing a wrong fill.
    val ranges = remember(line) { wordRanges(line) }
    val karaoke = active && positionState != null && ranges != null

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                translationY = shift
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (blurDp.value > 0.05f) Modifier.blur(blurDp) else Modifier)
            .clip(shape)
            .drawBehind {
                if (active) {
                    if (accentGlow) {
                        drawRoundRect(
                            color = accent.copy(alpha = 0.12f),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                        drawRoundRect(
                            color = accent.copy(alpha = 0.85f),
                            topLeft = Offset(0f, 10.dp.toPx()),
                            size = Size(3.dp.toPx(), size.height - 20.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    } else {
                        drawRoundRect(
                            color = if (glass) GlassTokens.Cream6 else Color.White.copy(alpha = 0.05f),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                    }
                }
            }
            .clickable(enabled = clickable) {
                val target = (line.timeMs - lyricsOffsetMs).coerceAtLeast(0L)
                onSeek(target)
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (karaoke) {
            WordSyncText(
                line = line,
                ranges = ranges!!,
                position = positionState!!,
                style = activeTextStyle,
                dimColor = activeColor.copy(alpha = 0.42f),
                maxLines = 6,
            )
        } else {
            Text(
                text = AnnotatedString(line.text.ifBlank { "♪" }),
                textAlign = TextAlign.Start,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                style = if (active) activeTextStyle else inactiveTextStyle,
            )
        }
    }
}

/**
 * Draws the active word-timed line as two identical text layers: a dim base and a lit copy clipped
 * to the sung region. Only the clip changes per frame, so the text is laid out once and never
 * re-measured or re-allocated while the song plays. The clip path is a single remembered [Path].
 */
@Composable
private fun WordSyncText(
    line: LrcLine,
    ranges: WordRanges,
    position: State<Long>,
    style: TextStyle,
    dimColor: Color,
    maxLines: Int,
) {
    val display = remember(line.text) { line.text.ifBlank { "♪" } }
    val dimStyle = remember(style, dimColor) { style.copy(color = dimColor) }
    val path = remember(line) { Path() }
    val rtl = remember(display) {
        runCatching { Bidi(display, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel % 2 == 1 }
            .getOrDefault(false)
    }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = display,
            textAlign = TextAlign.Start,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth(),
            style = dimStyle,
            onTextLayout = { layout = it },
        )
        val measured = layout
        if (measured != null) {
            Text(
                text = display,
                textAlign = TextAlign.Start,
                maxLines = maxLines,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val text = measured.layoutInput.text.text
                        val index = LrcParser.activeWord(line.words, position.value)
                        if (index < 0 || index >= ranges.size) {
                            // Before the first word starts: nothing is sung yet.
                            path.rewind()
                            clipPath(path) { this@drawWithContent.drawContent() }
                            return@drawWithContent
                        }
                        if (rtl) {
                            // Right-to-left lines: a left-to-right clip would be wrong, so light the
                            // whole line while it is active instead.
                            this@drawWithContent.drawContent()
                            return@drawWithContent
                        }
                        val word = line.words[index]
                        val start = ranges.starts[index].coerceIn(0, text.length)
                        val end = ranges.ends[index].coerceIn(start, text.length)
                        val span = ((word.endMs ?: word.timeMs) - word.timeMs).coerceAtLeast(1L)
                        val fraction = ((position.value - word.timeMs).toFloat() / span).coerceIn(0f, 1f)
                        path.rewind()
                        val firstLine = measured.getLineForOffset(start)
                        val lastLine = measured.getLineForOffset((end - 1).coerceAtLeast(start))
                        // Everything on the visual lines above the active word was already sung.
                        for (lineIndex in 0 until firstLine) {
                            path.addKaraokeRect(0f, measured.getLineTop(lineIndex), size.width, measured.getLineBottom(lineIndex))
                        }
                        var total = 0f
                        for (lineIndex in firstLine..lastLine) {
                            total += karaokeSegmentWidth(measured, start, end, lineIndex)
                        }
                        var remaining = fraction * total
                        for (lineIndex in firstLine..lastLine) {
                            val segmentStart = maxOf(start, measured.getLineStart(lineIndex))
                            val segmentEnd = minOf(end, measured.getLineEnd(lineIndex, visibleEnd = false))
                            val from = measured.getHorizontalPosition(segmentStart, true)
                            val to = measured.getHorizontalPosition(segmentEnd, true)
                            val width = (to - from).coerceAtLeast(0f)
                            val x = if (remaining >= width) to else from + remaining.coerceIn(0f, width)
                            path.addKaraokeRect(0f, measured.getLineTop(lineIndex), x.coerceIn(0f, size.width), measured.getLineBottom(lineIndex))
                            remaining -= width
                            if (remaining <= 0f) break
                        }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    },
            )
        }
    }
}

/** Horizontal extent of [start]..[end] on one visual line, used to split a wrapped word's fraction. */
private fun karaokeSegmentWidth(layout: TextLayoutResult, start: Int, end: Int, lineIndex: Int): Float {
    val from = maxOf(start, layout.getLineStart(lineIndex))
    val to = minOf(end, layout.getLineEnd(lineIndex, visibleEnd = false))
    if (to <= from) return 0f
    return (layout.getHorizontalPosition(to, true) - layout.getHorizontalPosition(from, true)).coerceAtLeast(0f)
}

/**
 * Appends a rectangle to the clip [Path] without allocating a [androidx.compose.ui.geometry.Rect],
 * so refilling the path every frame stays allocation-free.
 */
private fun Path.addKaraokeRect(left: Float, top: Float, right: Float, bottom: Float) {
    if (right <= left || bottom <= top) return
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
}

@Composable
private fun InstrumentalGap(visible: Boolean, accent: Color, animated: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(300)), exit = fadeOut(tween(240))) {
        val pulse = if (animated) {
            val transition = rememberInfiniteTransition(label = "gapPulse")
            transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(820, easing = LinearEasing), RepeatMode.Reverse),
                label = "gapPulseAlpha",
            ).value
        } else {
            0.75f
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val factors = remember { listOf(1f, 0.75f, 0.55f) }
            factors.forEach { factor ->
                Canvas(Modifier.size(6.dp)) {
                    drawCircle(accent.copy(alpha = pulse * factor))
                }
            }
        }
    }
}

@Composable
private fun UnsyncedLyrics(plain: String, accent: Color) {
    val colors = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(420)), exit = fadeOut()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            Surface(
                color = colors.surface.copy(alpha = 0.42f),
                shape = CircleShape,
                border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.10f)),
            ) {
                Text(
                    "NOT SYNCED",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val plainLines = plain.lineSequence().filter { it.isNotBlank() }.toList()
                itemsIndexed(plainLines) { _, line ->
                    val family = remember(line) { LyricsTypography.familyFor(line) }
                    Text(
                        line.trim(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
                        color = colors.onSurface.copy(alpha = 0.82f),
                        lineHeight = 26.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLyricsState(
    accent: Color,
    onTryAnother: () -> Unit,
    onRetry: () -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface.copy(alpha = 0.55f))
                .border(1.dp, colors.onSurface.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Lyrics, null, tint = accent, modifier = Modifier.size(34.dp))
            Text("No lyrics found", style = MaterialTheme.typography.titleMedium)
            Text(
                "No provider had lyrics for this song. Try another source, retry, or add them yourself.",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = onTryAnother, shape = CircleShape) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                    Text("Try another source", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onRetry, shape = CircleShape) {
                    Icon(Icons.Rounded.Replay, null, modifier = Modifier.size(18.dp))
                    Text("Retry", Modifier.padding(start = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onPaste) {
                    Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Text("Paste LRC", Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onImport) {
                    Icon(Icons.Rounded.FileOpen, null, modifier = Modifier.size(18.dp))
                    Text("Import file", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomStatus(
    accent: Color,
    positionMs: Long,
    durationMs: Long,
    hasTimeline: Boolean,
    offsetMs: Int,
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
        if (hasTimeline) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = accent,
                trackColor = colors.onSurface.copy(alpha = 0.10f),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.MusicNote,
                null,
                tint = accent.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            val offsetLabel = if (offsetMs == 0) "" else "  ·  offset ${if (offsetMs > 0) "+" else ""}${offsetMs} ms"
            Text(
                if (hasTimeline) "${timeLabel(positionMs)}  ·  ${timeLabel(durationMs)}  ·  tap a line to jump$offsetLabel"
                else "Playback continues while you read",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LyricsPasteDialog(track: Track, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(track.id) { mutableStateOf("") }
    var previewOpen by rememberSaveable(track.id) { mutableStateOf(false) }
    var validated by rememberSaveable(track.id) { mutableStateOf(false) }
    // Parsing a long paste is CPU work: debounce keystrokes and run it off the main thread so
    // pasting 200+ lines can never jank the dialog.
    val validation by produceState<LrcParser.Validation?>(null, text) {
        value = null
        if (text.isBlank()) return@produceState
        delay(120)
        value = withContext(Dispatchers.Default) { LrcParser.validate(text) }
    }
    val parsed by produceState<Lyrics?>(null, text) {
        value = null
        if (text.isBlank()) return@produceState
        delay(120)
        value = withContext(Dispatchers.Default) { LrcParser.parse(text) }
    }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    track.title.ifBlank { track.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; validated = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 230.dp),
                    label = { Text("LRC or plain text") },
                    placeholder = { Text("[00:12.40]First line\n[00:16.80][00:20.10]Repeated line") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { previewOpen = !previewOpen }) {
                        Text(if (previewOpen) "Hide preview" else "Preview")
                    }
                    TextButton(onClick = { validated = true }, enabled = text.isNotBlank()) {
                        Text("Validate")
                    }
                }
                if (previewOpen) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface.copy(alpha = 0.5f))
                            .padding(10.dp),
                    ) {
                        if (parsed?.synced.isNullOrEmpty() && parsed?.plain.isNullOrBlank()) {
                            Text("Nothing to preview yet.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } else if (!parsed?.synced.isNullOrEmpty()) {
                            val syncedLines = parsed!!.synced!!
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(syncedLines) { _, line ->
                                    Row {
                                        Text(
                                            timeLabel(line.timeMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(52.dp),
                                        )
                                        Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = LyricsTypography.familyFor(line.text)))
                                    }
                                }
                            }
                        } else {
                            Text(parsed?.plain.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                AnimatedVisibility(visible = validated && text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                    val v = validation
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface.copy(alpha = 0.5f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            when {
                                v == null -> "Checking…"
                                v.hasTimestamps -> "Valid · ${v.timedLines} timed lines"
                                v.canSave -> "Plain text · no timestamps"
                                else -> "Nothing to save"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (v?.canSave == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (v?.hasTimestamps == true) {
                            Text(
                                "${timeLabel(v.firstTimeMs ?: 0)} → ${timeLabel(v.lastTimeMs ?: 0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        v?.warnings?.forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Saving replaces the lyrics shown for this song. The embedded copy inside the audio file is never modified.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = validation?.canSave == true,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun timeLabel(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}