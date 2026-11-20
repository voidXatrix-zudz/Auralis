package com.auralis.app.data.youtube

/**
 * How the Home UI renders one row.
 *
 * YouTube's `FEmusic_home` mixes playable songs, music videos, albums, playlists, mixes and artists
 * in the same shelf. The app renders each kind with the shape that fits it instead of turning every
 * shelf into an identical card row (the old behaviour in [com.auralis.app.ui.AppViewModel]).
 */
enum class HomeRowStyle {
    /** Fallback used by the anonymous/curated rows: the renderer decides per card. */
    AUTO,

    /** Horizontally paged song row (Recents, Trending); cards become playable [Track]s. */
    TRACKS,

    /** Large discovery cards for Top Picks. */
    LARGE_CARDS,

    /** Square album/playlist/mix cards. */
    SQUARE_TILES,

    /** 16:9 music-video cards. */
    WIDE_CARDS,

    /** Circular artist cards. */
    CIRCLE_CARDS,
}

/** One renderable slice of a YouTube shelf. A mixed shelf can produce more than one block. */
data class HomeShelfBlock(
    val title: String,
    val style: HomeRowStyle,
    val cards: List<YtCard>,
    /** Continuation belongs to the primary (first) block only, so pages are not fetched twice. */
    val continuation: String? = null,
)

/** The three fixed sections, plus the remaining shelves in YouTube's own order. */
data class SignedInHomeLayout(
    val topPicks: HomeShelfBlock? = null,
    val moods: HomeShelfBlock? = null,
    val shelves: List<HomeShelfBlock> = emptyList(),
)

/** A minority group of at least this many items earns its own row with the shelf's title. */
const val MIN_SPLIT_ROW_ITEMS = 3

/** How many albums/mixes the "Top Picks for You" card row shows. */
const val TOP_PICKS_LIMIT = 6

/** How many mood tiles "Find Your Mood" shows. */
const val MOOD_TILE_LIMIT = 12

/**
 * Only a shelf *heading* about moods/genres counts. A shelf merely titled "Chill" or "Energy" is
 * usually a normal playlist/song shelf and must keep its own row instead of being flattened into
 * the mood tiles.
 */
private val MOOD_TITLE = Regex("\\bmoods?\\b|\\bgenres?\\b")
private val RELEASE_WORDS = listOf("single", "album", "ep", "mix", "playlist")
private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

/**
 * The shape one card should take. A card with a [YtCard.videoId] is playable; a browse-only card
 * navigates to a collection or artist.
 */
fun cardRowStyle(card: YtCard): HomeRowStyle = when {
    card.type == YtItemType.ARTIST || card.browseId?.startsWith("UC") == true -> HomeRowStyle.CIRCLE_CARDS
    card.type == YtItemType.VIDEO -> HomeRowStyle.WIDE_CARDS
    card.videoId != null -> HomeRowStyle.TRACKS
    else -> HomeRowStyle.SQUARE_TILES
}

/** True when [card] belongs in a row rendered with [style]. */
fun cardMatchesStyle(card: YtCard, style: HomeRowStyle): Boolean = when (style) {
    HomeRowStyle.AUTO -> true
    HomeRowStyle.TRACKS -> cardRowStyle(card) == HomeRowStyle.TRACKS
    HomeRowStyle.LARGE_CARDS -> cardRowStyle(card) == HomeRowStyle.SQUARE_TILES
    else -> cardRowStyle(card) == style
}

/**
 * Splits one shelf into typed rows:
 *
 * - a majority of songs -> [HomeRowStyle.TRACKS]; albums/singles/mixes -> square tiles; music
 *   videos -> wide cards; artists -> circular cards;
 * - every other group with at least [MIN_SPLIT_ROW_ITEMS] items becomes a second row with the same
 *   title;
 * - songs-only filtering happens before classification, and an empty shelf disappears entirely.
 */
fun classifyShelf(shelf: YtShelf, strict: Boolean = false): List<HomeShelfBlock> {
    val cards = shelf.items.filter { isMusicCard(it, strict) }
    if (cards.isEmpty()) return emptyList()

    // Order is the tie-breaker: songs win over albums, then videos, then artists.
    val groups = listOf(
        HomeRowStyle.TRACKS to cards.filter { cardRowStyle(it) == HomeRowStyle.TRACKS },
        HomeRowStyle.SQUARE_TILES to cards.filter { cardRowStyle(it) == HomeRowStyle.SQUARE_TILES },
        HomeRowStyle.WIDE_CARDS to cards.filter { cardRowStyle(it) == HomeRowStyle.WIDE_CARDS },
        HomeRowStyle.CIRCLE_CARDS to cards.filter { cardRowStyle(it) == HomeRowStyle.CIRCLE_CARDS },
    ).filter { it.second.isNotEmpty() }
    if (groups.isEmpty()) return emptyList()

    val primary = groups.first { it.second.size == groups.maxOf { group -> group.second.size } }
    val blocks = ArrayList<HomeShelfBlock>(groups.size)
    blocks += HomeShelfBlock(
        title = shelf.title,
        style = primary.first,
        cards = primary.second,
        continuation = shelf.continuation,
    )
    groups.filter { it.first != primary.first && it.second.size >= MIN_SPLIT_ROW_ITEMS }
        .forEach { (style, items) ->
            blocks += HomeShelfBlock(title = shelf.title, style = style, cards = items)
        }
    return blocks
}

/**
 * Builds the signed-in For You layout: Top Picks (from the first albums/singles/mixes shelf),
 * Recents is added by the caller from `FEmusic_history`, Find Your Mood, then the remaining shelves
 * in YouTube's order.
 *
 * [fallbackMoods] is used when YouTube returned no mood/genre shelf, so the section keeps the tiles
 * the anonymous Home already shows.
 */
fun buildSignedInLayout(
    shelves: List<YtShelf>,
    strict: Boolean = false,
    fallbackMoods: List<YtCard> = emptyList(),
): SignedInHomeLayout {
    // Keep each shelf's own block order; a shelf can contribute several rows.
    val perShelf = shelves.map { shelf -> shelf to classifyShelf(shelf, strict).toMutableList() }
        .filter { it.second.isNotEmpty() }
        .toMutableList()

    // 1. Find Your Mood: mood/genre shelves first, then the caller's existing tiles.
    val moodCards = LinkedHashMap<String, YtCard>()
    val moodShelves = perShelf.filter { (shelf, _) -> looksLikeMoodShelf(shelf) }.toSet()
    for ((shelf, blocks) in moodShelves) {
        blocks.asSequence()
            .flatMap { it.cards.asSequence() }
            .filter { it.type == YtItemType.MOOD || it.type == YtItemType.PLAYLIST || it.type == YtItemType.ALBUM }
            .forEach { moodCards.putIfAbsent(it.videoId ?: it.browseId ?: it.title, it) }
        if (moodCards.isEmpty()) {
            // Some accounts return a mood shelf with plain song cards; keep those tiles instead of
            // dropping the section.
            blocks.asSequence().flatMap { it.cards.asSequence() }
                .forEach { moodCards.putIfAbsent(it.videoId ?: it.browseId ?: it.title, it) }
        }
    }
    val moodShelfValues = moodShelves.map { it.first }.toSet()
    perShelf.removeAll { (shelf, _) -> shelf in moodShelfValues }
    if (moodCards.isEmpty()) {
        fallbackMoods.forEach { moodCards.putIfAbsent(it.videoId ?: it.browseId ?: it.title, it) }
    }
    val moods = moodCards.values.take(MOOD_TILE_LIMIT).takeIf { it.isNotEmpty() }?.let {
        HomeShelfBlock("Find Your Mood", HomeRowStyle.SQUARE_TILES, it)
    }

    // 2. Top Picks: the first shelf that contains albums/singles/mixes (or an immersive carousel).
    var topPicks: HomeShelfBlock? = null
    for ((_, blocks) in perShelf) {
        val index = blocks.indexOfFirst {
            it.style == HomeRowStyle.SQUARE_TILES &&
                it.cards.any { card -> card.type == YtItemType.ALBUM || card.type == YtItemType.PLAYLIST }
        }
        if (index >= 0) {
            val block = blocks.removeAt(index)
            topPicks = HomeShelfBlock(
                title = "Top Picks for You",
                style = HomeRowStyle.LARGE_CARDS,
                cards = block.cards.distinctBy { it.videoId ?: it.browseId ?: it.title }
                    .take(TOP_PICKS_LIMIT),
                continuation = block.continuation,
            )
            break
        }
    }

    // 3. Everything else stays in YouTube's order.
    val remaining = perShelf.flatMap { (_, blocks) -> blocks }
    return SignedInHomeLayout(topPicks = topPicks, moods = moods, shelves = remaining)
}

/** True for a shelf the account returned under a mood/genre heading. */
fun looksLikeMoodShelf(shelf: YtShelf): Boolean =
    MOOD_TITLE.containsMatchIn(shelf.title.lowercase()) ||
        shelf.items.any { it.type == YtItemType.MOOD }

/**
 * Labels for the big "Top Picks" card: a small eyebrow ("New from <artist>"), the title and a
 * release line ("Single - 2024") parsed from YouTube's `Artist • Single • 2024` subtitle.
 */
data class CardDisplay(val eyebrow: String, val title: String, val detail: String)

fun cardDisplay(card: YtCard): CardDisplay {
    val parts = card.subtitle.split('•', '·')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val release = parts.firstOrNull { part -> RELEASE_WORDS.any { part.contains(it, ignoreCase = true) } }
    val year = parts.firstOrNull { YEAR.containsMatchIn(it) }
    val artist = parts.firstOrNull { it != release && it != year && !it.contains("mix", ignoreCase = true) }
    val eyebrow = when {
        card.type == YtItemType.ARTIST -> "Featuring ${card.title}"
        !artist.isNullOrBlank() -> "New from $artist"
        else -> ""
    }
    val detail = listOfNotNull(release, year).joinToString(" - ")
    return CardDisplay(
        eyebrow = eyebrow,
        title = card.title,
        detail = detail.ifBlank { card.subtitle },
    )
}