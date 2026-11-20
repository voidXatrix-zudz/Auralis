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

/** A VLLM reply containing a catalogue track, a music video and a UGC upload. */
class LikedSongsFilterTest {
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
                add(column("Artist • 3:20"))
            }
            putJsonObject("watchEndpointMusicSupportedConfigs") {
                putJsonObject("watchEndpointMusicConfig") { put("musicVideoType", type) }
            }
        }
    }

    private val body: String = buildJsonObject {
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
                                                putJsonArray("contents") {
                                                    add(row("atv", "Catalogue", MUSIC_VIDEO_TYPE_ATV))
                                                    add(row("omv", "Official video", MUSIC_VIDEO_TYPE_OMV))
                                                    add(row("ugc", "User upload", MUSIC_VIDEO_TYPE_UGC))
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

    private fun repo(): YouTubeRepository = YouTubeRepository(
        OkHttpClient(),
        cache = FakeCache(),
        transport = { InnerTubeResponse(200, body) },
    )

    @Test
    fun `a VLLM page keeps ATV and OMV and drops UGC by default`() = runBlocking {
        val songs = repo().likedSongs()
        assertEquals(listOf("atv", "omv"), songs.map { it.videoId })
        assertEquals(MUSIC_VIDEO_TYPE_ATV, songs[0].musicVideoType)
    }

    @Test
    fun `sync reports the ids that must leave the local mirror`() = runBlocking {
        val (kept, hidden) = repo().likedSongsForSync()
        assertEquals(listOf("atv", "omv"), kept.map { it.videoId })
        assertEquals(listOf("ugc"), hidden)
        assertTrue(kept.all { it.musicVideoType != null })
    }
}