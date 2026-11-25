package com.auralis.app.data.lyrics

import com.auralis.app.fakeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPrecisionTest {
    private val track = fakeTrack().copy(title = "Halo (Official Video)", artist = "Beyoncé feat. Jay-Z", durationMs = 200_000)
    private val body = """[ti:Halo]
[ar:Beyonce]
[length:03:20.00]
[00:00.00]Remember those walls I built
[00:10.00]Well baby they are tumbling down
[00:20.00]And they did not even put up a fight
[00:30.00]They did not even make a sound"""

    @Test fun `normalization removes accents and release noise`() {
        assertEquals("halo", LyricsPrecision.normalize("Halo (Official Video)"))
        assertEquals(listOf("Beyoncé", "Jay-Z"), LyricsPrecision.artistNames("Beyoncé feat. Jay-Z"))
    }

    @Test fun `accepts exact title artist and duration`() {
        val check = LyricsPrecision.check(track, body)
        assertTrue(check.accepted)
        assertTrue(check.score >= 72)
    }

    @Test fun `rejects a different song`() {
        assertFalse(LyricsPrecision.check(track, body.replace("[ti:Halo]", "[ti:Completely Different Song]")).accepted)
    }

    @Test fun `rejects wrong artist`() {
        assertEquals("artist mismatch", LyricsPrecision.check(track, body.replace("[ar:Beyonce]", "[ar:Someone Else]")).reason)
    }

    @Test fun `rejects duration mismatch`() {
        assertEquals("duration mismatch", LyricsPrecision.check(track, body.replace("03:20.00", "06:20.00")).reason)
    }

    @Test fun `rejects boilerplate and instrumental notices`() {
        val spam = "Lyrics provided by Example\n".repeat(5)
        assertEquals("boilerplate/instrumental", LyricsPrecision.check(track, spam).reason)
        assertFalse(LyricsPrecision.check(track, "Instrumental").accepted)
    }

    @Test fun `rejects lyrics that are too short`() {
        assertEquals("too short", LyricsPrecision.check(track, "[ti:Halo]\n[ar:Beyonce]\n[00:00]oh").reason)
    }

    @Test fun `sanitizer decodes entities and removes boilerplate and duplicate timestamp rows`() {
        val cleaned = LyricsPrecision.sanitize("""[00:01.00]Rock &amp; Roll
[00:01.00]Rock &amp; Roll
Lyrics provided by Example
[00:02.00]Second line""")
        assertEquals(2, cleaned.lines().size)
        assertTrue(cleaned.contains("Rock & Roll"))
        assertFalse(cleaned.contains("provided by"))
    }

    @Test fun `cross provider comparison detects matching text`() {
        assertTrue(LyricsPrecision.lyricSimilarity(body, body) > 0.9)
        assertTrue(LyricsPrecision.lyricSimilarity(body, "[00:00]Totally unrelated words\n[00:10]Different song lyrics") < 0.48)
    }
}
