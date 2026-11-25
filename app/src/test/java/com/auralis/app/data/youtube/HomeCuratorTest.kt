package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home curator is the difference between "the account's music" and YouTube's whole page: it
 * removes non-music shelves and teasers, drops unusable cards, dedupes across shelves, caps length
 * and never reorders what YouTube returned.
 */
class HomeCuratorTest {

    private fun card(id: String, art: String? = "https://art/$id") = YtCard(
        title = "Song $id",
        subtitle = "Artist",
        thumbnailUrl = art,
        videoId = id,
    )

    private fun shelf(title: String, ids: List<String>) =
        YtShelf(title = title, items = ids.map { card(it) })

    @Test
    fun `drops non-music shelves`() {
        val out = curateHomeShelves(
            listOf(
                shelf("Top picks for you", listOf("a", "b", "c")),
                shelf("Podcasts for you", listOf("p1", "p2", "p3")),
                shelf("Shorts", listOf("s1", "s2", "s3")),
                shelf("Explore charts", listOf("x1", "x2", "x3")),
                shelf("Listen again", listOf("l1", "l2", "l3")),
            ),
        )
        assertEquals(listOf("Top picks for you", "Listen again"), out.map { it.title })
    }

    @Test
    fun `drops teasers with fewer than three usable items`() {
        val out = curateHomeShelves(
            listOf(
                shelf("Too small", listOf("a", "b")),
                shelf("Big enough", listOf("c", "d", "e")),
            ),
        )
        assertEquals(listOf("Big enough"), out.map { it.title })
    }

    @Test
    fun `unusable cards do not count toward the minimum`() {
        val out = curateHomeShelves(
            listOf(
                YtShelf(
                    title = "Mixed",
                    items = listOf(
                        card("ok1"),
                        card("ok2"),
                        card("ok3"),
                        card("noart", art = null).copy(title = "No art"),
                        YtCard(title = "", subtitle = "", thumbnailUrl = "https://art/x", videoId = "blank"),
                        YtCard(title = "No ids", subtitle = "", thumbnailUrl = "https://art/y"),
                    ),
                ),
            ),
        )
        assertEquals(3, out.single().items.size)
        assertEquals(listOf("ok1", "ok2", "ok3"), out.single().items.map { it.videoId })
    }

    @Test
    fun `keeps only the first copy of a song across shelves`() {
        val out = curateHomeShelves(
            listOf(
                shelf("Quick picks", listOf("dup", "a2", "a3")),
                shelf("Mixed for you", listOf("dup", "b2", "b3", "b4")),
            ),
        )
        assertEquals(listOf("dup", "a2", "a3"), out[0].items.map { it.videoId })
        assertEquals(listOf("b2", "b3", "b4"), out[1].items.map { it.videoId })
    }

    @Test
    fun `a shelf reduced below three items by dedupe disappears`() {
        val out = curateHomeShelves(
            listOf(
                shelf("Quick picks", listOf("dup", "a2", "a3")),
                shelf("Mixed for you", listOf("dup", "b2", "b3")),
            ),
        )
        assertEquals(listOf("Quick picks"), out.map { it.title })
    }

    @Test
    fun `caps each shelf and keeps YouTube order`() {
        val ids = (1..30).map { "s$it" }
        val out = curateHomeShelves(listOf(shelf("Long shelf", ids)))
        assertEquals(HOME_SHELF_ITEM_CAP, out.single().items.size)
        assertEquals(ids.take(HOME_SHELF_ITEM_CAP), out.single().items.map { it.videoId })
    }

    @Test
    fun `browse-only cards are usable (albums and playlists)`() {
        val out = curateHomeShelves(
            listOf(
                YtShelf(
                    title = "Albums for you",
                    items = (1..3).map {
                        YtCard(
                            title = "Album $it",
                            subtitle = "Artist",
                            thumbnailUrl = "https://art/album$it",
                            browseId = "MPREb$it",
                        )
                    },
                ),
            ),
        )
        assertEquals(3, out.single().items.size)
    }

    @Test
    fun `recents shelf is detected by its heading`() {
        assertTrue(looksLikeRecentsShelf(YtShelf("Listen again", emptyList())))
        assertTrue(looksLikeRecentsShelf(YtShelf("Recently played", emptyList())))
        assertFalse(looksLikeRecentsShelf(YtShelf("Quick picks", emptyList())))
    }

    @Test
    fun `blank shelves and blank titles are ignored`() {
        val out = curateHomeShelves(
            listOf(
                YtShelf("", listOf(card("a"), card("b"), card("c"))),
                YtShelf("Empty", emptyList()),
            ),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `diagnostics never log a personal shelf heading`() {
        assertEquals("listen again", safeShelfLabel("Listen again"))
        assertEquals("top picks", safeShelfLabel("Top picks for you"))
        assertEquals("other", safeShelfLabel("Because you listened to Some Artist"))
    }
}