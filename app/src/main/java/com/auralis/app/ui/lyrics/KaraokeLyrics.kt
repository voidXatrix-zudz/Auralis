package com.auralis.app.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.auralis.app.domain.model.LrcLine
import kotlinx.coroutines.isActive

/** True when a line's joined words reproduce its display text, so word timings can drive the render. */
fun canKaraoke(line: LrcLine): Boolean =
    line.words.isNotEmpty() && line.words.joinToString(" ") { it.text } == line.text.trim()

/**
 * A player position that advances every frame while [playing], re-based on each [positionMs]
 * snapshot so seeking and pausing stay in sync, and including [offsetMs]. Read it only inside the
 * active line: only composables that read it recompose, so the rest of the list is untouched.
 */
@Composable
fun rememberLyricsPosition(positionMs: Long, playing: Boolean, offsetMs: Int): State<Long> {
    val state = remember { mutableLongStateOf((positionMs + offsetMs).coerceAtLeast(0L)) }
    LaunchedEffect(positionMs, playing, offsetMs) {
        val base = (positionMs + offsetMs).coerceAtLeast(0L)
        state.longValue = base
        if (!playing) return@LaunchedEffect
        var frameBase = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (frameBase < 0L) frameBase = now
                state.longValue = base + (now - frameBase) / 1_000_000L
            }
        }
    }
    return state
}

/**
 * Builds a karaoke [AnnotatedString] for one word-timed line: sung words are fully lit, the word
 * being sung fills left-to-right per character, and future words stay dim. Only called for the
 * active line, so per-frame work never touches the rest of the list.
 */
fun karaokeLine(line: LrcLine, positionMs: Long, litColor: Color, dimColor: Color): AnnotatedString =
    buildAnnotatedString {
        line.words.forEachIndexed { index, word ->
            if (index > 0) append(' ')
            val start = word.timeMs
            val end = word.endMs ?: start
            when {
                positionMs >= end -> lit(word.text, litColor)
                positionMs < start -> dimmed(word.text, dimColor)
                else -> appendWordFill(word.text, positionMs, start, end, litColor, dimColor)
            }
        }
    }

private fun AnnotatedString.Builder.lit(text: String, color: Color) =
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.ExtraBold)) { append(text) }

private fun AnnotatedString.Builder.dimmed(text: String, color: Color) =
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(text) }

/** Reveals the word left-to-right: earlier characters are lit, the frontier character fades in. */
private fun AnnotatedString.Builder.appendWordFill(
    text: String,
    positionMs: Long,
    start: Long,
    end: Long,
    litColor: Color,
    dimColor: Color,
) {
    val span = (end - start).coerceAtLeast(1L)
    val progress = ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)
    val length = text.length.coerceAtLeast(1)
    text.forEachIndexed { index, char ->
        val charStart = index.toFloat() / length
        val charEnd = (index + 1).toFloat() / length
        val local = ((progress - charStart) / (charEnd - charStart)).coerceIn(0f, 1f)
        withStyle(
            SpanStyle(
                color = lerp(dimColor, litColor, local),
                fontWeight = if (local > 0.5f) FontWeight.ExtraBold else FontWeight.Medium,
            ),
        ) {
            append(char)
        }
    }
}
