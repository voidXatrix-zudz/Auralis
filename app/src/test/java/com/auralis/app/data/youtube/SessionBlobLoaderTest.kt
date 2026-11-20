package com.auralis.app.data.youtube

import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5: a failed decrypt returns null, logs the exception TYPE, and never deletes the blob. */
class SessionBlobLoaderTest {
    @Test
    fun `AEAD tag failure is reported by type and the blob is kept`() {
        var blob: String? = "cipher-text"
        val logs = mutableListOf<String>()
        val loader = SessionBlobLoader(
            readBlob = { blob },
            decrypt = { throw AEADBadTagException("bad tag") },
            parse = { error("must not parse") },
            record = { logs += it },
        )

        val result = loader.load()

        assertEquals(SessionLoadResult.Unreadable("AEADBadTagException"), result)
        assertEquals("cipher-text", blob)
        assertTrue(logs.single().contains("AEADBadTagException"))
        assertTrue(logs.single().contains("blob kept"))
    }

    @Test
    fun `a missing keystore key is unreadable and the blob survives`() {
        var blob: String? = "cipher-text"
        val loader = SessionBlobLoader(
            readBlob = { blob },
            decrypt = { throw java.security.UnrecoverableKeyException("key missing") },
            parse = { null },
        )

        assertEquals(SessionLoadResult.Unreadable("UnrecoverableKeyException"), loader.load())
        assertEquals("cipher-text", blob)
    }

    @Test
    fun `a JSON error is reported separately and does not touch the blob`() {
        var blob: String? = "plain-text"
        val loader = SessionBlobLoader(
            readBlob = { blob },
            decrypt = { it },
            parse = { throw SerializationException("bad json") },
        )

        assertEquals(SessionLoadResult.Unreadable("SerializationException"), loader.load())
        assertEquals("plain-text", blob)
    }

    @Test
    fun `no blob means no session and no error`() {
        val loader = SessionBlobLoader(readBlob = { null }, decrypt = { it }, parse = { null })
        assertNull(loader.load())
    }

    @Test
    fun `a successful read returns the session`() {
        val session = YouTubeAuth(cookie = "SAPISID=secret", name = "Dipanjan")
        val loader = SessionBlobLoader(
            readBlob = { "blob" },
            decrypt = { "{\"cookie\":\"SAPISID=secret\",\"name\":\"Dipanjan\"}" },
            parse = { session },
        )

        assertEquals(SessionLoadResult.Loaded(session), loader.load())
    }
}