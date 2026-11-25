package com.auralis.app.data.lyrics

/**
 * Converts QQ-style QRC (as served by the Better Lyrics `/qq/getLyrics` "Portato" provider) into
 * enhanced LRC, so it flows through the existing [com.auralis.app.domain.lyrics.LrcParser] and the
 * lyrics screens need no changes.
 *
 * QRC wraps its body in the `LyricContent` attribute of `<Lyric_1>`:
 * `[292333,1910]Let (292333,136)it (292469,97)come (292566,202)to (293301,377)you(293678,565)`
 * — each run of text is followed by its own `(startMs,durationMs)` token. Malformed input yields
 * `null` and never throws.
 */
internal object QrcParser {

    private val contentAttr = Regex("LyricContent\\s*=\\s*\"([\\s\\S]*?)\"", RegexOption.IGNORE_CASE)
    private val lineHead = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val wordTiming = Regex("\\((\\d+),(\\d+)\\)")
    private val metadata = Regex("^\\[[A-Za-z]+:.*]$")

    /** Enhanced-LRC text, or null when [xml] is blank or carries no timed lines. */
    fun parse(xml: String?): String? {
        if (xml.isNullOrBlank()) return null
        val body = contentAttr.find(xml)?.groupValues?.get(1) ?: xml
        val decoded = decodeEntities(body)
        val lines = decoded.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !metadata.matches(it) }
            .mapNotNull(::toLrcLine)
            .toList()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun toLrcLine(raw: String): String? {
        val head = lineHead.find(raw) ?: return null
        val lineStart = head.groupValues[1].toLongOrNull() ?: return null
        val text = head.groupValues[3]
        if (text.isBlank()) return "[${timestamp(lineStart)}]"

        val words = ArrayList<Pair<Long, String>>()
        var cursor = 0
        var hasWordTiming = false
        for (match in wordTiming.findAll(text)) {
            hasWordTiming = true
            val wordText = text.substring(cursor, match.range.first)
            val start = match.groupValues[1].toLongOrNull() ?: lineStart
            if (wordText.isNotEmpty()) words += start to wordText
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor)
        if (tail.isNotEmpty()) words += lineStart to tail

        if (!hasWordTiming || words.isEmpty()) {
            return "[${timestamp(lineStart)}]${text.trim()}"
        }
        return buildString {
            append('[').append(timestamp(lineStart)).append(']')
            // Keep every chunk, including the standalone spaces QQ encodes as their own token:
            // LrcParser rebuilds the line text from the literal text between tags, so dropping a
            // space chunk would concatenate words ("ShapeofYou") and lose word-sync.
            for ((start, word) in words) {
                append('<').append(timestamp(start)).append('>').append(word)
            }
        }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun decodeEntities(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&amp;", "&")
}