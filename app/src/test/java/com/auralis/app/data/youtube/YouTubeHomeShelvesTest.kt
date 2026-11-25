package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeCacheDao
import com.auralis.app.data.db.YouTubeCacheEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signed-in Home: shelves keep YouTube's order and titles, the continuation is followed, and a
 * refused request is a `Failure` the UI can retry - not an empty account.
 */
class YouTubeHomeShelvesTest {
    private class FakeCache : YouTubeCacheDao {
        val entries = LinkedHashMap<String, YouTubeCacheEntity>()
        override suspend fun put(entry: YouTubeCacheEntity) {
            entries[entry.cacheKey] = entry
        }

        override suspend fun get(key: String): YouTubeCacheEntity? = entries[key]
        override suspend fun clear() = entries.clear()
    }

    private fun runs(text: String) = buildJsonArray {
        add(buildJsonObject { put("text", text) })
    }

    private fun card(id: String, title: String): JsonObject = buildJsonObject {
        putJsonObject("musicTwoRowItemRenderer") {
            putJsonObject("title") { put("runs", runs(title)) }
            putJsonObject("subtitle") { put("runs", runs("Artist")) }
            putJsonObject("navigationEndpoint") {
                putJsonObject("watchEndpoint") { put("videoId", id) }
            }
        }
    }

    private fun shelf(title: String, cards: List<JsonObject>, continuation: String? = null): JsonObject =
        buildJsonObject {
            putJsonObject("musicCarouselShelfRenderer") {
                putJsonObject("header") {
                    putJsonObject("musicCarouselShelfBasicHeaderRenderer") {
                        putJsonObject("title") { put("runs", runs(title)) }
                    }
                }
                putJsonArray("contents") { cards.forEach { add(it) } }
                if (continuation != null) {
                    putJsonArray("continuations") {
                        add(buildJsonObject {
                            putJsonObject("nextContinuationData") { put("continuation", continuation) }
                        })
                    }
                }
            }
        }

    private fun page(vararg shelves: JsonObject): String = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("singleColumnBrowseResultsRenderer") {
                putJsonArray("tabs") {
                    add(buildJsonObject {
                        putJsonObject("tabRenderer") {
                            putJsonObject("content") {
                                putJsonObject("sectionListRenderer") {
                                    putJsonArray("contents") { shelves.forEach { add(it) } }
                                }
                            }
                        }
                    })
                }
            }
        }
    }.toString()

    @Test
    fun `shelves keep YouTube order and follow the page continuation`() = runBlocking {
        val first = page(
            shelf("Made for you", listOf(card("a", "A"), card("b", "B")), continuation = "token-1"),
            shelf("Mixed for you", listOf(card("c", "C"))),
        )
        val second = page(shelf("Forgotten favourites", listOf(card("d", "D"))))
        var calls = 0
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = FakeCache(),
            transport = { request ->
                calls++
                if (request.url.queryParameter("ctoken") == "token-1") InnerTubeResponse(200, second)
                else InnerTubeResponse(200, first)
            },
        )

        val result = repo.homeShelves(force = true)
        assertTrue(result is YouTubeLoadResult.Success)
        val shelves = (result as YouTubeLoadResult.Success).value
        assertEquals(
            listOf("Made for you", "Mixed for you", "Forgotten favourites"),
            shelves.map { it.title },
        )
        assertEquals(listOf("a", "b"), shelves[0].items.map { it.videoId })
        assertEquals(2, calls)
    }

    @Test
    fun `a refused request is a failure with a reason, not an empty success`() = runBlocking {
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = FakeCache(),
            transport = { InnerTubeResponse(500, "{}") },
        )
        val result = repo.homeShelves(force = true)
        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(500, (result as YouTubeLoadResult.Failure).code)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `signed-in and anonymous feeds never share a cache row`() {
        assertNotEquals(homeCacheKey(true), homeCacheKey(false))
        // Two different accounts must not read each other's Home or history either.
        assertNotEquals(homeCacheKey(true, "acctA"), homeCacheKey(true, "acctB"))
        assertNotEquals(homeCacheKey(true, "acctA"), homeCacheKey(true))
        assertNotEquals(
            playlistCacheKeyFor("VLPLabc", signedIn = true),
            playlistCacheKeyFor("VLPLabc", signedIn = false),
        )
        // The browse prefix is storage detail, not part of the key.
        assertEquals(
            playlistCacheKeyFor("PLabc", signedIn = false),
            playlistCacheKeyFor("VLPLabc", signedIn = false),
        )
    }
}