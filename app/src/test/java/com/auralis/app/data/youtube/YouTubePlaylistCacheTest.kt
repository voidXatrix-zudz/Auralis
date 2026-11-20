package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeCacheDao
import com.auralis.app.data.db.YouTubeCacheEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist cache must never turn a failed or empty fetch into "success". A failed refresh
 * keeps the last good payload so the Library cannot silently go blank.
 */
class YouTubePlaylistCacheTest {
    private val key = "yt_liked_playlists:anon"

    private class FakeCache : YouTubeCacheDao {
        val entries = LinkedHashMap<String, YouTubeCacheEntity>()
        override suspend fun put(entry: YouTubeCacheEntity) {
            entries[entry.cacheKey] = entry
        }

        override suspend fun get(key: String): YouTubeCacheEntity? = entries[key]

        override suspend fun clear() = entries.clear()
    }

    private fun playlistPage(title: String, id: String): String = """
        {
          "contents": {"items": [
            {"musicTwoRowItemRenderer": {
              "title": {"runs": [{"text": "$title"}]},
              "navigationEndpoint": {"browseEndpoint": {"browseId": "VL$id"}}
            }}
          ]}
        }
    """.trimIndent()

    private val emptyPage = """{"contents": {}}"""

    @Test
    fun `a failed refresh keeps the last good cache entry`() = runBlocking {
        val dao = FakeCache()
        var respond: (String) -> InnerTubeResponse = { InnerTubeResponse(200, playlistPage("Mix", "PLmix")) }
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = dao,
            transport = { request -> respond(request.url.encodedPath) },
        )

        val first = repo.likedPlaylists(force = true)
        assertTrue(first is YouTubeLoadResult.Success)
        val cached = dao.entries[key]?.payload
        assertTrue(!cached.isNullOrBlank())

        respond = { InnerTubeResponse(500, "{}") }
        val second = repo.likedPlaylists(force = true)
        assertTrue(second is YouTubeLoadResult.Failure)
        assertEquals(cached, dao.entries[key]?.payload)
    }

    @Test
    fun `an empty success never overwrites a non-empty cache`() = runBlocking {
        val dao = FakeCache()
        var respond: (String) -> InnerTubeResponse = { InnerTubeResponse(200, playlistPage("Mix", "PLmix")) }
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = dao,
            transport = { request -> respond(request.url.encodedPath) },
        )

        repo.likedPlaylists(force = true)
        val cached = dao.entries[key]?.payload

        respond = { InnerTubeResponse(200, emptyPage) }
        val empty = repo.likedPlaylists(force = true)

        assertTrue(empty is YouTubeLoadResult.Success)
        assertTrue((empty as YouTubeLoadResult.Success).value.isEmpty())
        assertEquals(cached, dao.entries[key]?.payload)
    }

    @Test
    fun `cached playlists survive a cold start and are replaced by a fresh success`() = runBlocking {
        val dao = FakeCache()
        var respond: (String) -> InnerTubeResponse = { InnerTubeResponse(200, playlistPage("Mix", "PLmix")) }
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = dao,
            transport = { request -> respond(request.url.encodedPath) },
        )

        repo.likedPlaylists(force = true)

        // Cold start: before any network call, the persisted list is readable for an immediate paint.
        assertEquals(listOf("PLmix"), repo.cachedPlaylists()?.map { it.id })

        respond = { InnerTubeResponse(200, playlistPage("Fresh", "PLfresh")) }
        val refreshed = repo.likedPlaylists(force = true)

        assertEquals(listOf("PLfresh"), (refreshed as YouTubeLoadResult.Success).value.map { it.id })
        assertEquals(listOf("PLfresh"), repo.cachedPlaylists()?.map { it.id })
    }

    @Test
    fun `a fresh cache is served without a request`() = runBlocking {
        val dao = FakeCache()
        var requests = 0
        var respond: (String) -> InnerTubeResponse = {
            requests++
            InnerTubeResponse(200, playlistPage("Mix", "PLmix"))
        }
        val repo = YouTubeRepository(
            OkHttpClient(),
            cache = dao,
            transport = { request -> respond(request.url.encodedPath) },
        )

        repo.likedPlaylists(force = true)
        requests = 0
        respond = { InnerTubeResponse(500, "{}") }

        val cached = repo.likedPlaylists()

        assertTrue(cached is YouTubeLoadResult.Success)
        assertEquals(listOf("PLmix"), (cached as YouTubeLoadResult.Success).value.map { it.id })
        assertEquals(0, requests)
    }
}