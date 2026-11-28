package com.auralis.app.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported Node Player Classic Full-Screen synchronized lyrics list.
 * Supports O(log N) binary search active line lookup, word-by-word karaoke sweep,
 * 3-dot rhythmic instrumental gap indicator, tap-to-seek, auto-scroll with manual
 * scroll pause + "Jump to current" button, and 14 custom fonts via [LocalLyricsFontResolver].
 */
@Composable
fun ClassicLyricsView(
    lyrics: Lyrics?,
    loading: Boolean,
    playing: Boolean,
    positionMs: Long,
    lyricsOffsetMs: Int,
    autoScroll: Boolean,
    lyricSizeSp: Int,
    lyricsSettings: LyricsSettings,
    accent: Color,
    onSeek: (Long) -> Unit,
    onTryAnotherSource: () -> Unit,
    onRetry: () -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontResolver = LocalLyricsFontResolver.current
    val activeFontFamily = remember(lyricsSettings.fontChoice, fontResolver) {
        fontResolver(lyricsSettings.fontChoice)
    }
    val displayMode = remember(lyricsSettings.lyricsDisplayMode, lyricsSettings.romanizeForeignLyrics) {
        LyricsPresentation.effectiveMode(lyricsSettings)
    }

    CompositionLocalProvider(LocalLyricsFontFamily provides activeFontFamily) {
        val synced = lyrics?.synced
        when {
            loading && synced.isNullOrEmpty() && lyrics?.plain.isNullOrBlank() -> {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Looking for lyrics…", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
                    }
                }
            }
            !synced.isNullOrEmpty() -> {
                val presented = remember(synced, displayMode) {
                    LyricsPresentation.present(synced, displayMode)
                }
                val listState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val livePosition by rememberLyricsPosition(
                    positionMs = positionMs,
                    playing = playing,
                    offsetMs = lyricsOffsetMs,
                )
                val activeIndex by remember(synced) {
                    derivedStateOf { findActiveLyricIndex(synced, livePosition).coerceAtLeast(0) }
                }

                var userScrolledManually by remember { mutableStateOf(false) }

                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
                        if (inProgress) {
                            userScrolledManually = true
                        }
                    }
                }

                LaunchedEffect(userScrolledManually) {
                    if (userScrolledManually) {
                        delay(5_000L)
                        userScrolledManually = false
                    }
                }

                LaunchedEffect(activeIndex, autoScroll, userScrolledManually) {
                    if (autoScroll && !userScrolledManually && !listState.isScrollInProgress && activeIndex in synced.indices) {
                        val targetIndex = (activeIndex - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(targetIndex)
                    }
                }

                val baseColor = Color(lyricsSettings.baseColor.colorArgb)
                val inactiveColor = baseColor.copy(alpha = lyricsSettings.inactiveOpacity)
                val glowColor = Color(lyricsSettings.palette.glowColorArgb)
                val effectiveFontSize = (lyricSizeSp * lyricsSettings.commonZoom * lyricsSettings.fontSizeScale)
                    .coerceIn(14f, 64f).sp

                Box(modifier = modifier.fillMaxSize()) {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 24.dp, bottom = 140.dp, start = 24.dp, end = 24.dp),
                            verticalArrangement = Arrangement.spacedBy((16f * lyricsSettings.verticalSpacing).dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = presented,
                                key = { index, item -> "${item.line.timeMs}_$index" },
                            ) { index, item ->
                                val line = item.line
                                WordByWordKaraokeText(
                                    line = line,
                                    index = index,
                                    activeIndex = activeIndex,
                                    currentPositionMs = livePosition,
                                    positionProvider = { livePosition },
                                    onSeek = { ts -> onSeek((ts - lyricsOffsetMs).coerceAtLeast(0L)) },
                                    activeColor = baseColor,
                                    inactiveColor = inactiveColor,
                                    accentSweepColor = Color(lyricsSettings.palette.litColorArgb),
                                    fontSize = effectiveFontSize,
                                    glowIntensity = lyricsSettings.glowIntensity,
                                    glowColor = glowColor,
                                    isBold = lyricsSettings.isBold,
                                    bounceFactor = lyricsSettings.bounce,
                                    secondaryText = item.secondaryText,
                                )

                                val nextLine = presented.getOrNull(index + 1)?.line
                                val lineEnd = line.endMs ?: nextLine?.timeMs
                                val gapDuration = if (nextLine != null && lineEnd != null) {
                                    nextLine.timeMs - lineEnd
                                } else {
                                    0L
                                }
                                if (gapDuration >= 4000L && index == activeIndex) {
                                    InstrumentalGapIndicator(color = accent)
                                }
                            }
                        }
                    }

                    if (userScrolledManually && activeIndex in synced.indices) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 20.dp, bottom = 16.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f))
                                .clickable {
                                    userScrolledManually = false
                                    coroutineScope.launch {
                                        listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = "Jump to current",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            !lyrics?.plain.isNullOrBlank() -> {
                val baseColor = Color(lyricsSettings.baseColor.colorArgb)
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Text(
                        text = LyricsPresentation.presentPlain(lyrics!!.plain!!, displayMode),
                        fontFamily = activeFontFamily,
                        fontSize = (lyricSizeSp * lyricsSettings.fontSizeScale * 0.78f).coerceIn(14f, 44f).sp,
                        fontWeight = if (lyricsSettings.isBold) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = (lyricSizeSp * lyricsSettings.fontSizeScale * 1.15f).coerceIn(20f, 56f).sp,
                        color = baseColor.copy(alpha = 0.90f),
                    )
                }
            }
            else -> {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No lyrics found",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Try another lyrics source, import an .lrc file, or paste lyrics manually.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onTryAnotherSource) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Try another")
                        }
                        OutlinedButton(onClick = onImport) {
                            Icon(Icons.Rounded.FileOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Import")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPaste) {
                            Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Paste lyrics")
                        }
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
