package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8: YouTube's mixed `FEmusic_home` shelves become typed Home rows — songs -> track rows,
 * albums/playlists/mixes -> square cards, music videos -> wide cards, artists -> circular cards;
 * a mixed shelf splits off a minority of three or more; empty shelves disappear.
 */
class HomeShelfLayoutTest {

    private fun song(id: String, type: String = YtItemType.SONG.wire) = YtCard(
        title = "Song $id",
        subtitle = "Artist $id • 2024",
        thumbnailUrl = null,
        videoId = id,
        itemType = type,
    )

    private fun album(id: String) = YtCard(
        title = "Album $id",
        subtitle = "Artist $id • Album • 2024",
        thumbnailUrl = null,
        browseId = "MPREb$id",
        itemType = YtItemType.ALBUM.wire,
    )

    private fun mood(id: String) = YtCard(
        title = "Mood $id",
        subtitle = "Playlist",
        thumbnailUrl = null,
        browseId = "VL$id",
        itemType = YtItemType.MOOD.wire,
    )

    private fun artist(id: String) = YtCard(
        title = "Artist $id",
        subtitle = "1M subscribers",
        thumbnailUrl = null,
        browseId = "UC$id",
        itemType = YtItemType.ARTIST.wire,
    )

    private fun shelf(title: String, items: List<YtCard>, continuation: String? = null) =
        YtShelf(title = title, items = items, continuation = continuation)

    @Test
    fun `a shelf of songs becomes one track row`() {
        val blocks = classifyShelf(shelf("Recents", (1..5).map { song("$it") }, "next"))

        assertEquals(1, blocks.size)
        assertEquals(HomeRowStyle.TRACKS, blocks[0].style)
        assertEquals(5, blocks[0].cards.size)
        assertEquals("next", blocks[0].continuation)
    }

    @Test
    fun `albums become square cards`() {
        val blocks = classifyShelf(shelf("New releases", (1..4).map { album("$it") }))

        assertEquals(HomeRowStyle.SQUARE_TILES, blocks.single().style)
    }

    @Test
    fun `music videos become wide cards`() {
        val blocks = classifyShelf(shelf("Videos", (1..4).map { song("$it", YtItemType.VIDEO.wire) }))

        assertEquals(HomeRowStyle.WIDE_CARDS, blocks.single().style)
    }

    @Test
    fun `artists become circular cards`() {
        val blocks = classifyShelf(shelf("Popular artists", (1..4).map { artist("$it") }))

        assertEquals(HomeRowStyle.CIRCLE_CARDS, blocks.single().style)
    }

    @Test
    fun `a mixed shelf splits a minority of three into a second row`() {
        val items = (1..5).map { song("$it") } + (1..3).map { album("$it") }

        val blocks = classifyShelf(shelf("Mixed for you", items, "more"))

        assertEquals(2, blocks.size)
        assertEquals(HomeRowStyle.TRACKS, blocks[0].style)
        assertEquals(5, blocks[0].cards.size)
        assertEquals("more", blocks[0].continuation)
        assertEquals(HomeRowStyle.SQUARE_TILES, blocks[1].style)
        assertEquals(3, blocks[1].cards.size)
        // Continuation belongs to one row only, so the page is never fetched twice.
        assertNull(blocks[1].continuation)
        assertEquals(blocks[0].title, blocks[1].title)
    }

    @Test
    fun `a minority smaller than three stays inside the majority row`() {
        val items = (1..5).map { song("$it") } + (1..2).map { album("$it") }

        val blocks = classifyShelf(shelf("Mixed for you", items))

        assertEquals(1, blocks.size)
        assertEquals(HomeRowStyle.TRACKS, blocks[0].style)
        assertEquals(5, blocks[0].cards.size)
    }

    @Test
    fun `empty and non-song-only shelves are hidden`() {
        assertTrue(classifyShelf(shelf("Empty", emptyList())).isEmpty())
        // UGC is filtered by the shared songs-only rule, so the shelf disappears.
        val podcast = YtCard(
            title = "Podcast",
            subtitle = "Uploader",
            thumbnailUrl = null,
            videoId = "ugc",
            itemType = YtItemType.SONG.wire,
            musicVideoType = MUSIC_VIDEO_TYPE_UGC,
        )
        assertTrue(classifyShelf(shelf("Uploads", listOf(podcast))).isEmpty())
    }

    @Test
    fun `layout is Top Picks, moods, then YouTube order`() {
        val shelves = listOf(
            shelf("Chill", (1..4).map { song("c$it") }),
            shelf("New releases", (1..8).map { album("$it") }),
            shelf("Find your mood", (1..3).map { mood("$it") }),
            shelf("Popular artists", (1..3).map { artist("$it") }),
        )

        val layout = buildSignedInLayout(shelves)

        assertEquals("Top Picks for You", layout.topPicks!!.title)
        assertEquals(HomeRowStyle.LARGE_CARDS, layout.topPicks!!.style)
        assertEquals(TOP_PICKS_LIMIT, layout.topPicks!!.cards.size)
        assertEquals("Find Your Mood", layout.moods!!.title)
        assertEquals(HomeRowStyle.SQUARE_TILES, layout.moods!!.style)
        assertEquals(listOf("Chill", "Popular artists"), layout.shelves.map { it.title })
        assertEquals(listOf(HomeRowStyle.TRACKS, HomeRowStyle.CIRCLE_CARDS), layout.shelves.map { it.style })
    }

    @Test
    fun `the top picks shelf is removed from the remaining shelves`() {
        val shelves = listOf(
            shelf("Albums for you", (1..3).map { album("$it") }),
            shelf("Chill", (1..3).map { song("c$it") }),
        )

        val layout = buildSignedInLayout(shelves)

        assertEquals(3, layout.topPicks!!.cards.size)
        assertEquals(listOf("Chill"), layout.shelves.map { it.title })
    }

    @Test
    fun `fallback tiles fill an empty Find Your Mood section`() {
        val fallback = (1..3).map { mood("f$it") }
        val layout = buildSignedInLayout(
            shelves = listOf(shelf("Chill", (1..3).map { song("c$it") })),
            fallbackMoods = fallback,
        )

        assertEquals(3, layout.moods!!.cards.size)
        assertNull(layout.topPicks)
    }

    @Test
    fun `big card labels split artist, release type and year`() {
        val display = cardDisplay(album("1"))

        assertEquals("New from Artist 1", display.eyebrow)
        assertEquals("Album 1", display.title)
        assertEquals("Album - 2024", display.detail)
    }

    @Test
    fun `card style recognises songs without a type`() {
        val unknownSong = YtCard("Song", "Artist", null, videoId = "v")
        val unknownCollection = YtCard("List", "Owner", null, browseId = "VLPL1")

        assertEquals(HomeRowStyle.TRACKS, cardRowStyle(unknownSong))
        assertEquals(HomeRowStyle.SQUARE_TILES, cardRowStyle(unknownCollection))
        assertFalse(cardRowStyle(unknownSong) == HomeRowStyle.AUTO)
    }
}