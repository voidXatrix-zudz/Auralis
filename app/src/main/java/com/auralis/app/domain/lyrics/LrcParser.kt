package com.auralis.app.domain.lyrics

import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.LrcWord
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.LyricsSync

/**
 * Parses LRC and plain lyric text. Multiple timestamps on one line are expanded, an [offset:]
 * tag is applied, enhanced inline word tags (`<mm:ss.xx>`) are extracted, and malformed lines are
 * kept as plain text instead of being discarded.
 */
object LrcParser {
    private val tagRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTagRegex = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val offsetRegex = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val metadataRegex = Regex("^\\[[a-zA-Z]+:.*]$")
    // Karaoke/speaker labels such as "v1:" / "V2:" that must never reach the display text.
    private val speakerTagRegex = Regex("^\\s*[vV]\\d+:\\s*")
    // Bracketed form of the same label ("[v1: Don't cry]") is a real lyric, not file metadata.
    private val speakerBracketRegex = Regex("^\\s*\\[[vV]\\d+:\\s*")
    // Shortest word fill, so a zero/negative word duration still lights visibly instead of snapping.
    private const val MIN_WORD_MS = 90L

    fun parse(raw: String): Lyrics {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val offsetMs = offsetRegex.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LrcLine>()
        val plain = StringBuilder()
        for (rawLine in text.lineSequence()) {
            val speakerBracket = speakerBracketRegex.find(rawLine)
            val sourceLine = if (speakerBracket == null) {
                rawLine
            } else {
                rawLine.replaceRange(speakerBracket.range, "").trim().removeSuffix("]").trim()
            }
            val matches = tagRegex.findAll(sourceLine).toList()
            val withoutLineTags = sourceLine.replace(tagRegex, "")
            val (content, words) = parseWords(withoutLineTags, offsetMs)
            if (matches.isEmpty() && words.isEmpty()) {
                if (content.isNotEmpty() && !metadataRegex.matches(sourceLine.trim()) &&
                    !metadataRegex.matches("[$content]") && !sourceLine.trim().startsWith("[offset", true)
                ) {
                    plain.appendLine(content)
                }
                continue
            }
            plain.appendLine(content)
            if (content.isEmpty() && words.isEmpty()) {
                // A line that only held a speaker tag ("[00:12.00]v1:") stays as a blank
                // instrumental line with its timestamp instead of being dropped.
                if (withoutLineTags.isBlank()) continue
            }
            val times = matches.mapNotNull { tagTimeMs(it, offsetMs) }
            // A line with word tags but no leading line tag is timed by its first word.
            val lineTimes = times.ifEmpty { listOfNotNull(words.firstOrNull()?.timeMs) }
            for (time in lineTimes) {
                // When one lyric line is repeated later (multiple timestamps), shift its word
                // timings by the same delta so the repeat highlights correctly too.
                val shiftedWords = if (words.isEmpty()) {
                    words
                } else {
                    val delta = time - words.first().timeMs
                    if (delta == 0L) words else words.map { it.copy(timeMs = it.timeMs + delta) }
                }
                lines += LrcLine(time, content, shiftedWords)
            }
        }
        val sorted = lines.sortedBy { it.timeMs }
        val synced = normalize(sorted).takeIf { it.isNotEmpty() }
        val plainText = plain.toString().trim().takeIf { it.isNotBlank() }
        val syncLevel = when {
            synced == null -> LyricsSync.PLAIN
            synced.any { it.words.isNotEmpty() } -> LyricsSync.WORD
            else -> LyricsSync.LINE
        }
        return Lyrics(LyricsSource.USER, raw, synced, plainText, sync = syncLevel)
    }

    /**
     * Normalises every timestamp so the render can never go backwards: lines are ordered and
     * non-negative, a line's end is never before its start, words are ordered, a word's end is the
     * next word's start (never zero or negative), and the last word of a line keeps a null end so it
     * lights on arrival instead of stretching across a possible instrumental gap. A line whose words
     * no longer reproduce its display text (broken/duplicated tags) has its words dropped, so broken
     * word timings never render and the line falls back to line-level sync.
     */
    private fun normalize(lines: List<LrcLine>): List<LrcLine> {
        val sorted = lines.sortedBy { it.timeMs }
        return sorted.mapIndexed { index, line ->
            val start = line.timeMs.coerceAtLeast(0L)
            val nextStart = sorted.getOrNull(index + 1)?.timeMs?.coerceAtLeast(start)
            val words = normalizeWords(line.words, start)
            val usable = words.isNotEmpty() && words.joinToString(" ") { it.text } == line.text.trim()
            line.copy(timeMs = start, words = if (usable) words else emptyList(), endMs = nextStart)
        }
    }

    /** Sorts a line's words by start and widens any zero/negative duration to at least [MIN_WORD_MS]. */
    private fun normalizeWords(words: List<LrcWord>, lineStart: Long): List<LrcWord> {
        if (words.isEmpty()) return words
        val sorted = words.sortedBy { it.timeMs }
        return sorted.mapIndexed { index, word ->
            val start = word.timeMs.coerceAtLeast(lineStart)
            val nextStart = sorted.getOrNull(index + 1)?.timeMs
            val end = when {
                word.endMs != null && word.endMs > start -> word.endMs
                nextStart != null -> maxOf(nextStart, start + MIN_WORD_MS)
                else -> null
            }
            word.copy(timeMs = start, endMs = end)
        }
    }

    fun parse(raw: String, source: LyricsSource): Lyrics = parse(raw).copy(source = source)

    /** Extracts display text and timed words from a line whose line tags were already stripped. */
    private fun parseWords(text: String, offsetMs: Long): Pair<String, List<LrcWord>> {
        val matches = wordTagRegex.findAll(text).toList()
        if (matches.isEmpty()) return text.replace(speakerTagRegex, "").trim() to emptyList()
        val words = mutableListOf<LrcWord>()
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            if (start > end || start > text.length) return@forEachIndexed
            val segment = text.substring(start, end.coerceAtMost(text.length))
                // A speaker label can sit right after a word tag ("<00:12.00>v1:Don't") or lead
                // the segment after the line tag was stripped; strip it and any leading space.
                .replace(speakerTagRegex, "")
                .trim()
            if (segment.isNotEmpty()) {
                tagTimeMs(match, offsetMs)?.let { words += LrcWord(it, segment) }
            }
        }
        val display = text.replace(wordTagRegex, "").replace(speakerTagRegex, "").trim()
        return display to words
    }

    private fun tagTimeMs(match: MatchResult, offsetMs: Long): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fractionText = match.groupValues[3]
        val fraction = when (fractionText.length) {
            0 -> 0L
            1 -> fractionText.toLongOrNull()?.times(100) ?: 0L
            2 -> fractionText.toLongOrNull()?.times(10) ?: 0L
            else -> fractionText.take(3).toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L + seconds * 1_000L + fraction + offsetMs).coerceAtLeast(0L)
    }

    /** Result of checking pasted text before it is saved against a track. */
    data class Validation(
        val timedLines: Int,
        val plainLines: Int,
        val firstTimeMs: Long?,
        val lastTimeMs: Long?,
        val warnings: List<String>,
    ) {
        val hasTimestamps: Boolean get() = timedLines > 0
        val isEmpty: Boolean get() = timedLines == 0 && plainLines == 0
        val canSave: Boolean get() = !isEmpty
    }

    /**
     * Validates pasted LRC text. Timestamps are expanded exactly like [parse], so the counts match
     * what will be displayed after saving; duplicate and out-of-order timestamps become warnings.
     */
    fun validate(raw: String): Validation {
        val parsed = parse(raw)
        val synced = parsed.synced.orEmpty()
        val warnings = mutableListOf<String>()
        if (synced.isEmpty()) warnings += "No [mm:ss.xx] timestamps found. Lyrics will be saved as plain text."
        val duplicates = synced.groupBy { it.timeMs }.filterValues { it.size > 1 }.size
        if (duplicates > 0) warnings += "$duplicates duplicated timestamp(s) will show the same line twice."
        if (synced.zipWithNext().any { (a, b) -> b.timeMs < a.timeMs }) warnings += "Timestamps were out of order and will be sorted."
        if (parsed.plain.isNullOrBlank() && synced.isEmpty()) warnings += "Nothing to save."
        val plainLines = parsed.plain?.lineSequence()?.count { it.isNotBlank() } ?: 0
        return Validation(
            timedLines = synced.size,
            plainLines = plainLines,
            firstTimeMs = synced.firstOrNull()?.timeMs,
            lastTimeMs = synced.lastOrNull()?.timeMs,
            warnings = warnings,
        )
    }

    /** True when [raw] contains at least one usable LRC timestamp (line or word level). */
    fun hasTimestamps(raw: String): Boolean =
        tagRegex.containsMatchIn(raw) || wordTagRegex.containsMatchIn(raw)

    /** Index of the line active at [positionMs], or -1 before the first timestamp. */
    fun activeLine(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    /**
     * Index of the word active at [positionMs] within an already-sorted line, or -1 before the first
     * word. O(log n) so the per-frame render never scans the word list.
     */
    fun activeWord(words: List<LrcWord>, positionMs: Long): Int {
        if (words.isEmpty() || positionMs < words.first().timeMs) return -1
        var low = 0
        var high = words.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (words[middle].timeMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }
}
