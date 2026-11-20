package com.auralis.app.ui.screens

import com.auralis.app.data.youtube.YtSong
import com.auralis.app.data.youtube.isYouTubeTrack
import com.auralis.app.data.youtube.playbackStart
import com.auralis.app.data.youtube.routeLibraryPlay
import com.auralis.app.data.youtube.toTrack
import com.auralis.app.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library taps must play the row that was tapped, and the empty hero must never replace a connected
 * YouTube Library. Pure rules, so no Android or player is involved.
 */
class YouTubeLibraryRoutingTest {

    private fun streamed(videoId: String): Track = YtSong(
        videoId = videoId,
        title = "Song $videoId",
        artist = "Artist",
        album = "",
        durationMs = 180_000,
        thumbnailUrl = null,
    ).toTrack()

    private fun local(id: String): Track = streamed(id).copy(
        id = id,
        uri = "content://media/external/audio/media/$id",
        isRemote = false,
        remoteId = null,
    )

    @Test
    fun `tapping a track missing from the list never falls back to the first item`() {
        val tapped = streamed("tapped")
        val list = listOf(local("local-1"), local("local-2"))

        val start = playbackStart(tapped, list)

        assertFalse(start.found)
        assertEquals(listOf(tapped), start.queue)
        assertEquals(0, start.index)
    }

    @Test
    fun `a track that is in the list keeps its real index`() {
        val tapped = local("local-2")
        val list = listOf(local("local-1"), tapped, local("local-3"))

        val start = playbackStart(tapped, list)

        assertTrue(start.found)
        assertSame(list, start.queue)
        assertEquals(1, start.index)
    }

    @Test
    fun `a mixed liked list sends streams and local files down different paths`() {
        val stream = streamed("abc123")
        val file = local("local-1")
        val localCalls = mutableListOf<Track>()
        val youtubeCalls = mutableListOf<Track>()

        routeLibraryPlay(stream, { localCalls += it }, { youtubeCalls += it })
        routeLibraryPlay(file, { localCalls += it }, { youtubeCalls += it })

        assertTrue(stream.isYouTubeTrack())
        assertFalse(file.isYouTubeTrack())
        assertEquals(listOf(stream), youtubeCalls)
        assertEquals(listOf(file), localCalls)
    }

    @Test
    fun `connected and signed in is never treated as an empty library`() {
        val empty = isLibraryEmpty(
            youtubeEnabled = true,
            youtubeSignedIn = true,
            localTracks = 0,
            playlists = 0,
            youtubePlaylists = 0,
            downloads = 0,
            youtubeLiked = 0,
            spotifyImports = 0,
            youtubeError = null,
        )
        assertFalse(empty)
    }

    @Test
    fun `signed out with no local access keeps the normal empty hero`() {
        val empty = isLibraryEmpty(
            youtubeEnabled = false,
            youtubeSignedIn = false,
            localTracks = 0,
            playlists = 0,
            youtubePlaylists = 0,
            downloads = 0,
            youtubeLiked = 0,
            spotifyImports = 0,
            youtubeError = null,
        )
        assertTrue(empty)
    }

    @Test
    fun `a refresh failure is not an empty library`() {
        val empty = isLibraryEmpty(
            youtubeEnabled = true,
            youtubeSignedIn = false,
            localTracks = 0,
            playlists = 0,
            youtubePlaylists = 0,
            downloads = 0,
            youtubeLiked = 0,
            spotifyImports = 0,
            youtubeError = "Couldn't refresh playlists",
        )
        assertFalse(empty)
    }
}