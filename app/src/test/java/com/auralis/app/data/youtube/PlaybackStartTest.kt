package com.auralis.app.data.youtube

import com.auralis.app.fakeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap must play the tapped song, never the first entry of a stale or mismatched list. */
class PlaybackStartTest {
    private fun remote(id: String): com.auralis.app.domain.model.Track = YtSong(
        videoId = id,
        title = "Song $id",
        artist = "Artist",
        album = "",
        durationMs = 180_000L,
        thumbnailUrl = null,
        musicVideoType = MUSIC_VIDEO_TYPE_ATV,
    ).toTrack()

    @Test
    fun `the tapped song is found at its own position`() {
        val list = listOf(remote("a"), remote("b"), remote("c"))
        val start = playbackStart(list[2], list)
        assertTrue(start.found)
        assertEquals(2, start.index)
        assertEquals("yt:c", start.queue[start.index].id)
    }

    @Test
    fun `a missing song plays alone instead of falling back to index zero`() {
        val list = listOf(remote("a"), remote("b"))
        val missing = remote("z")
        val start = playbackStart(missing, list)
        assertFalse(start.found)
        assertEquals(0, start.index)
        assertEquals(listOf("yt:z"), start.queue.map { it.id })
    }

    @Test
    fun `local songs stay on the local queue and YouTube songs on the stream queue`() {
        val local = fakeTrack(id = "local-1")
        val stream = remote("a")
        assertFalse(local.isYouTubeTrack())
        assertTrue(stream.isYouTubeTrack())
    }
}