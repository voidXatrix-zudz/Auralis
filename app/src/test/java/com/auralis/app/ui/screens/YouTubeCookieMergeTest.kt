package com.auralis.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cookie header is assembled from music.youtube.com first, then www.youtube.com. A duplicated
 * name must keep the first (session-owning) value instead of being sent twice, which makes Google
 * answer the request as the wrong account.
 */
class YouTubeCookieMergeTest {
    @Test
    fun `duplicate names keep the first host value`() {
        val merged = mergeCookies(
            listOf(
                "SAPISID=from-music; PREF=music",
                "SAPISID=from-www; VISITOR_DATA=v; PREF=www",
            ),
        )

        assertEquals("SAPISID=from-music; PREF=music; VISITOR_DATA=v", merged)
    }

    @Test
    fun `blank fragments and empty values are dropped`() {
        val merged = mergeCookies(listOf(" ; SAPISID=one ;;PREF=", "PREF=two"))

        assertEquals("SAPISID=one; PREF=two", merged)
    }
}