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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist detail loading: the whole list is followed across continuations, a refusal is a
 * `Failure` the screen can retry, and an empty-but-successful page is an empty list (not an error).
 */
class YouTubePlaylistSongsTest {
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

    private fun column(text: String) = buildJsonObject {
        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
            putJsonObject("text") { put("runs", runs(text)) }
        }
    }

    private fun row(id: String, title: String, type: String): JsonObject = buildJsonObject {
        putJsonObject("musicResponsiveListItemRenderer") {
            putJsonObject("playlistItemData") { put("videoId", id) }
            putJsonArray("flexColumns") {
                add(column(title))
                add(column("Artist • 3:00"))
            }
            putJsonObject("watchEndpointMusicSupportedConfigs") {
                putJsonObject("watchEndpointMusicConfig") { put("musicVideoType", type) }
            }
        }
    }

    private fun page(rows: List<JsonObject>, continuation: String? = null): String = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("singleColumnBrowseResultsRenderer") {
                putJsonArray("tabs") {
                    add(buildJsonObject {
                        putJsonObject("tabRenderer") {
                            putJsonObject("content") {
                                putJsonObject("sectionListRenderer") {
                                    putJsonArray("contents") {
                                        add(buildJsonObject {
                                            putJsonObject("musicShelfRenderer") {
                                                putJsonArray("contents") { rows.forEach { add(it) } }
                                                if (continuation != null) {
                                                    putJsonArray("continuations") {
                                                        add(buildJsonObject {
                                                            putJsonObject("nextContinuationData") {
                                                                put("continuation", continuation)
                                                            }
                                                        })
                                                    }
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }
    }.toString()

    private fun repo(vararg pages: Pair<String?, String>): YouTubeRepository {
        val byToken = pages.toMap()
        return YouTubeRepository(
            OkHttpClient(),
            cache = FakeCache(),
            transport = { request ->
                val token = request.url.queryParameter("ctoken")
                InnerTubeResponse(200, byToken[token] ?: byToken[null].orEmpty())
            },
        )
    }

    @Test
    fun `every playlist page is followed in order and non-songs are hidden`() = runBlocking {
        val first = page(
            listOf(row("a", "One", MUSIC_VIDEO_TYPE_ATV), row("ugc", "Upload", MUSIC_VIDEO_TYPE_UGC)),
            continuation = "page-2",
        )
        val second = page(listOf(row("b", "Two", MUSIC_VIDEO_TYPE_ATV)))
        val result = repo(null to first, "page-2" to second).playlistSongs("PLxyz", force = true)

        assertTrue(result is YouTubeLoadResult.Success)
        val songs = (result as YouTubeLoadResult.Success).value
        assertEquals(listOf("a", "b"), songs.map { it.videoId })
    }

    @Test
    fun `the browse id is normalized to one VL prefix`() = runBlocking {
        var sentBody = ""
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = FakeCache(),
            transport = { request ->
                sentBody = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                InnerTubeResponse(200, page(listOf(row("a", "One", MUSIC_VIDEO_TYPE_ATV))))
            },
        )
        repo.playlistSongs("PLabc", force = true)
        assertTrue(sentBody.contains("\"browseId\":\"VLPLabc\""))
        assertTrue(!sentBody.contains("VLVLPLabc"))
    }

    @Test
    fun `an empty successful playlist is an empty success`() = runBlocking {
        val result = repo(null to page(emptyList())).playlistSongs("PLempty", force = true)
        assertTrue(result is YouTubeLoadResult.Success)
        assertTrue((result as YouTubeLoadResult.Success).value.isEmpty())
    }

    @Test
    fun `a refused playlist is a failure the screen can retry`() = runBlocking {
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = FakeCache(),
            transport = { InnerTubeResponse(500, "{}") },
        )
        val result = repo.playlistSongs("PLfail", force = true)
        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(500, (result as YouTubeLoadResult.Failure).code)
    }
}