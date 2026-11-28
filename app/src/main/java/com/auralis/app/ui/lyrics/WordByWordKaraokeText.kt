package com.auralis.app.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.LrcWord
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Binary-search upper-bound lookup matching `lines.indexOfLast { it.timeMs <= positionMs }`
 * in O(log N) for sorted LRC lines.
 */
fun findActiveLyricIndex(lines: List<LrcLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float = 0f): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
        ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(lineLeft, lineRight)
    }
}

private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills: Boolean = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float = if (spills) {
        if (rawNext > lineEndVisible) {
            val rtl = layout.getParagraphDirection(
                visualLine.coerceAtMost(lineEndAll).coerceIn(0, (textLen - 1).coerceAtLeast(0)),
            ) == ResolvedTextDirection.Rtl
            val farLeft = layout.getLineLeft(visualLine)
            val farRight = layout.getLineRight(visualLine)
            if (rtl) farLeft else farRight
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
        }
    } else {
        layout.xOn(rawNext, visualLine, 0f)
    }
    return here + (next - here) * (chars - index)
}

private fun ContentDrawScope.sweepText(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) {
        drawContent()
        return
    }

    val rtl = layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        if (revealedChars <= lineStart) return

        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)

        if (revealedChars >= lineEndAll) {
            clipRect(left = lineLeft, top = top, right = lineRight, bottom = bottom) {
                this@sweepText.drawContent()
            }
        } else {
            val charPos = horizontalAt(layout, revealedChars, visualLine, spans)
            val safeCut = charPos.coerceIn(lineLeft, lineRight)
            val (clipLeft, clipRight) = if (rtl) safeCut to lineRight else lineLeft to safeCut

            if (clipLeft < clipRight) {
                clipRect(left = clipLeft, top = top, right = clipRight, bottom = bottom) {
                    this@sweepText.drawContent()
                }
            }
        }
    }
}

object KaraokeProgressMath {
    fun alphaForDistance(distance: Int): Float = when (distance) {
        0 -> 1.0f
        1 -> 0.58f
        2 -> 0.40f
        else -> 0.28f
    }

    fun scaleForDistance(distance: Int): Float = when (distance) {
        0 -> 1.0f
        1 -> 0.94f
        2 -> 0.90f
        else -> 0.88f
    }
}

private val LrcLine.sweepSpans: List<IntRange>
    get() {
        if (words.isEmpty()) return emptyList()
        val spans = ArrayList<IntRange>(words.size)
        var offset = 0
        for (word in words) {
            val length = word.text.length
            if (length > 0) spans += offset until (offset + length)
            offset += length + 1
        }
        return spans
    }

private fun LrcWord.effectiveEndMs(nextWordStartMs: Long?, lineEndMs: Long?): Long {
    return endMs?.takeIf { it > timeMs }
        ?: nextWordStartMs?.takeIf { it > timeMs }
        ?: lineEndMs?.takeIf { it > timeMs }
        ?: (timeMs + 420L)
}

private fun LrcLine.revealedChars(positionMs: Long): Float {
    if (positionMs < timeMs) return 0f
    if (words.isEmpty()) {
        val end = endMs?.takeIf { it > timeMs } ?: (timeMs + 3500L)
        if (positionMs >= end) return text.length.toFloat()
        val fraction = (positionMs - timeMs).toFloat() / (end - timeMs).toFloat()
        return text.length * fraction.coerceIn(0f, 1f)
    }
    var offset = 0
    for (idx in words.indices) {
        val word = words[idx]
        val wordEnd = word.effectiveEndMs(words.getOrNull(idx + 1)?.timeMs, endMs)
        val length = word.text.length
        if (positionMs <= word.timeMs) return offset.toFloat()
        if (positionMs < wordEnd) {
            val duration = (wordEnd - word.timeMs).coerceAtLeast(1L).toFloat()
            val revealedInWord = length * ((positionMs - word.timeMs) / duration).coerceIn(0f, 1f)
            return offset + revealedInWord.coerceIn(0f, length.toFloat())
        }
        offset += length + 1
    }
    return text.length.toFloat()
}

private fun LrcWord.revealedCharsInWord(positionMs: Long, effectiveEnd: Long): Float {
    if (positionMs <= timeMs) return 0f
    if (positionMs >= effectiveEnd) return text.length.toFloat()
    val duration = (effectiveEnd - timeMs).coerceAtLeast(1L).toFloat()
    return text.length * ((positionMs - timeMs) / duration).coerceIn(0f, 1f)
}

private class Spline(private val xs: FloatArray, private val ys: FloatArray) {
    private val m = FloatArray(xs.size)

    init {
        val n = xs.size
        val cp = FloatArray(n)
        val dp = FloatArray(n)
        for (i in 1 until n - 1) {
            val h0 = xs[i] - xs[i - 1]
            val h1 = xs[i + 1] - xs[i]
            val b = 2f * (h0 + h1)
            val d = 6f * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
            val den = if (i == 1) b else b - h0 * cp[i - 1]
            cp[i] = h1 / den
            dp[i] = if (i == 1) d / den else (d - h0 * dp[i - 1]) / den
        }
        if (n > 2) {
            m[n - 2] = dp[n - 2]
            for (i in n - 3 downTo 1) m[i] = dp[i] - cp[i] * m[i + 1]
        }
    }

    fun at(x0: Float): Float {
        val n = xs.size
        val x = x0.coerceIn(xs.first(), xs.last())
        var i = 0
        while (i < n - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val a = (xs[i + 1] - x) / h
        val b = (x - xs[i]) / h
        return a * ys[i] + b * ys[i + 1] +
            ((a * a * a - a) * m[i] + (b * b * b - b) * m[i + 1]) * h * h / 6f
    }
}

private class Spr(var pos: Float, private val freq: Float, private val damping: Float) {
    var vel = 0f

    fun step(goal: Float, dt: Float) {
        val f = freq * 2f * Math.PI.toFloat()
        val q = exp(-damping * f * dt)
        val c = sqrt(1f - damping * damping)
        val i = cos(dt * f * c)
        val j = sin(dt * f * c)
        val z = j / c
        val y = j / (f * c)
        val o = pos - goal
        val newPos = (o * (i + z * damping) + vel * y) * q + goal
        val newVel = (vel * (i - z * damping) - o * (z * f)) * q
        pos = newPos
        vel = newVel
    }
}

private val BounceScale = Spline(floatArrayOf(0f, 0.7f, 1f), floatArrayOf(0.95f, 1.0505f, 1f))
private val BounceLift = Spline(floatArrayOf(0f, 0.9f, 1f), floatArrayOf(0.01f, -1f / 60f, 0f))

private const val BOUNCE_SCALE_GAIN = 1.6f
private const val BOUNCE_LIFT_GAIN = 6f

private val StartCenterOrigin = TransformOrigin(0f, 0.5f)
private val WordCenterOrigin = TransformOrigin(0.5f, 0.5f)

@Composable
private fun ActiveKaraokeWord(
    word: LrcWord,
    effectiveEndMs: Long,
    textStyle: TextStyle,
    glowTextStyle: TextStyle,
    baseColor: Color,
    sweepColor: Color,
    fontPx: Float,
    bounceFactor: Float,
    positionProvider: () -> Long,
) {
    var bounceScale by remember(word) { mutableFloatStateOf(1f) }
    var bounceLiftPx by remember(word) { mutableFloatStateOf(0f) }
    var framePlayPositionMs by remember(word) { mutableLongStateOf(positionProvider()) }
    val provider by rememberUpdatedState(positionProvider)

    LaunchedEffect(word, bounceFactor) {
        val sScale = Spr(1f, 0.88f, 0.64f)
        val sLift = Spr(0f, 1.45f, 0.4f)
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            last = now
            val pos = provider()
            framePlayPositionMs = pos
            var goalScale = 1f
            var goalLift = 0f
            if (pos >= word.timeMs && pos < effectiveEndMs) {
                val dur = (effectiveEndMs - word.timeMs).coerceAtLeast(1L)
                val p = (pos - word.timeMs).toFloat() / dur
                val strength = (dur / 900f).coerceIn(0.4f, 1f) * bounceFactor
                goalScale = 1f + (BounceScale.at(p) - 1f).coerceAtLeast(-0.02f) * BOUNCE_SCALE_GAIN * strength
                goalLift = BounceLift.at(p) * BOUNCE_LIFT_GAIN * fontPx * strength
            }
            sScale.step(goalScale, dt)
            sLift.step(goalLift, dt)
            bounceScale = sScale.pos
            bounceLiftPx = sLift.pos
        }
    }

    var wordLayout by remember(word.text) { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = bounceScale
            scaleY = bounceScale
            translationY = bounceLiftPx
            transformOrigin = WordCenterOrigin
        },
    ) {
        Text(
            text = word.text,
            style = textStyle,
            color = baseColor,
            onTextLayout = { wordLayout = it },
        )

        Text(
            text = word.text,
            style = glowTextStyle,
            color = sweepColor,
            modifier = Modifier.drawWithContent {
                val layout = wordLayout
                val pos = framePlayPositionMs
                val revealed = word.revealedCharsInWord(pos, effectiveEndMs)
                if (layout != null && revealed > 0f) {
                    sweepText(layout, revealed, emptyList())
                } else if (revealed >= word.text.length) {
                    drawContent()
                }
            },
        )
    }
}

class LyricVsyncClock {
    private var anchorMs: Long = 0L
    private var anchorNanos: Long = 0L
    private var speed: Float = 1.0f
    private var isPlaying: Boolean = false

    fun sync(positionMs: Long, playbackSpeed: Float, playing: Boolean) {
        val now = System.nanoTime()
        val expected = if (isPlaying && anchorNanos != 0L) {
            anchorMs + ((now - anchorNanos) / 1_000_000.0 * speed).toLong()
        } else {
            anchorMs
        }
        if (kotlin.math.abs(positionMs - expected) > 350L || !playing) {
            anchorMs = positionMs
            anchorNanos = now
        }
        speed = playbackSpeed
        isPlaying = playing
    }

    fun currentSmoothMs(): Long {
        if (!isPlaying || anchorNanos == 0L) return anchorMs
        val elapsed = ((System.nanoTime() - anchorNanos) / 1_000_000.0 * speed).toLong()
        return anchorMs + elapsed.coerceAtLeast(0L)
    }
}

/**
 * Ported Node Player karaoke line item with ExtraBold typography, active-line
 * word-by-word spring lift/bounce + syllable clip sweep fill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordKaraokeText(
    line: LrcLine,
    index: Int,
    activeIndex: Int,
    currentPositionMs: Long = 0L,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color(0xFFC0C0C0),
    accentSweepColor: Color = activeColor,
    fontSize: TextUnit = 32.sp,
    positionProvider: () -> Long = { currentPositionMs },
    glowIntensity: Float = 1.0f,
    glowColor: Color = Color.White,
    isBold: Boolean = true,
    bounceFactor: Float = 1.0f,
    selectedForCard: Boolean = false,
    selectionMode: Boolean = false,
    /** Romanized/translated line drawn under the original in [com.auralis.app.domain.model.LyricsDisplayMode.DUAL_LINE]. */
    secondaryText: String? = null,
) {
    val distance = abs(index - activeIndex)
    val isActive = distance == 0

    val targetAlpha = when {
        selectionMode && selectedForCard -> 1.0f
        selectionMode -> 0.42f
        else -> KaraokeProgressMath.alphaForDistance(distance)
    }
    val targetScale = if (selectionMode) 1.0f else KaraokeProgressMath.scaleForDistance(distance)

    val animatedAlpha = remember { Animatable(targetAlpha) }
    val animatedScale = remember { Animatable(targetScale) }

    LaunchedEffect(targetAlpha) {
        animatedAlpha.animateTo(targetAlpha, animationSpec = tween(durationMillis = 320))
    }
    LaunchedEffect(targetScale) {
        animatedScale.animateTo(
            targetScale,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = 170f),
        )
    }

    val interactionSource = remember { MutableInteractionSource() }
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.toPx() }
    val wordSpacing = with(density) { (fontSize * 0.26f).toDp() }

    val lyricsFontFamily = LocalLyricsFontFamily.current

    val subtleGlowShadow = remember(glowIntensity, glowColor) {
        if (glowIntensity > 0.02f) {
            Shadow(
                color = glowColor.copy(alpha = (0.55f * glowIntensity).coerceIn(0f, 1f)),
                offset = Offset.Zero,
                blurRadius = (18f * glowIntensity),
            )
        } else {
            null
        }
    }

    val textStyle = remember(fontSize, isActive, lyricsFontFamily, isBold) {
        TextStyle(
            fontFamily = lyricsFontFamily,
            fontSize = fontSize,
            fontWeight = if (isActive) (if (isBold) FontWeight.Black else FontWeight.Bold) else (if (isBold) FontWeight.Bold else FontWeight.Medium),
            fontSynthesis = FontSynthesis.All,
            lineHeight = (fontSize.value * 1.22f).sp,
            letterSpacing = (-0.65).sp,
            textAlign = TextAlign.Start,
        )
    }

    val activeGlowTextStyle = remember(textStyle, subtleGlowShadow, isBold) {
        textStyle.copy(
            fontWeight = if (isBold) FontWeight.Black else FontWeight.Bold,
            fontSynthesis = FontSynthesis.All,
            shadow = subtleGlowShadow,
        )
    }

    val activeBaseColor = remember(activeColor) { activeColor.copy(alpha = 0.45f) }
    val sweepResolvedColor = if (accentSweepColor != Color.Unspecified) accentSweepColor else activeColor

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = animatedScale.value
                scaleY = animatedScale.value
                this.alpha = animatedAlpha.value
                this.transformOrigin = StartCenterOrigin
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                onSeek(line.timeMs)
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (!selectionMode && isActive && line.words.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(wordSpacing, Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    line.words.forEachIndexed { wordIdx, word ->
                        val nextStart = line.words.getOrNull(wordIdx + 1)?.timeMs
                        val wordEnd = word.effectiveEndMs(nextStart, line.endMs)
                        key("${word.timeMs}_$wordIdx") {
                            ActiveKaraokeWord(
                                word = word,
                                effectiveEndMs = wordEnd,
                                textStyle = textStyle,
                                glowTextStyle = activeGlowTextStyle,
                                baseColor = activeBaseColor,
                                sweepColor = sweepResolvedColor,
                                fontPx = fontPx,
                                bounceFactor = bounceFactor,
                                positionProvider = currentPositionProvider,
                            )
                        }
                    }
                }
            } else if (!selectionMode && isActive) {
                var textLayout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
                var linePlayPosMs by remember(line) { mutableLongStateOf(currentPositionProvider()) }

                LaunchedEffect(line) {
                    while (true) {
                        withFrameNanos { it }
                        linePlayPosMs = currentPositionProvider()
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = line.text,
                        style = textStyle,
                        color = activeBaseColor,
                        onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = line.text,
                        style = activeGlowTextStyle,
                        color = sweepResolvedColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                val layout = textLayout
                                val pos = linePlayPosMs
                                val revealed = line.revealedChars(pos)
                                if (layout != null && revealed > 0f) {
                                    sweepText(layout, revealed, line.sweepSpans)
                                } else if (revealed >= line.text.length) {
                                    drawContent()
                                }
                            },
                    )
                }
            } else {
                Text(
                    text = line.text,
                    style = textStyle,
                    color = if (selectionMode && selectedForCard) activeColor else inactiveColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!selectionMode && !secondaryText.isNullOrBlank()) {
            Text(
                text = secondaryText,
                fontFamily = lyricsFontFamily,
                fontSize = (fontSize.value * 0.56f).sp,
                fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
                color = inactiveColor.copy(alpha = 0.85f),
                lineHeight = (fontSize.value * 0.70f).sp,
                letterSpacing = 0.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}
