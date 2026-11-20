package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * T1-T3 of the self-healing prompt: a flagged session is cleared by a healthy account_menu, or by
 * one silent refresh; only when both fail does it stay expired, with a 30-minute backoff, and the
 * cookie/account are kept.
 */
class SessionHealerTest {
    private val expired = YouTubeAuth(
        cookie = "SAPISID=secret; SID=keep",
        name = "Dipanjan",
        email = "user@example.com",
        avatarUrl = "https://avatar",
        expired = true,
        lastRefreshedAt = 1_000L,
    )

    private class Fake(initial: YouTubeAuth?, var now: Long = 5_000L) {
        var session: YouTubeAuth? = initial
        var probeStatus = 200
        var refreshResult: YouTubeAuth? = null
        var refreshCalls = 0
        var failedAt = 0L
        val saved = mutableListOf<YouTubeAuth>()
        val events = mutableListOf<String>()

        fun healer() = SessionHealer(
            snapshot = { session },
            persist = { saved += it; session = it },
            probe = { probeStatus },
            refresh = { refreshCalls++; refreshResult },
            lastFailureAt = { failedAt },
            recordFailureAt = { failedAt = it },
            clock = { now },
            record = { events += it },
        )
    }

    @Test
    fun `expired plus a healthy account menu clears the flag without a refresh`() = runBlocking {
        val fake = Fake(expired)

        val outcome = fake.healer().heal()

        assertEquals(SessionHealOutcome.RECOVERED, outcome)
        assertFalse(fake.session!!.expired)
        assertTrue(fake.session!!.signedIn)
        assertEquals(0, fake.refreshCalls)
        assertEquals(5_000L, fake.session!!.lastRefreshedAt)
        assertEquals("SAPISID=secret; SID=keep", fake.session!!.cookie)
    }

    @Test
    fun `failed account menu plus a successful silent refresh recovers and the session works`() = runBlocking {
        val fake = Fake(expired).apply {
            probeStatus = 401
            refreshResult = YouTubeAuth(
                cookie = "SAPISID=fresh; SID=keep",
                visitorData = "fresh-visitor",
                name = "",
                lastRefreshedAt = now,
            )
        }

        val outcome = fake.healer().heal()

        assertEquals(SessionHealOutcome.RECOVERED, outcome)
        assertFalse(fake.session!!.expired)
        assertTrue(fake.session!!.signedIn)
        assertTrue(fake.session!!.cookie.contains("SAPISID=fresh"))
        // Profile captured at sign-in survives the refresh.
        assertEquals("Dipanjan", fake.session!!.name)
        assertEquals("user@example.com", fake.session!!.email)
        assertEquals("https://avatar", fake.session!!.avatarUrl)
        // The session is usable again, so the original call can be retried by the client, and a
        // later heal pass has nothing left to do.
        assertTrue(fake.session!!.hasSession)
        assertEquals(SessionHealOutcome.NOT_NEEDED, fake.healer().heal())
    }

    @Test
    fun `both fail stays expired, keeps the cookie, and backs off for 30 minutes`() = runBlocking {
        val fake = Fake(expired).apply {
            probeStatus = 401
            refreshResult = null
        }
        val healer = fake.healer()

        assertEquals(SessionHealOutcome.STILL_EXPIRED, healer.heal())
        assertTrue(fake.session!!.expired)
        assertEquals("SAPISID=secret; SID=keep", fake.session!!.cookie)
        assertEquals("Dipanjan", fake.session!!.name)
        assertEquals(5_000L, fake.failedAt)

        fake.now += 29 * 60_000L
        assertEquals(SessionHealOutcome.THROTTLED, healer.heal())
        assertEquals(1, fake.refreshCalls)

        fake.now += 2 * 60_000L
        assertEquals(SessionHealOutcome.STILL_EXPIRED, healer.heal())
        assertEquals(2, fake.refreshCalls)
    }

    @Test
    fun `a healthy session is left alone`() = runBlocking {
        val fake = Fake(expired.copy(expired = false))

        assertEquals(SessionHealOutcome.NOT_NEEDED, fake.healer().heal())
        assertTrue(fake.saved.isEmpty())
    }

    @Test
    fun `refreshNow merges the stored profile and never clears anything`() = runBlocking {
        val fake = Fake(expired).apply {
            refreshResult = YouTubeAuth(cookie = "SAPISID=fresh", clientVersion = "1.2")
        }

        assertTrue(fake.healer().refreshNow("401 verified refusal"))
        assertFalse(fake.session!!.expired)
        assertEquals("Dipanjan", fake.session!!.name)
        assertTrue(fake.events.any { it.contains("refresh OK") })
    }

    // ---- T4: periodic refresh of a still-valid session ---------------------------------------

    @Test
    fun `a six-hour-old session is refreshed and keeps its profile`() = runBlocking {
        val valid = expired.copy(expired = false, lastRefreshedAt = 1_000L)
        val fake = Fake(valid).apply {
            now = SessionHealer.STALE_AFTER_MS + 5_000L
            refreshResult = YouTubeAuth(cookie = "SAPISID=fresh; SID=keep", visitorData = "v2")
        }

        assertTrue(fake.healer().refreshIfStale("startup"))

        assertFalse(fake.session!!.expired)
        assertTrue(fake.session!!.signedIn)
        assertTrue(fake.session!!.cookie.contains("SAPISID=fresh"))
        assertEquals("Dipanjan", fake.session!!.name)
        assertEquals("user@example.com", fake.session!!.email)
        assertEquals("https://avatar", fake.session!!.avatarUrl)
        assertEquals(fake.now, fake.session!!.lastRefreshedAt)
        assertEquals(1, fake.refreshCalls)
    }

    @Test
    fun `a recent session is not refreshed`() = runBlocking {
        val valid = expired.copy(expired = false, lastRefreshedAt = 5_000L)
        val fake = Fake(valid)

        assertFalse(fake.healer().refreshIfStale("foreground"))

        assertEquals(0, fake.refreshCalls)
        assertEquals(5_000L, fake.session!!.lastRefreshedAt)
    }

    @Test
    fun `LOGGED_IN false never clears the session and backs off`() = runBlocking {
        val valid = expired.copy(expired = false, lastRefreshedAt = 1_000L)
        val fake = Fake(valid).apply {
            now = SessionHealer.STALE_AFTER_MS + 5_000L
            refreshResult = null // YouTubeSilentRefresh returns null when ytcfg LOGGED_IN=false
        }
        val healer = fake.healer()

        assertFalse(healer.refreshIfStale("startup"))

        assertFalse(fake.session!!.expired)
        assertEquals("SAPISID=secret; SID=keep", fake.session!!.cookie)
        assertEquals("Dipanjan", fake.session!!.name)
        assertEquals(fake.now, fake.failedAt)

        // The next automatic trigger waits for the 30-minute backoff.
        fake.now += 10 * 60_000L
        assertFalse(healer.refreshIfStale("foreground"))
        assertEquals(1, fake.refreshCalls)
    }
}