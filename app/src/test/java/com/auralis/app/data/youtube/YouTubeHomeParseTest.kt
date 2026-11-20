package com.auralis.app.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `FEmusic_home` parser against the real WEB_REMIX response shape:
 *
 * - a carousel shelf keeps its title in `header.musicCarouselShelfBasicHeaderRenderer.title.runs`;
 * - a song row is a `musicResponsiveListItemRenderer` whose id sits in `playlistItemData.videoId`
 *   and whose title/artist sit in `flexColumns[*]...text.runs`;
 * - a playlist card is a `musicTwoRowItemRenderer` whose id sits in
 *   `navigationEndpoint.browseEndpoint.browseId`.
 *
 * The fixture is a trimmed copy of a live anonymous home response, so a shape drift that would
 * silently empty the signed-in Home feed fails here instead of only on a phone.
 */
class YouTubeHomeParseTest {
    private val client = InnerTubeClient(OkHttpClient(), auth = { null })

    private fun fixture(): JsonObject {
        val body = checkNotNull(javaClass.getResourceAsStream("/youtube/home_anonymous.json")) {
            "Missing fixture /youtube/home_anonymous.json"
        }.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(body) as JsonObject
    }

    @Test
    fun `real carousel shelves keep their titles and cards`() {
        val shelves = client.parseHomeShelves(fixture())
        assertEquals(2, shelves.size)

        val quickPicks = shelves[0]
        assertEquals("Quick picks", quickPicks.title)
        assertEquals(3, quickPicks.items.size)
        val song = quickPicks.items[0]
        assertEquals("tdS3ijuqxW4", song.videoId)
        assertEquals("Todo Lo Fue", song.title)
        assertEquals("Lenin Ramirez", song.subtitle)
        assertNotNull(song.thumbnailUrl)

        val playlists = shelves[1]
        assertEquals("Romance Right Now", playlists.title)
        assertEquals(3, playlists.items.size)
        val playlist = playlists.items[0]
        assertEquals("VLRDCLAK5uy_miAacfMxVybbt7ketqqnPPbH9LDn1TavU", playlist.browseId)
        assertEquals(YtItemType.PLAYLIST.wire, playlist.itemType)
        assertNotNull(playlist.thumbnailUrl)
    }

    @Test
    fun `a signed-out landing page yields no shelves instead of throwing`() {
        // What Google answers when the cookie is not recognised: a messageRenderer, no shelves.
        val payload = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
            {"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
            {"messageRenderer":{"text":{"runs":[{"text":"Sign in"}]}}}]}}]}}}}]}}}
        """.trimIndent()
        val shelves = client.parseHomeShelves(Json.parseToJsonElement(payload) as JsonObject)
        assertTrue(shelves.isEmpty())
    }
}