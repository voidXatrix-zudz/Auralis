package com.auralis.app.ui.home

import com.auralis.app.data.youtube.HomeRowStyle
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.data.youtube.YtItemType
import com.auralis.app.data.youtube.cardDisplay
import com.auralis.app.data.youtube.toTrack
import com.auralis.app.domain.model.Track

/** One artwork-less-capable Top Pick card, built from a YouTube card or a local track. */
data class HomeTopPick(
    val key: String,
    val title: String,
    val caption: String,
    val subtitle: String,
    val imageUrl: String?,
    val track: Track? = null,
    val accentSeed: Int = key.hashCode(),
    val onClick: () -> Unit = {},
)

/** One square "From your library" tile with up to four mosaic covers. */
data class LibraryTileData(
    val key: String,
    val title: String,
    val caption: String,
    val covers: List<Track>,
    val coverUrl: String?,
    val onClick: () -> Unit,
)

/**
 * A renderable Home section after mapping. Named `HomeRowUi` because `com.auralis.app.ui.HomeRow`
 * (the view-model row) already exists and the two must not be confused.
 */
sealed interface HomeRowUi {
    val key: String
    val title: String
    val showAll: Boolean
    val hasMore: Boolean

    data class TopPicks(
        override val key: String,
        override val title: String,
        val picks: List<HomeTopPick>,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi

    data class PagedSongs(
        override val key: String,
        override val title: String,
        val tracks: List<Track>,
        /** Compact 16:9 rows for "Covers and remixes"-style shelves. */
        val compact: Boolean = false,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi

    data class LibraryTiles(
        override val key: String,
        override val title: String,
        val tiles: List<LibraryTileData>,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi

    data class Videos(
        override val key: String,
        override val title: String,
        val cards: List<YtCard>,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi

    data class SquareCards(
        override val key: String,
        override val title: String,
        val cards: List<YtCard>,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi

    data class Artists(
        override val key: String,
        override val title: String,
        val cards: List<YtCard>,
        override val showAll: Boolean = false,
        override val hasMore: Boolean = false,
    ) : HomeRowUi
}

/** Maps a view-model row to the presentation model, or null when the row should not be drawn. */
fun com.auralis.app.ui.HomeRow.toHomeRowUi(onOpenCard: (YtCard) -> Unit = {}): HomeRowUi? {
    if (title.isBlank()) return null
    val coverShelf = title.contains("cover", ignoreCase = true) || title.contains("remix", ignoreCase = true)

    if (tracks.isNotEmpty()) {
        if (tracks.size < 3) return null
        return HomeRowUi.PagedSongs(
            key = id,
            title = title,
            tracks = tracks,
            compact = coverShelf,
            showAll = showAll,
            hasMore = hasMore,
        )
    }
    if (cards.size < 3) return null

    return when (style) {
        HomeRowStyle.LARGE_CARDS -> HomeRowUi.TopPicks(
            key = id,
            title = title,
            picks = cards.map { card -> card.toHomeTopPick { onOpenCard(card) } },
            showAll = showAll,
            hasMore = hasMore,
        )

        HomeRowStyle.WIDE_CARDS -> HomeRowUi.Videos(id, title, cards, showAll, hasMore)
        HomeRowStyle.CIRCLE_CARDS -> HomeRowUi.Artists(id, title, cards, showAll, hasMore)
        HomeRowStyle.SQUARE_TILES -> HomeRowUi.SquareCards(id, title, cards, showAll, hasMore)

        HomeRowStyle.TRACKS -> {
            val converted = cards.map { it.toTrack() }.distinctBy { it.id }
            if (converted.size < 3) null
            else HomeRowUi.PagedSongs(id, title, converted, coverShelf, showAll, hasMore)
        }

        HomeRowStyle.AUTO -> when {
            cards.all { it.videoId != null } -> {
                val converted = cards.map { it.toTrack() }.distinctBy { it.id }
                if (converted.size < 3) null
                else HomeRowUi.PagedSongs(id, title, converted, coverShelf, showAll, hasMore)
            }
            cards.all { it.type == YtItemType.ARTIST || it.browseId?.startsWith("UC") == true } ->
                HomeRowUi.Artists(id, title, cards, showAll, hasMore)
            else -> HomeRowUi.SquareCards(id, title, cards, showAll, hasMore)
        }
    }
}

/** Section order: Top Picks, Recents, Quick Picks, then YouTube's own order (stable sort). */
fun orderHomeRows(rows: List<HomeRowUi>): List<HomeRowUi> = rows.sortedBy { homeRowPriority(it.title) }

fun homeRowPriority(title: String): Int = when {
    title.contains("top pick", ignoreCase = true) -> 0
    title.contains("recent", ignoreCase = true) || title.contains("listen again", ignoreCase = true) -> 1
    title.contains("quick pick", ignoreCase = true) -> 2
    else -> 3
}

/** Titles the local (integration-off) Home needs, resolved from `strings.xml` by the caller. */
data class LocalHomeLabels(
    val topPicks: String,
    val recents: String,
    val fromLibrary: String,
    val quickPicks: String,
)

/** Builds the local-library Home rows. Presentation only; no new data fetching. */
fun buildLocalHomeRows(
    tracks: List<Track>,
    libraryTiles: List<LibraryTileData>,
    labels: LocalHomeLabels,
    onPlay: (Track) -> Unit,
): List<HomeRowUi> {
    if (tracks.isEmpty()) return emptyList()
    val byRecent = tracks.sortedByDescending { it.addedAt }
    val rows = ArrayList<HomeRowUi>(4)
    if (tracks.size >= 3) {
        rows += HomeRowUi.TopPicks(
            key = "local:top",
            title = labels.topPicks,
            picks = byRecent.take(6).map { track -> track.toHomeTopPick { onPlay(track) } },
        )
    }
    if (tracks.size >= 3) {
        rows += HomeRowUi.PagedSongs("local:recents", labels.recents, byRecent.take(12))
    }
    if (libraryTiles.size >= 3) {
        rows += HomeRowUi.LibraryTiles("local:library", labels.fromLibrary, libraryTiles)
    }
    // Always keep a browsing row for very small libraries, where the 3-item minimum hides every
    // other section and Home would otherwise look empty despite having songs.
    if (tracks.isNotEmpty()) {
        rows += HomeRowUi.PagedSongs("local:quick", labels.quickPicks, tracks.take(20))
    }
    return rows
}

fun YtCard.toHomeTopPick(onClick: () -> Unit = {}): HomeTopPick {
    val display = cardDisplay(this)
    return HomeTopPick(
        key = videoId ?: browseId ?: title,
        title = display.title,
        caption = display.eyebrow,
        subtitle = display.detail,
        imageUrl = thumbnailUrl,
        onClick = onClick,
    )
}

fun Track.toHomeTopPick(onClick: () -> Unit = {}): HomeTopPick = HomeTopPick(
    key = id,
    title = title.ifBlank { displayName },
    caption = "Made for you",
    subtitle = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "),
    imageUrl = artworkUrl,
    track = this,
    onClick = onClick,
)