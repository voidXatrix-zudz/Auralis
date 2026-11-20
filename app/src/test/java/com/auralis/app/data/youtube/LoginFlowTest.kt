package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5: the dialog opens the saved-session page when the app has a session (or the browser jar has a
 * signing cookie), the account form otherwise, and "Use a different account" may only expire known
 * session cookie names — never the whole jar.
 */
class LoginFlowTest {

    private val saved = YouTubeAuth(
        cookie = "SAPISID=secret; SID=keep; PREF=music",
        name = "Dipanjan",
        email = "user@example.com",
    )

    @Test
    fun `a saved session selects continue even when it is flagged expired`() {
        assertEquals(YouTubeLoginMode.CONTINUE, loginMode(saved, jarHasSigningCookie = false))
        assertEquals(
            YouTubeLoginMode.CONTINUE,
            loginMode(saved.copy(expired = true), jarHasSigningCookie = false),
        )
    }

    @Test
    fun `no saved session but a logged-in jar still selects continue`() {
        assertEquals(YouTubeLoginMode.CONTINUE, loginMode(null, jarHasSigningCookie = true))
    }

    @Test
    fun `no saved session and an anonymous jar selects the account form`() {
        assertEquals(YouTubeLoginMode.SIGN_IN, loginMode(null, jarHasSigningCookie = false))
    }

    @Test
    fun `the confirm label names the saved account`() {
        assertEquals("Continue as Dipanjan", continueLabel(YouTubeLoginMode.CONTINUE, "Dipanjan"))
        assertEquals("Continue", continueLabel(YouTubeLoginMode.CONTINUE, ""))
        assertEquals("Confirm", continueLabel(YouTubeLoginMode.SIGN_IN, "Dipanjan"))
    }

    @Test
    fun `saved cookies are parsed and de-duplicated by name`() {
        val cookies = parseSavedCookies("SAPISID=one; SID=keep; SAPISID=two; PREF=; ;")

        assertEquals(listOf("SAPISID", "SID"), cookies.map { it.name })
        assertEquals("one", cookies.first().value)
        assertEquals("SAPISID=one; Path=/; Secure", savedCookieSetValue(cookies.first()))
    }

    @Test
    fun `only known session cookie names may be expired`() {
        listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PSIDTS", "SID", "LOGIN_INFO").forEach {
            assertTrue(it, isYouTubeSessionCookieName(it))
        }
        listOf("PREF", "CONSENT", "SOCS", "VISITOR_INFO1_LIVE", "SID_GUEST", "APISID2").forEach {
            assertFalse(it, isYouTubeSessionCookieName(it))
        }
    }
}