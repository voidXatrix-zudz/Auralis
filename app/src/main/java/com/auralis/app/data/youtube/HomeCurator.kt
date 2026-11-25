package com.auralis.app.data.youtube

/**
 * Cleans up the raw `FEmusic_home` shelves before the Home UI lays them out.
 *
 * YouTube returns the user's personalized music shelves, but the same page also carries filler: a
 * two-item teaser, podcast/video shelves, and the same song repeated across "Quick picks" and a mix.
 * Home should read as "my music", so this pass
 *
 *  - drops shelves that are not music (podcasts, shorts, episodes, ...),
 *  - drops shelves with fewer than [MIN_HOME_SHELF_ITEMS] usable cards,
 *  - drops cards with no title, no artwork or no id,
 *  - keeps only the FIRST copy of a song across every shelf (no repeated recommendations),
 *  - caps each shelf at [HOME_SHELF_ITEM_CAP] cards.
 *
 * It is pure and network-free so it can be unit-tested with plain [YtShelf] values.
 */

/** A shelf this small is a teaser, not a section; YouTube returns a few of them. */
const val MIN_HOME_SHELF_ITEMS = 3

/** Hard cap so a single long shelf cannot dominate Home. */
const val HOME_SHELF_ITEM_CAP = 20

/** Headings that are never the user's music. Kept conservative so a real shelf is never lost. */
private val NON_MUSIC_SHELF = Regex(
    "podcast|episode|shorts|\\bnews\\b|sports|audiobook|audiobooks|trailer|\\bexplore\\b|\\bcharts?\\b",
)

/** Headings YouTube uses for the "your recent listening" shelf. */
private val RECENTS_SHELF = Regex("listen again|recent|recently played")

/** True when [shelf] is the account's own "Listen again"/recents carousel. */
fun looksLikeRecentsShelf(shelf: YtShelf): Boolean = RECENTS_SHELF.containsMatchIn(shelf.title.lowercase())

/**
 * A privacy-safe name for a shelf in diagnostics: a known section label, or "other" when the
 * heading could carry a personal detail (YouTube uses "Because you listened to <artist>").
 * [AppLog] promises never to store artist names, so raw headings never reach the log.
 */
fun safeShelfLabel(title: String): String {
    val lower = title.lowercase()
    return KNOWN_SHELF_LABELS.firstOrNull { lower.contains(it) } ?: "other"
}

private val KNOWN_SHELF_LABELS = listOf(
    "top picks", "mixed for you", "listen again", "recently played", "quick picks",
    "find your mood", "mood", "albums for you", "singles for you", "from your library",
    "trending", "new music videos", "covers and remixes", "forgotten favorites",
    "daily discover", "from the community", "recommended", "made for you",
)

/**
 * Filters and dedupes [shelves] into what Home should render, in the order YouTube returned them.
 * Never throws: a malformed card is simply skipped.
 */
fun curateHomeShelves(shelves: List<YtShelf>): List<YtShelf> {
    val seen = HashSet<String>()
    val out = ArrayList<YtShelf>(shelves.size)
    for (shelf in shelves) {
        if (shelf.title.isBlank()) continue
        if (NON_MUSIC_SHELF.containsMatchIn(shelf.title.lowercase())) continue
        val items = ArrayList<YtCard>(shelf.items.size)
        for (card in shelf.items) {
            if (card.title.isBlank()) continue
            if (card.thumbnailUrl.isNullOrBlank()) continue
            if (card.videoId.isNullOrBlank() && card.browseId.isNullOrBlank()) continue
            val key = card.videoId ?: card.browseId ?: card.title
            if (!seen.add(key)) continue
            items += card
            if (items.size >= HOME_SHELF_ITEM_CAP) break
        }
        if (items.size < MIN_HOME_SHELF_ITEMS) continue
        out += shelf.copy(items = items)
    }
    return out
}
