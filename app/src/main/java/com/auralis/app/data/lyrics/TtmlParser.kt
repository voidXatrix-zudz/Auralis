package com.auralis.app.data.lyrics

/**
 * Converts Apple-style word-timed TTML (as served by lrc.red / BiniLyrics and the BetterLyrics API)
 * into enhanced LRC, so it flows through the existing [com.auralis.app.domain.lyrics.LrcParser] and
 * the lyrics screens need no changes.
 *
 * Each `<p>` becomes a `[mm:ss.mmm]` line; its timed `<span>` children become inline `<mm:ss.mmm>`
 * word tags. Malformed or lyric-less input yields `null` and never throws.
 */
internal object TtmlParser {

    private val pBlock = Regex("(?s)<p\\b([^>]*)>(.*?)</p>", RegexOption.IGNORE_CASE)
    private val spanBlock = Regex("(?s)<span\\b([^>]*)>(.*?)</span>", RegexOption.IGNORE_CASE)
    private val beginAttr = Regex("\\bbegin\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")
    private val whitespace = Regex("\\s+")

    /** Enhanced-LRC text, or null when [ttml] is blank or carries no timed lines. */
    fun parse(ttml: String?): String? {
        if (ttml.isNullOrBlank()) return null
        val lines = mutableListOf<String>()
        for (match in pBlock.findAll(ttml)) {
            val lineStart = timeOf(beginAttr.find(match.groupValues[1])?.groupValues?.get(1)) ?: continue
            val inner = match.groupValues[2]
            val words = spanBlock.findAll(inner).mapNotNull { span ->
                val start = timeOf(beginAttr.find(span.groupValues[1])?.groupValues?.get(1))
                    ?: return@mapNotNull null
                val text = decode(span.groupValues[2]).trim()
                if (text.isEmpty()) null else start to text
            }.toList()
            val text = plain(inner)
            lines += when {
                text.isEmpty() -> "[${lrcTimestamp(lineStart)}]"
                words.isNotEmpty() -> buildString {
                    append('[').append(lrcTimestamp(lineStart)).append(']')
                    words.forEach { (time, word) ->
                        append('<').append(lrcTimestamp(time)).append('>').append(word).append(' ')
                    }
                }.trimEnd()
                else -> "[${lrcTimestamp(lineStart)}]$text"
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Text of a `<p>`/`<span>` with its tags removed and XML entities decoded. */
    private fun plain(inner: String): String = decode(inner.replace(anyTag, "")).replace(whitespace, " ").trim()

    /** `ss.mmm`, `mm:ss.mmm` or `hh:mm:ss.mmm` to milliseconds. */
    private fun timeOf(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(':')
        return runCatching {
            when (parts.size) {
                1 -> seconds(parts[0])
                2 -> parts[0].toLong() * 60_000L + seconds(parts[1])
                3 -> parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L + seconds(parts[2])
                else -> null
            }
        }.getOrNull()
    }

    private fun seconds(value: String): Long {
        val dot = value.indexOf('.')
        if (dot < 0) return value.toLong() * 1_000L
        val whole = value.substring(0, dot).toLongOrNull() ?: return 0L
        val fraction = value.substring(dot + 1).padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return whole * 1_000L + fraction
    }

    private fun decode(value: String): String {
        if ('&' !in value) return value
        return buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val amp = value.indexOf('&', index)
                if (amp < 0) {
                    append(value, index, value.length)
                    break
                }
                append(value, index, amp)
                val semi = value.indexOf(';', amp)
                val entity = if (semi in (amp + 1)..(amp + 10)) value.substring(amp + 1, semi) else null
                val decoded = when {
                    entity == null -> null
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let { code -> String(Character.toChars(code)) }
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let { code -> String(Character.toChars(code)) }
                    else -> null
                }
                if (decoded == null) {
                    append(value, amp, (semi.takeIf { it >= 0 }?.plus(1)) ?: (amp + 1))
                    index = if (semi >= 0) semi + 1 else amp + 1
                } else {
                    append(decoded)
                    index = semi + 1
                }
            }
        }
    }
}

/** Milliseconds to an LRC `mm:ss.mmm` timestamp, shared by the TTML and LyricsPlus conversions. */
internal fun lrcTimestamp(ms: Long): String {
    val value = ms.coerceAtLeast(0L)
    return "%02d:%02d.%03d".format(value / 60_000, (value % 60_000) / 1_000, value % 1_000)
}
