package com.auralis.app.ui.screens

import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.lyrics.WordRanges
import com.auralis.app.ui.lyrics.rememberLyricsPosition
import com.auralis.app.ui.lyrics.wordRanges
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.theme.Auralis
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.LyricsTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * Single source of truth for the lyrics motion. Retune the feel here; nothing else in the lyrics
 * surfaces hard-codes a duration, spring or anchor.
 *
 * The spring is tuned to the reference: one small overshoot (about 2% of a line pitch) that settles
 * in roughly half a second, so each line change reads as a single creamy move. Large jumps (seeks,
 * or opening a song mid-way) use the list's own critically damped scroll instead.
 */
internal object LyricsMotion {
    /** Default resting position of the active line, as a fraction of the viewport height from top. */
    const val ACTIVE_LINE_ANCHOR = 0.58f

    /** Spring for the one-block scroll: damping < 1 gives the single tiny overshoot. */
    const val DAMPING_RATIO = 0.78f
    const val STIFFNESS = 150f

    /** Colour/alpha/scale/blur blend time for active <-> inactive. */
    const val STYLE_TRANSITION_MS = 300

    /** How long auto-follow stays paused after a manual drag. */
    const val USER_SCROLL_RESUME_MS = 3_000L

    /** Blur only the nearest neighbours; farther lines fade without a blur pass (avoids shimmer). */
    const val MAX_BLURRED_NEIGHBORS = 5

    /** Optional per-line styling stagger. 0 keeps every row in lock-step (the default). */
    const val STAGGER_MS_PER_LINE = 0

    /** Constant vertical gap between rows, so the block never stretches. */
    val LINE_GAP = 2.dp
}

/** Dark, transparent-friendly Material scheme used when the lyrics sit on the player's dark mesh. */
@Composable
private fun LyricsDarkSurface(accent: Color, content: @Composable () -> Unit) {
    val scheme = remember(accent) {
        darkColorScheme(
            primary = accent,
            onPrimary = GlassTokens.Indigo,
            background = GlassTokens.Indigo,
            onBackground = GlassTokens.Cream,
            surface = Color(0xFF232B45),
            onSurface = GlassTokens.Cream,
            surfaceVariant = Color(0xFF2A3350),
            onSurfaceVariant = GlassTokens.Cream.copy(alpha = 0.62f),
            secondary = GlassTokens.Cream,
            tertiary = GlassTokens.Cream,
            outline = GlassTokens.Cream.copy(alpha = 0.14f),
            outlineVariant = GlassTokens.Cream.copy(alpha = 0.10f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The lyrics viewport on its own: loading spinner, the synced list with its spring auto-follow, the
 * plain-text list, or the calm empty state. It fills whatever box it is given, so the full-screen
 * lyrics page and the embedded Now Playing lyrics area share every pixel of this logic.
 *
 * [anchorFraction] is where the active line rests, measured from the top of the viewport. The
 * full-screen page uses [LyricsMotion.ACTIVE_LINE_ANCHOR]; the shorter embedded area passes a
 * smaller value so the active line still sits in the upper-middle with lines above and below it.
 *
 * [onDark] wraps the content in a dark scheme for callers whose background is the player's dark mesh
 * (the active line and chips then read as light cream instead of following a light app theme).
 */
@Composable
fun LyricsContent(
    lyrics: Lyrics?,
    loading: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    lyricsOffsetMs: Int,
    autoScroll: Boolean,
    lyricSizeSp: Int,
    accent: Color,
    onSeek: (Long) -> Unit,
    onTryAnotherSource: () -> Unit,
    onRetry: () -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    anchorFraction: Float = LyricsMotion.ACTIVE_LINE_ANCHOR,
    contentHorizontalPadding: Dp = 14.dp,
    onDark: Boolean = false,
    /** Dissolves the list into the background over this distance at the top and bottom edges. */
    edgeFade: Dp = 0.dp,
    /** Changes once the surrounding layout has settled, re-anchoring the active line exactly. */
    reanchorKey: Any? = null,
) {
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
    // Persistent spring for the one-block auto-scroll. Reusing it keeps value *and* velocity across
    // retargets, so a line change that arrives mid-flight never snaps.
    val scrollAnim = remember { Animatable(0f) }
    val profile = LocalPerformanceProfile.current
    val reduceMotion = rememberReduceMotion()
    val motionEnabled = profile.ambientMotionEnabled && !reduceMotion
    val blurAvailable = profile.blurEnabled && Build.VERSION.SDK_INT >= 31 && !reduceMotion
    val motionMs = when {
        reduceMotion -> 0
        profile.isLow -> 150
        else -> LyricsMotion.STYLE_TRANSITION_MS
    }

    // Manual scrolling pauses auto-follow and clears the blur; both resume after ~3 s idle.
    var manualScroll by remember { mutableStateOf(false) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            manualScroll = true
            scrollAnim.stop()
        } else if (manualScroll) {
            delay(LyricsMotion.USER_SCROLL_RESUME_MS)
            manualScroll = false
        }
    }
    val clearForTouch = manualScroll

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

    // One spring drives the whole block: every line moves by the same offset, so the spacing stays
    // constant. Retargeting the same Animatable keeps the current value *and* velocity, so a line
    // change that arrives mid-flight never snaps. The spring has a single tiny overshoot. A far jump
    // (a seek, or opening a song mid-way) uses the list's own animated scroll, which can never leave
    // the active line stranded.
    LaunchedEffect(active, autoScroll, lines.size, clearForTouch, anchorFraction, reanchorKey) {
        if (!autoScroll || clearForTouch || active < 0 || rows.isEmpty()) return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { it is LyricRow.Line && it.index == active }
        if (rowIndex < 0) return@LaunchedEffect
        // Wait for the first real layout instead of silently skipping the very first anchor.
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val anchor = viewport * anchorFraction
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rowIndex }
        if (info == null) {
            scrollAnim.stop()
            if (reduceMotion) {
                listState.scrollToItem(rowIndex, -anchor.toInt())
            } else {
                listState.animateScrollToItem(rowIndex, -anchor.toInt())
            }
            scrollAnim.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            // Remove-animations / animator scale 0: jump straight to the anchor.
            val delta = info.offset - anchor
            if (delta != 0f) listState.dispatchRawDelta(delta)
            scrollAnim.snapTo(0f)
            return@LaunchedEffect
        }
        val target = scrollAnim.value + (info.offset - anchor)
        var last = scrollAnim.value
        scrollAnim.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = LyricsMotion.DAMPING_RATIO,
                stiffness = LyricsMotion.STIFFNESS,
            ),
        ) {
            val delta = value - last
            last = value
            if (delta != 0f) listState.dispatchRawDelta(delta)
        }
        // The move finished; reset so the next target is measured from the settled position.
        scrollAnim.snapTo(0f)
    }

    val viewport: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val centerPadding = maxHeight * anchorFraction
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                lines.isNotEmpty() -> Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (edgeFade > 0.dp) {
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        if (size.height > 0f) {
                                            val band = (edgeFade.toPx() / size.height).coerceIn(0f, 0.4f)
                                            val mask = Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                band to Color.Black,
                                                1f - band to Color.Black,
                                                1f to Color.Transparent,
                                            )
                                            drawRect(brush = mask, blendMode = BlendMode.DstIn)
                                        }
                                    }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = contentHorizontalPadding),
                        contentPadding = PaddingValues(top = centerPadding, bottom = centerPadding),
                        verticalArrangement = Arrangement.spacedBy(LyricsMotion.LINE_GAP),
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
                                        motionMs = motionMs,
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
                }
                !plain.isNullOrBlank() -> UnsyncedLyrics(
                    plain = plain,
                    accent = accent,
                )
                else -> EmptyLyricsState(
                    accent = accent,
                    onTryAnother = onTryAnotherSource,
                    onRetry = onRetry,
                    onPaste = onPaste,
                    onImport = onImport,
                )
            }
        }
    }

    Box(modifier) {
        if (onDark) LyricsDarkSurface(accent) { viewport() } else viewport()
    }
}

/** The lyrics-source chip, e.g. "LyricsPlus 64%". Renders nothing when the source is unknown. */
@Composable
internal fun SourceChip(lyrics: Lyrics?) {
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
internal fun OutOfSyncChip() {
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
    // One animated 0..1 amount drives the highlight, colour and shadow so active <-> inactive
    // blends instead of snapping. Layout-affecting properties (weight, letter spacing) stay
    // constant below, so a line never re-wraps when it becomes active.
    val stagger = if (motionMs == 0) 0 else LyricsMotion.STAGGER_MS_PER_LINE * distance.coerceAtMost(2)
    val highlight by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(motionMs, delayMillis = stagger),
        label = "lineHighlight",
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance == 1 -> 0.80f
            distance == 2 -> 0.66f
            distance == 3 -> 0.55f
            else -> 0.44f
        } * if (passed && !active) 0.9f else 1f,
        animationSpec = tween(motionMs, delayMillis = stagger),
        label = "lineAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance == 1 -> 0.84f
            distance == 2 -> 0.80f
            else -> 0.76f
        },
        animationSpec = tween(motionMs, delayMillis = stagger),
        label = "lineScale",
    )
    // Blur only the nearest few neighbours. Blurring every far row shimmered and cost a pass per
    // line; beyond MAX_BLURRED_NEIGHBORS the alpha fade alone reads the same.
    val targetBlur = when {
        !blurAvailable || clearForTouch || active -> 0f
        distance == 1 -> 0f
        distance <= LyricsMotion.MAX_BLURRED_NEIGHBORS -> blurScale * if (distance == 2) 1f else 2.5f
        else -> 0f
    }
    val blurDp by animateDpAsState(
        targetBlur.dp,
        tween(motionMs, delayMillis = stagger),
        label = "lineBlur",
    )

    val shape = RoundedCornerShape(14.dp)
    val clickable = line.timeMs > 0 || line.words.isNotEmpty()
    val activeColor = when {
        glass -> GlassTokens.Cream
        Auralis.extras.isDark -> Color.White
        else -> colors.onBackground
    }
    val textColor by animateColorAsState(
        targetValue = if (active) activeColor else colors.onSurfaceVariant,
        animationSpec = tween(motionMs, delayMillis = stagger),
        label = "lineTextColor",
    )
    val family = remember(line.text) { LyricsTypography.familyFor(line.text) }
    // Weight and letter spacing are deliberately identical for active and inactive lines: the
    // bundled variable font widens with weight, so changing either would re-wrap the line mid-move.
    val textStyle = remember(sizeSp, family, textColor, accentGlow, accent, highlight) {
        TextStyle(
            color = textColor,
            fontFamily = family,
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = (sizeSp + 9).sp,
            letterSpacing = 0.2.sp,
            shadow = if (accentGlow) {
                Shadow(color = accent.copy(alpha = 0.55f * highlight), blurRadius = 16f)
            } else {
                null
            },
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
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (blurDp.value > 0.05f) Modifier.blur(blurDp) else Modifier)
            .clip(shape)
            .drawBehind {
                if (highlight > 0.01f) {
                    if (accentGlow) {
                        drawRoundRect(
                            color = accent,
                            alpha = 0.12f * highlight,
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                        drawRoundRect(
                            color = accent,
                            alpha = 0.85f * highlight,
                            topLeft = Offset(0f, 10.dp.toPx()),
                            size = Size(3.dp.toPx(), size.height - 20.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    } else {
                        drawRoundRect(
                            color = if (glass) GlassTokens.Cream6 else Color.White.copy(alpha = 0.05f),
                            alpha = highlight,
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
                ranges = ranges,
                position = positionState,
                style = textStyle,
                // The dim base stays constant so the lit clip always reads as "fill", even while the
                // row's colour/alpha are still blending in at the start of the line.
                dimColor = textColor.copy(alpha = 0.42f),
                maxLines = 6,
            )
        } else {
            Text(
                text = AnnotatedString(line.text.ifBlank { "♪" }),
                textAlign = TextAlign.Start,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                style = textStyle,
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
 * Appends a rectangle to the clip [Path] without allocating a Rect, so refilling the path every
 * frame stays allocation-free.
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
