package com.auralis.app.data.lyrics

import com.auralis.app.domain.model.Track
import java.text.Normalizer
import kotlin.math.abs

/** Conservative, deterministic checks applied to every remote response before it reaches the UI. */
internal object LyricsPrecision {
    private val noise = Regex("(?i)(\\[official\\s+(video|audio|music video)]|\\((remaster(ed)?|live|official video|feat\\.? [^)]*)[^)]*\\)|\\s[-–]\\s(live|remaster(ed)?).*$)")
    private val nonLyric = Regex("(?i)^(instrumental|no lyrics( found)?|lyrics provided by|paroles de|embed|advertisement|subscribe to).*$")
    private val tags = Regex("\\[[^]]*]")
    private val spaces = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value.lowercase().replace(noise, " "), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('_', ' ')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(spaces, " ").trim()

    fun artistNames(value: String): List<String> = value
        .replace(Regex("(?i)\\b(feat(uring)?|ft)\\.?\\s+"), ",")
        .replace(Regex("\\s+/\\s+"), ",")
        .split(',', '&', ';', '·', '|', '×', '+')
        .map { it.trim() }.filter(String::isNotBlank).distinct()

    data class Check(val accepted: Boolean, val score: Int, val reason: String)

    fun sanitize(raw: String): String {
        val decoded = raw.replace("&amp;", "&", true).replace("&quot;", "\"", true)
            .replace("&#39;", "'", true).replace("&apos;", "'", true)
            .replace("&lt;", "<", true).replace("&gt;", ">", true)
            .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value }
        val seen = HashSet<String>()
        return decoded.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
            .filterNot { line -> nonLyric.matches(normalize(tags.replace(line, "").trim())) }
            .filter { line ->
                val stamp = Regex("^\\[(\\d{1,3}:\\d{1,2}[.:]\\d{1,3})](.*)$").matchEntire(line.trim())
                if (stamp == null) true else seen.add("${stamp.groupValues[1]}|${normalize(stamp.groupValues[2])}")
            }
            .joinToString("\n").trim()
    }

    fun check(track: Track, raw: String): Check {
        val originalTextLines = raw.lineSequence().map { normalize(tags.replace(it, "").trim()) }
            .filter(String::isNotBlank).toList()
        if (originalTextLines.isNotEmpty() && originalTextLines.all(nonLyric::matches)) {
            return Check(false, 0, "boilerplate/instrumental")
        }
        val lines = sanitize(raw).replace("\r", "").lineSequence().map(String::trim)
            .filterNot { Regex("(?i)^\\[(ti|ar|al|length|offset):").containsMatchIn(it) }
            .toList()
        val lyricLines = lines
            .map { tags.replace(it, "").replace(Regex("<\\d{1,2}:\\d{2}(\\.\\d{1,3})?>"), "").trim() }
            .filter(String::isNotBlank)
        if (raw.isBlank() || lyricLines.isEmpty()) return Check(false, 0, "empty")
        val normalizedLines = lyricLines.map(::normalize).filter(String::isNotBlank)
        if (normalizedLines.any { nonLyric.matches(it) }) return Check(false, 0, "boilerplate/instrumental")
        if (normalizedLines.size < 4 || normalizedLines.joinToString(" ").length < 40) return Check(false, 0, "too short")

        val headers = Regex("(?im)^\\[(ti|ar|al|length):\\s*([^]]*)]").findAll(raw)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val title = headers["ti"]
        val artist = headers["ar"]
        val titleScore = title?.let { similarity(track.title, it) } ?: 0.78
        val wantedArtists = artistNames(track.artist).map(::normalize)
        val actualArtists = artist?.let(::artistNames)?.map(::normalize).orEmpty()
        val artistScore = when {
            wantedArtists.isEmpty() || actualArtists.isEmpty() -> 0.72
            wantedArtists.any { want -> actualArtists.any { got -> similarity(want, got) >= 0.72 } } -> 1.0
            else -> 0.0
        }
        if (titleScore < 0.72) return Check(false, 0, "title mismatch")
        if (artistScore == 0.0) return Check(false, 0, "artist mismatch")

        val requestedDuration = track.durationMs.takeIf { it > 0 }
        val declaredDuration = headers["length"]?.let(::durationMs)
        val lastTime = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]").findAll(raw)
            .mapNotNull { m ->
                val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                (m.groupValues[1].toLongOrNull() ?: return@mapNotNull null) * 60_000L +
                    (m.groupValues[2].toLongOrNull() ?: return@mapNotNull null) * 1_000L + fraction
            }.maxOrNull()
        if (requestedDuration != null && declaredDuration != null && abs(requestedDuration - declaredDuration) > 3_000L) {
            return Check(false, 0, "duration mismatch")
        }
        if (requestedDuration != null && lastTime != null && lastTime > requestedDuration + 3_000L) {
            return Check(false, 0, "timestamps exceed track duration")
        }
        val durationScore = if (requestedDuration == null || declaredDuration == null) 0.72
            else when (abs(requestedDuration - declaredDuration)) { in 0..1_000 -> 1.0; else -> 0.75 }
        val score = ((titleScore * 50) + (artistScore * 35) + (durationScore * 10) + 5).toInt().coerceIn(0, 100)
        return Check(score >= MIN_SCORE, score, if (score >= MIN_SCORE) "accepted" else "below threshold")
    }

    fun similarity(a: String, b: String): Double {
        val x = normalize(a).split(' ').filter(String::isNotBlank).toSet()
        val y = normalize(b).split(' ').filter(String::isNotBlank).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    fun lyricSimilarity(a: String, b: String): Double {
        fun lines(raw: String) = raw.lineSequence()
            .filterNot { Regex("(?i)^\\[(ti|ar|al|length|offset):").containsMatchIn(it.trim()) }
            .map { normalize(tags.replace(it, "").replace(Regex("<[^>]+>"), "")) }
            .filter { it.length >= 3 }
            .toSet()
        val left = lines(a)
        val right = lines(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val common = left.count { x -> right.any { y -> similarity(x, y) >= 0.78 } }
        return 2.0 * common / (left.size + right.size)
    }

    private fun durationMs(raw: String): Long? = runCatching {
        val parts = raw.split(':')
        when (parts.size) {
            2 -> (parts[0].toLong() * 60_000L) + (parts[1].toDouble() * 1_000).toLong()
            3 -> (parts[0].toLong() * 3_600_000L) + (parts[1].toLong() * 60_000L) + (parts[2].toDouble() * 1_000).toLong()
            else -> null
        }
    }.getOrNull()

    private const val MIN_SCORE = 72
}
