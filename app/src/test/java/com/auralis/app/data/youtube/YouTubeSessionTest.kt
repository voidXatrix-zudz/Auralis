package com.auralis.app.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSessionTest {
    @Test
    fun `any of the three signing cookies counts as signed in material`() {
        assertTrue(hasSigningCookie("SAPISID=abc; VISITOR_DATA=v"))
        assertTrue(hasSigningCookie("__Secure-3PAPISID=abc; VISITOR_DATA=v"))
        assertTrue(hasSigningCookie("__Secure-1PAPISID=abc"))
        assertFalse(hasSigningCookie("VISITOR_DATA=v; PREF=x"))
        assertFalse(hasSigningCookie("SAPISID=; __Secure-3PAPISID="))
    }

    @Test
    fun `signing secret prefers SAPISID and falls back to secure variants`() {
        assertEquals("one", signingCookieValue("SAPISID=one; __Secure-3PAPISID=two"))
        assertEquals("three", signingCookieValue("__Secure-1PAPISID=three"))
        assertNull(signingCookieValue("VISITOR_DATA=v"))
    }

    @Test
    fun `data sync id keeps the active half after the double pipe`() {
        // `DATASYNC_ID` is `account||delegated`; the second half is what onBehalfOfUser accepts.
        assertEquals("delegated", normalizeDataSyncId("account||delegated"))
        assertEquals("delegated", normalizeDataSyncId("  account  ||delegated"))
        // A trailing separator carries no active identity, so the account half is the fallback.
        assertEquals("account", normalizeDataSyncId("account||"))
        assertEquals("solo", normalizeDataSyncId("solo"))
        assertEquals("delegated", normalizeDataSyncId("||delegated"))
        assertNull(normalizeDataSyncId("   "))
        assertNull(normalizeDataSyncId(null))
    }

    @Test
    fun `sapisid hash matches the documented SHA1 input`() {
        // SHA1("1700000000 test-secret https://music.youtube.com")
        assertEquals(
            "e696ea0974ed942022a7ad7be2decab379f16716",
            sapisidHash("test-secret", 1_700_000_000L),
        )
    }

    @Test
    fun `session survives a JSON round trip and keeps the expired flag`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = YouTubeAuth(
            cookie = "SAPISID=abc",
            visitorData = "visitor",
            dataSyncId = "account",
            pageId = "brand",
            authUser = "1",
            clientVersion = "1.20250101.00.00",
            name = "Name",
            expired = true,
        )
        val restored = json.decodeFromString(
            YouTubeAuth.serializer(),
            json.encodeToString(YouTubeAuth.serializer(), original),
        )
        assertEquals(original, restored)
        assertTrue(restored.hasSession)
        assertFalse(restored.signedIn)
    }

    @Test
    fun `cards keep their item type through serialization`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val card = YtCard(
            title = "Album",
            subtitle = "Artist • 2024",
            thumbnailUrl = "https://example.test/art.jpg",
            browseId = "MPREb_123",
            itemType = YtItemType.ALBUM.wire,
            explicit = true,
        )
        val restored = Json.decodeFromString(YtCard.serializer(), json.encodeToString(YtCard.serializer(), card))
        assertEquals(YtItemType.ALBUM, restored.type)
        assertTrue(restored.explicit)
    }
}