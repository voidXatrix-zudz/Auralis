package com.auralis.app.data.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider-candidate ranking/rejection shared by [MetadataRepository] and the artwork resolver. */
class MetadataRankingTest {

    private fun candidate(
        title: String,
        artists: String = "Arijit Singh",
        durationMs: Long? = 180_000L,
    ) = MetadataCandidate(source = "test", title = title, artists = artists, durationMs = durationMs)

    @Test
    fun `an edit marker in the candidate title still matches`() {
        assertTrue(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Kesariya (Official Audio)"),
            ),
        )
    }

    @Test
    fun `a lyrics marker in the candidate title still matches`() {
        assertTrue(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Kesariya (Lyrics)"),
            ),
        )
    }

    @Test
    fun `a remaster suffix and a featuring are ignored`() {
        assertTrue(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Kesariya (feat. Someone) - Remastered 2024"),
            ),
        )
    }

    @Test
    fun `an unrelated title is rejected`() {
        assertFalse(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Completely Different Song"),
            ),
        )
    }

    @Test
    fun `a clearly different artist is rejected`() {
        assertFalse(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Kesariya", artists = "Ed Sheeran"),
            ),
        )
    }

    @Test
    fun `a duration far from the requested one is rejected`() {
        assertFalse(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = 180_000L,
                candidate = candidate("Kesariya", durationMs = 300_000L),
            ),
        )
    }

    @Test
    fun `a matching title with a blank artist is accepted`() {
        assertTrue(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "",
                requestedDurationMs = null,
                candidate = candidate("Kesariya", artists = ""),
            ),
        )
    }

    @Test
    fun `Devanagari titles compare in the same script`() {
        assertTrue(
            metadataCandidateAccepted(
                requestedTitle = "केसरिया",
                requestedArtist = "अरिजीत सिंह",
                requestedDurationMs = 180_000L,
                candidate = candidate("केसरिया", artists = "अरिजीत सिंह"),
            ),
        )
    }

    @Test
    fun `a blank candidate title is rejected`() {
        assertFalse(
            metadataCandidateAccepted(
                requestedTitle = "Kesariya",
                requestedArtist = "Arijit Singh",
                requestedDurationMs = null,
                candidate = candidate(""),
            ),
        )
    }

    @Test
    fun `title similarity uses the cleaned form`() {
        assertTrue(titleSimilarity("Kesariya", "Kesariya (Official Video)") >= 0.99)
        assertTrue(titleSimilarity("Kesariya", "Unrelated") < 0.8)
    }
}