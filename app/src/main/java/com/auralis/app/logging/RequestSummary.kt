package com.auralis.app.logging

/**
 * Counts thumbnail outcomes for one request. A valid URL produces **no** line: the caller logs one
 * summary per request instead of one line per song. Only failures are returned for logging.
 */
class ThumbnailTally {
    var total: Int = 0
        private set
    var ok: Int = 0
        private set
    var failed: Int = 0
        private set

    /** Records one item; returns a failure line, or null when the thumbnail was usable. */
    fun add(label: String, videoId: String, url: String?): String? {
        total++
        val reason = when {
            url == null -> "null"
            url.isBlank() -> "empty"
            else -> null
        }
        if (reason == null) {
            ok++
            return null
        }
        failed++
        return "$label: thumbnail missing for [$videoId] ($reason)"
    }
}

/**
 * The single line logged per search/radio request, e.g.
 * `Radio: 50 parsed, 2 hidden, thumbnails OK 48/50` or `Search: 20 songs, thumbnails OK 20/20`.
 */
fun requestSummary(
    label: String,
    tally: ThumbnailTally,
    noun: String = "songs",
    hidden: Int = 0,
): String {
    val head = if (noun == "parsed") {
        "$label: ${tally.total} parsed"
    } else {
        "$label: ${tally.total} $noun"
    }
    val hiddenPart = if (hidden > 0) ", $hidden hidden" else ""
    val failedPart = if (tally.failed > 0) ", ${tally.failed} failed" else ""
    return "$head$hiddenPart, thumbnails OK ${tally.ok}/${tally.total}$failedPart"
}