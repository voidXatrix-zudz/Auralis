package com.auralis.app.data.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A copied web session must survive being closed for a while. Google rotates `__Secure-1PSIDTS` and
 * the `*SIDCC` cookies on its replies, so the app persists those refreshes, and a session that was
 * flagged expired after a transient refusal is re-checked (and revived) on the next launch.
 */
class YouTubeSessionRefreshTest {

    private val okHttp = OkHttpClient()

    // ---- cookie merge -----------------------------------------------------------------------

    @Test
    fun `a rotated cookie replaces the stored value and keeps the rest`() {
        val existing = "SID=keep; __Secure-1PSIDTS=stale; SAPISID=secret"

        val merged = mergeCookieHeader(
            existing,
            listOf("__Secure-1PSIDTS=fresh; Path=/; Domain=.youtube.com; Secure; HttpOnly"),
        )

        assertEquals("SID=keep; __Secure-1PSIDTS=fresh; SAPISID=secret", merged)
    }

    @Test
    fun `unknown, blank and deleted cookies are ignored`() {
        val existing = "SID=keep; SAPISID=secret"

        assertNull(mergeCookieHeader(existing, listOf("_gcl_au=marketing; Path=/")))
        assertNull(mergeCookieHeader(existing, listOf("SID=; Path=/; Max-Age=0")))
        assertNull(mergeCookieHeader(existing, listOf("SID=keep; Path=/")))
    }

    // ---- persistence on a signed call -------------------------------------------------------

    @Test
    fun `rotated cookies from a signed reply are persisted`() = runBlocking {
        val session = YouTubeAuth(
            cookie = "SAPISID=secret; __Secure-1PSIDTS=stale",
            clientVersion = "1.20250101.00.00",
            userAgent = "test-agent",
        )
        var persisted: String? = null
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            onSessionCookies = { persisted = it },
            transport = {
                InnerTubeResponse(
                    200,
                    "{\"actions\":[]}",
                    setCookies = listOf("__Secure-1PSIDTS=fresh; Path=/; Secure"),
                )
            },
        )

        client.accountMenu()

        assertNotNull(persisted)
        assertTrue(persisted!!.contains("__Secure-1PSIDTS=fresh"))
        assertTrue(persisted!!.contains("SAPISID=secret"))
    }

    // ---- revive of a flagged session ---------------------------------------------------------

    @Test
    fun `an expired session is still signed when it is re-checked`() = runBlocking {
        val session = YouTubeAuth(
            cookie = "SAPISID=secret",
            clientVersion = "1.20250101.00.00",
            userAgent = "test-agent",
            expired = true,
        )
        val sentCookie = mutableListOf<Boolean>()
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            transport = { request ->
                sentCookie += request.header("Cookie") != null
                InnerTubeResponse(200, "{\"actions\":[]}")
            },
        )

        assertEquals(200, client.probeStoredSession())
        assertTrue(sentCookie.isNotEmpty())
        assertTrue(sentCookie.all { it })
    }

    @Test
    fun `a genuinely revoked session stays refused`() = runBlocking {
        val session = YouTubeAuth(
            cookie = "SAPISID=secret",
            clientVersion = "1.20250101.00.00",
            userAgent = "test-agent",
            expired = true,
        )
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            transport = { InnerTubeResponse(401, "{\"error\":{\"code\":401}}") },
        )

        assertEquals(401, client.probeStoredSession())
    }

    @Test
    fun `a refusal that carries a rotated cookie is retried once`() = runBlocking {
        var session: YouTubeAuth? = YouTubeAuth(
            cookie = "SAPISID=secret; __Secure-1PSIDTS=stale",
            clientVersion = "1.20250101.00.00",
            userAgent = "test-agent",
            expired = true,
        )
        var calls = 0
        val client = InnerTubeClient(
            http = okHttp,
            auth = { session },
            onSessionCookies = { merged -> session = session?.copy(cookie = merged) },
            transport = { request ->
                calls++
                if (request.header("Cookie").orEmpty().contains("__Secure-1PSIDTS=stale")) {
                    InnerTubeResponse(
                        401,
                        "{\"error\":{\"code\":401}}",
                        setCookies = listOf("__Secure-1PSIDTS=fresh; Path=/; Secure"),
                    )
                } else {
                    InnerTubeResponse(200, "{\"actions\":[]}")
                }
            },
        )

        assertEquals(200, client.probeStoredSession())
        assertEquals(2, calls)
        assertTrue(session!!.cookie.contains("__Secure-1PSIDTS=fresh"))
        assertFalse(session!!.cookie.contains("__Secure-1PSIDTS=stale"))
    }
}