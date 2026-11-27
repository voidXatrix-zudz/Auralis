package com.auralis.app.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
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
import com.auralis.app.ui.glass.GlassMaterialAlertDialog
import com.auralis.app.ui.glass.GlassMenu
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen lyrics surface. Line-synced LRC drives one spring that moves every line together and
 * rests the active line a little below the middle of the viewport; the active line brightens while
 * neighbours dim and blur. Enhanced LRC word tags fill word by word from a smooth interpolated
 * clock. Everything reads the real player position plus the global offset, so seeking and pausing
 * never desync.
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
    val colors = MaterialTheme.colorScheme
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val profile = LocalPerformanceProfile.current
    val gradientScale = if (profile.tier == VisualPerformanceTier.MEDIUM) 1.7f else 1f
    val motionEnabled = profile.ambientMotionEnabled && !rememberReduceMotion()

    val fallbackAccent = colors.primary
    val targetAccent = track.artColor
        ?.let { Color(ArtworkPalette.readableAccent(it, Auralis.extras.isDark)) }
        ?: fallbackAccent
    val accent by animateColorAsState(targetAccent, tween(900), label = "lyricsAccent")
    val accent2 = Color(ArtworkPalette.mix(accent.toArgb(), colors.tertiary.toArgb(), 0.55f))
    val accent3 = Color(ArtworkPalette.mix(accent.toArgb(), colors.secondary.toArgb(), 0.45f))
    val base = when {
        glass -> GlassTokens.Canvas
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
                    // Glass keeps the flat black field; no off-palette vertical wash.
                    drawRect(GlassTokens.Canvas)
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
                        GlassMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Try another source") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { moreOpen = false; onTryAnotherSource() },
                            )
                            // Per-song timing nudge: −5 s…+5 s, saved for this track.
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

                LyricsContent(
                    lyrics = lyrics,
                    loading = loading,
                    playing = playing,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    lyricsOffsetMs = lyricsOffsetMs,
                    autoScroll = autoScroll,
                    lyricSizeSp = lyricSizeSp,
                    accent = accent,
                    onSeek = onSeek,
                    onTryAnotherSource = onTryAnotherSource,
                    onRetry = onRetry,
                    onPaste = { pasteOpen = true },
                    onImport = onImport,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                BottomStatus(
                    accent = accent,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasTimeline = !lyrics?.synced.isNullOrEmpty(),
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
internal fun LyricsPasteDialog(track: Track, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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

    GlassMaterialAlertDialog(
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