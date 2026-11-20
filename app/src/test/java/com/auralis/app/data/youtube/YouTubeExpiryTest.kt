package com.auralis.app.data.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session may only be declared expired by a session-defining call **and** a fresh
 * account-menu check that also refuses. Everything else — above all the `player` endpoint used
 * for history reporting — must leave the stored session alone.
 */
class YouTubeExpiryTest {
    private val session = YouTubeAuth(
        cookie = "SAPISID=secret",
        visitorData = "visitor",
        dataSyncId = "account||active",
        authUser = "0",
        clientVersion = "1.20250101.00.00",
        userAgent = "test-agent",
    )

    private val okHttp = OkHttpClient()

    private fun requestBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun client(
        onExpired: () -> Unit,
        handler: (path: String, body: String) -> InnerTubeResponse,
    ) = InnerTubeClient(
        http = okHttp,
        auth = { session },
        onAuthExpired = onExpired,
        transport = InnerTubeTransport { request ->
            handler(request.url.encodedPath, requestBody(request))
        },
    )

    @Test
    fun `player refusal never expires the session or probes the account menu`() = runBlocking {
        var expired = 0
        val paths = mutableListOf<String>()
        val client = client({ expired++ }) { path, _ ->
            paths += path
            if (path.endsWith("/player")) {
                InnerTubeResponse(403, "{\"error\":{\"code\":403}}")
            } else {
                InnerTubeResponse(200, "{}")
            }
        }

        assertFalse(client.reportPlayback("video", 30))
        assertFalse(client.reportPlayback("video", 30))

        assertEquals(0, expired)
        assertTrue(paths.none { it.endsWith("account_menu") })
        assertTrue(paths.all { it.endsWith("/player") })
    }

    @Test
    fun `session-defining 401 plus a refusing account menu expires exactly once`() = runBlocking {
        var expired = 0
        var menuProbes = 0
        val client = client({ expired++ }) { path, _ ->
            if (path.endsWith("account_menu")) menuProbes++
            InnerTubeResponse(401, "{\"error\":{\"code\":401}}")
        }

        val first = client.likedPlaylists()
        val second = client.likedPlaylists()

        assertTrue(first is YouTubeLoadResult.Failure)
        assertEquals(401, (first as YouTubeLoadResult.Failure).code)
        assertTrue(second is YouTubeLoadResult.Failure)
        // Two refusals, one expiry: the second pass must not emit the message again.
        assertEquals(1, expired)
        assertTrue(menuProbes >= 1)
    }

    @Test
    fun `session-defining 401 with a healthy account menu keeps the session`() = runBlocking {
        var expired = 0
        val client = client({ expired++ }) { path, _ ->
            if (path.endsWith("account_menu")) {
                InnerTubeResponse(200, "{\"contents\":{}}")
            } else {
                InnerTubeResponse(401, "{\"error\":{\"code\":401}}")
            }
        }

        val result = client.likedPlaylists()

        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(0, expired)
    }

    @Test
    fun `anonymous refusal never expires anything`() = runBlocking {
        var expired = 0
        val client = InnerTubeClient(
            http = okHttp,
            auth = { null },
            onAuthExpired = { expired++ },
            transport = { _ -> InnerTubeResponse(401, "{}") },
        )

        val result = client.likedPlaylists()

        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(0, expired)
    }

    @Test
    fun `a lone 401 followed by a successful verification does not expire or refresh`() = runBlocking {
        var expired = 0
        var refreshes = 0
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            onAuthExpired = { expired++ },
            refreshSession = { refreshes++; true },
            transport = { request ->
                if (request.url.encodedPath.endsWith("account_menu")) {
                    InnerTubeResponse(200, "{\"actions\":[]}")
                } else {
                    InnerTubeResponse(401, "{\"error\":{\"code\":401}}")
                }
            },
        )

        val result = client.likedSongsResult()

        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(0, expired)
        assertEquals(0, refreshes)
    }

    @Test
    fun `a silent refresh recovers the session and the original call is retried once`() = runBlocking {
        var expired = 0
        var refreshes = 0
        var browseCalls = 0
        var repaired = false
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            onAuthExpired = { expired++ },
            refreshSession = { refreshes++; repaired = true; true },
            transport = { request ->
                when {
                    request.url.encodedPath.endsWith("account_menu") ->
                        if (repaired) InnerTubeResponse(200, "{}") else InnerTubeResponse(401, "{}")
                    else -> {
                        browseCalls++
                        if (repaired) InnerTubeResponse(200, "{}") else InnerTubeResponse(401, "{}")
                    }
                }
            },
        )

        val result = client.likedSongsResult()

        assertTrue(result is YouTubeLoadResult.Success)
        assertEquals(0, expired)
        assertEquals(1, refreshes)
        assertTrue(browseCalls >= 2)
    }

    @Test
    fun `a failed refresh still expires exactly once`() = runBlocking {
        var expired = 0
        var refreshes = 0
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            onAuthExpired = { expired++ },
            refreshSession = { refreshes++; false },
            transport = { _ -> InnerTubeResponse(401, "{\"error\":{\"code\":401}}") },
        )

        client.likedSongsResult()
        client.likedSongsResult()

        assertEquals(1, expired)
        assertTrue(refreshes >= 1)
    }
}