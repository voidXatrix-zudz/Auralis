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

/**
 * Audio/frame latency compensation: the sung word lights up this far before it is heard, so the
 * highlight lands on the beat rather than just after it (as Spotify/Apple Music do). 60 ms is the
 * median output latency of Android audio plus a frame; larger values looked visibly early.
 */
const val LYRICS_LOOKAHEAD_MS = 60L

/** True when a line's joined words reproduce its display text, so word timings can drive the render. */
fun canKaraoke(line: LrcLine): Boolean =
    line.words.isNotEmpty() && line.words.joinToString(" ") { it.text } == line.text.trim()

/**
 * Character ranges of each word inside [LrcLine.text], precomputed once per line so the draw phase
 * never searches or allocates. [starts] and [ends] are parallel to the line's word list.
 */
internal class WordRanges(val starts: IntArray, val ends: IntArray) {
    val size: Int get() = starts.size
}

/**
 * Maps each word onto the display text by walking the words in order and skipping the separator
 * whitespace between them. This reproduces the exact display whether the source joins words with
 * spaces (LRC), with none (KRC/CJK), or leaves trailing spaces (TTML). Returns null when the words do
 * not reconstruct the text (punctuation mismatch, extra text), so the caller falls back to
 * line-level highlighting instead of drawing a wrong fill.
 */
internal fun wordRanges(line: LrcLine): WordRanges? {
    val text = line.text
    if (text.isBlank() || line.words.isEmpty()) return null
    val starts = IntArray(line.words.size)
    val ends = IntArray(line.words.size)
    var cursor = 0
    for (index in line.words.indices) {
        val word = line.words[index].text
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (word.isEmpty()) {
            starts[index] = cursor
            ends[index] = cursor
            continue
        }
        if (cursor > text.length - word.length || !text.regionMatches(cursor, word, 0, word.length)) {
            return null
        }
        starts[index] = cursor
        cursor += word.length
        ends[index] = cursor
    }
    // Anything left after the last word must be whitespace, otherwise the mapping is unreliable.
    for (index in cursor until text.length) {
        if (!text[index].isWhitespace()) return null
    }
    return WordRanges(starts, ends)
}

/**
 * Playback-rate estimate between two player-position snapshots, so the highlight interpolates at the
 * right rate when the user plays at 0.5x-2x. Returns 1.0 whenever the interval cannot be trusted
 * (first sample, pause, seek jump, paused interval, implausible ratio), so a bad estimate never
 * speeds the highlight up or down.
 */
internal fun estimatePlaybackSpeed(
    prevPositionMs: Long,
    prevWallMs: Long,
    positionMs: Long,
    wallMs: Long,
    prevPlaying: Boolean,
    playing: Boolean,
): Float {
    if (!prevPlaying || !playing) return 1f
    if (prevPositionMs < 0L || prevWallMs <= 0L) return 1f
    val deltaPosition = positionMs - prevPositionMs
    val deltaWall = wallMs - prevWallMs
    if (deltaPosition <= 0L || deltaWall <= 0L || deltaWall > 2_000L) return 1f
    val speed = deltaPosition.toDouble() / deltaWall
    if (speed.isNaN() || speed < 0.25 || speed > 3.0) return 1f
    return speed.toFloat()
}

/** Carries the previous snapshot across [rememberLyricsPosition]'s effect restarts to estimate speed. */
internal class PlaybackSpeedTracker {
    private var lastPositionMs = -1L
    private var lastWallMs = 0L
    private var lastPlaying = false

    fun observe(positionMs: Long, playing: Boolean, wallMs: Long): Float {
        val speed = estimatePlaybackSpeed(lastPositionMs, lastWallMs, positionMs, wallMs, lastPlaying, playing)
        lastPositionMs = positionMs
        lastWallMs = wallMs
        lastPlaying = playing
        return speed
    }
}

/**
 * A player position that advances every frame while [playing], re-based on each [positionMs]
 * snapshot so seeking, pausing, resuming and song changes re-sync instantly, and including
 * [offsetMs] plus [LYRICS_LOOKAHEAD_MS]. The frame clock interpolates between the 500 ms snapshots
 * at the estimated playback speed, so the highlight glides instead of stepping. Read it only inside
 * the active line: only composables that read it recompose, so the rest of the list is untouched.
 */
@Composable
fun rememberLyricsPosition(positionMs: Long, playing: Boolean, offsetMs: Int): State<Long> {
    val state = remember { mutableLongStateOf(0L) }
    val tracker = remember { PlaybackSpeedTracker() }
    LaunchedEffect(positionMs, playing, offsetMs) {
        val base = (positionMs + offsetMs + LYRICS_LOOKAHEAD_MS).coerceAtLeast(0L)
        val speed = tracker.observe(positionMs, playing, System.nanoTime() / 1_000_000L).toDouble()
        state.longValue = base
        if (!playing) return@LaunchedEffect
        var frameBase = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (frameBase < 0L) frameBase = now
                val elapsedNs = (now - frameBase).toDouble() * speed
                state.longValue = base + (elapsedNs / 1_000_000.0).toLong()
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
