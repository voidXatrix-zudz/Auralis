package com.auralis.app.data.youtube

import com.auralis.app.domain.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** URL playability + cache-expiry helpers used by [StreamResolver] before a provider may win. */
class StreamUrlValidationTest {

    private val now = 1_700_000_000_000L

    private fun url(expireSeconds: Long): String =
        "https://r1.googlevideo.com/videoplayback?expire=$expireSeconds&itag=251&x=1"

    @Test
    fun `expiry seconds are read from the query string`() {
        assertEquals(1_700_000_000L, parseExpirySeconds(url(1_700_000_000L)))
        assertNull(parseExpirySeconds("https://r1.googlevideo.com/videoplayback?itag=251"))
    }

    @Test
    fun `a good HTTPS url is accepted`() {
        assertNull(validateStreamUrl(url(now / 1000L + 3_600L), now))
    }

    @Test
    fun `empty, malformed, non-HTTPS and hostless urls are rejected`() {
        assertEquals("empty url", validateStreamUrl(null, now))
        assertEquals("empty url", validateStreamUrl("   ", now))
        assertEquals("malformed url", validateStreamUrl("not a url", now))
        assertEquals("not https", validateStreamUrl("http://r1.googlevideo.com/a?expire=1", now))
    }

    @Test
    fun `an expired or nearly-expired signature is rejected`() {
        assertEquals("expired", validateStreamUrl(url(now / 1000L - 1L), now))
        assertEquals("expired", validateStreamUrl(url(now / 1000L + 10L), now, minRemainingMs = 30_000L))
        assertNull(validateStreamUrl(url(now / 1000L + 120L), now, minRemainingMs = 30_000L))
    }

    @Test
    fun `cache TTL is capped by the URL expiry and is zero when already too close`() {
        val default = 55L * 60L * 1000L
        // No expiry parameter: use the default session TTL.
        assertEquals(default, streamCacheTtlMs("https://r1.googlevideo.com/videoplayback?itag=251", now, default, 60_000L))
        // Expires in an hour: default is shorter, so the default wins.
        assertEquals(default, streamCacheTtlMs(url(now / 1000L + 3_600L), now, default, 60_000L))
        // Expires in ten minutes: the URL's own deadline (minus margin) wins.
        assertEquals(
            10L * 60L * 1000L - 60_000L,
            streamCacheTtlMs(url(now / 1000L + 600L), now, default, 60_000L),
        )
        // Already within the margin: never cached.
        assertEquals(0L, streamCacheTtlMs(url(now / 1000L + 30L), now, default, 60_000L))
        assertEquals(0L, streamCacheTtlMs(url(now / 1000L - 5L), now, default, 60_000L))
    }

    @Test
    fun `quality picks the highest bitrate at or below the target`() {
        data class Stream(val kbps: Int)
        val streams = listOf(Stream(48), Stream(96), Stream(160), Stream(256))

        assertEquals(48, pickByQuality(streams, StreamQuality.LOW) { it.kbps }!!.kbps)
        assertEquals(96, pickByQuality(streams, StreamQuality.NORMAL) { it.kbps }!!.kbps)
        assertEquals(160, pickByQuality(streams, StreamQuality.HIGH) { it.kbps }!!.kbps)
        assertEquals(256, pickByQuality(streams, StreamQuality.BEST) { it.kbps }!!.kbps)
        // Nothing at or below a very low target: fall back to the lowest available.
        assertEquals(48, pickByQuality(streams, StreamQuality.LOW) { it.kbps }!!.kbps)
    }
}