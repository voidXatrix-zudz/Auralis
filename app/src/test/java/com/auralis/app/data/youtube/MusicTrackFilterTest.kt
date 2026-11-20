package com.auralis.app.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared songs-only matrix. Every YouTube surface delegates here, so these are the rules. */
class MusicTrackFilterTest {
    private fun song(type: String?, durationMs: Long = 200_000L) = YtSong(
        videoId = "v",
        title = "Title",
        artist = "Artist",
        album = "",
        durationMs = durationMs,
        thumbnailUrl = null,
        musicVideoType = type,
    )

    @Test
    fun `catalogue audio passes in both modes`() {
        assertTrue(isMusicTrack(song(MUSIC_VIDEO_TYPE_ATV), strict = false))
        assertTrue(isMusicTrack(song(MUSIC_VIDEO_TYPE_ATV), strict = true))
    }

    @Test
    fun `official source and music videos pass by default but not in strict mode`() {
        assertTrue(isMusicTrack(song(MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC)))
        assertTrue(isMusicTrack(song(MUSIC_VIDEO_TYPE_OMV)))
        assertFalse(isMusicTrack(song(MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC), strict = true))
        assertFalse(isMusicTrack(song(MUSIC_VIDEO_TYPE_OMV), strict = true))
    }

    @Test
    fun `ugc podcasts and private tracks never pass`() {
        for (type in listOf(
            MUSIC_VIDEO_TYPE_UGC,
            MUSIC_VIDEO_TYPE_PODCAST_EPISODE,
            MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK,
            "MUSIC_VIDEO_TYPE_SOMETHING_NEW",
        )) {
            assertFalse(type, isMusicTrack(song(type), strict = false))
            assertFalse(type, isMusicTrack(song(type), strict = true))
        }
    }

    @Test
    fun `typeless rows pass only while shorter than twelve minutes`() {
        assertTrue(isMusicTrack(song(null, durationMs = 11 * 60_000L)))
        assertTrue(isMusicTrack(song("", durationMs = 1_000L)))
        assertFalse(isMusicTrack(song(null, durationMs = MAX_MUSIC_TRACK_MS)))
        assertFalse(isMusicTrack(song(null, durationMs = 12 * 60_000L + 1)))
    }

    @Test
    fun `an official video longer than twelve minutes still passes by default`() {
        assertTrue(isMusicTrack(song(MUSIC_VIDEO_TYPE_OMV, durationMs = 20 * 60_000L)))
    }

    @Test
    fun `playlist browse ids are normalized to exactly one VL prefix`() {
        assertEquals("VLPLabc", normalizePlaylistBrowseId("VLPLabc"))
        assertEquals("VLPLabc", normalizePlaylistBrowseId("PLabc"))
        assertEquals("VLPLabc", normalizePlaylistBrowseId("  VLPLabc  "))
        // Album cards browse their own `MPREb` page, which must not gain a VL prefix.
        assertEquals("MPREb_abc", normalizePlaylistBrowseId("MPREb_abc"))
    }
}