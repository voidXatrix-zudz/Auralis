package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeRecentEntity
import com.auralis.app.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataFillerTest {

    private val videoId = "dQw4w9WgXcQ"

    private fun placeholder(artist: String = ""): Track = Track(
        id = "$YOUTUBE_TRACK_PREFIX$videoId",
        sourceId = YOUTUBE_SOURCE_ID,
        uri = youtubePlaybackUri(videoId),
        relativePath = "",
        displayName = "",
        sizeBytes = 0L,
        lastModified = 0L,
        ext = "webm",
        mime = "audio/webm",
        title = "",
        artist = artist,
        albumArtist = artist,
        album = "",
        genres = emptyList(),
        trackNumber = null,
        discNumber = null,
        year = null,
        composer = null,
        durationMs = 0L,
        codec = null,
        container = null,
        sampleRateHz = null,
        bitDepth = null,
        bitrateBps = null,
        channels = null,
        lossless = false,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
        artKey = null,
        artColor = null,
        addedAt = 0L,
        tagError = null,
        isRemote = true,
        artworkUrl = fallbackThumbnailUrl(videoId),
        remoteId = videoId,
    )

    private fun song(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000L,
    ) = YtSong(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = fallbackThumbnailUrl(videoId),
    )

    @Test
    fun `a lookup stops as soon as the metadata completes`() = runTest {
        val filled = mutableListOf<Track>()
        val settled = mutableListOf<String>()
        var fetches = 0
        val filler = MetadataFiller(
            scope = this,
            fetch = { fetches++; song() },
            onFilled = { filled += it },
            onSettled = { settled += it },
        )

        filler.request(placeholder())
        advanceUntilIdle()

        assertEquals(1, fetches)
        assertEquals("Song", filled.last().title)
        assertEquals("Artist", filled.last().artist)
        assertEquals(listOf(placeholder().id), settled)
    }

    @Test
    fun `a missing song is retried at one three six ten and ten seconds then settles`() = runTest {
        val settled = mutableListOf<String>()
        var fetches = 0
        val filler = MetadataFiller(
            scope = this,
            fetch = { fetches++; null },
            onFilled = { error("nothing should fill") },
            onSettled = { settled += it },
        )

        filler.request(placeholder())
        runCurrent()
        assertEquals(1, fetches)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fetches)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3, fetches)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(4, fetches)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(5, fetches)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(6, fetches)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(6, fetches)
        assertEquals(listOf(placeholder().id), settled)
    }

    @Test
    fun `the same song is never looked up twice at once`() = runTest {
        val gate = CompletableDeferred<YtSong?>()
        var fetches = 0
        val filler = MetadataFiller(
            scope = this,
            fetch = { fetches++; gate.await() },
            onFilled = {},
            onSettled = {},
        )

        filler.request(placeholder())
        filler.request(placeholder())
        runCurrent()
        assertEquals(1, fetches)

        gate.complete(song())
        advanceUntilIdle()
        assertEquals(1, fetches)
    }

    @Test
    fun `release cancels the window without settling`() = runTest {
        val settled = mutableListOf<String>()
        var fetches = 0
        val filler = MetadataFiller(
            scope = this,
            fetch = { fetches++; null },
            onFilled = {},
            onSettled = { settled += it },
        )

        val track = placeholder()
        filler.request(track)
        runCurrent()
        filler.release(track.id)
        advanceTimeBy(MetadataFiller.DEADLINE_MS)
        runCurrent()

        assertEquals(1, fetches)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `a completed lookup is not requested again by a later screen`() = runTest {
        var fetches = 0
        val filler = MetadataFiller(
            scope = this,
            fetch = { fetches++; song() },
            onFilled = {},
            onSettled = {},
        )
        val complete = placeholder().filledWith(song())

        filler.request(complete)
        advanceUntilIdle()
        assertEquals(0, fetches)
    }

    @Test
    fun `only missing fields are filled`() {
        val current = placeholder(artist = "Known artist").copy(
            title = "Known title",
            displayName = "Known title",
            durationMs = 90_000L,
        )

        val merged = current.filledWith(song(title = "Other title", artist = "Other artist", durationMs = 180_000L))

        assertEquals("Known title", merged.title)
        assertEquals("Known artist", merged.artist)
        assertEquals(90_000L, merged.durationMs)
    }

    @Test
    fun `a video id placeholder is replaced with the real title`() {
        val current = placeholder().copy(title = videoId, displayName = videoId)

        val merged = current.filledWith(song())

        assertEquals("Song", merged.title)
        assertEquals("Song", merged.displayName)
        assertEquals("Artist", merged.artist)
        assertEquals(180_000L, merged.durationMs)
    }

    @Test
    fun `a blank provider result never erases or adds a placeholder`() {
        val current = placeholder().copy(title = "Title", artist = "Artist")

        val merged = current.filledWith(song(title = "", artist = "Unknown artist", album = "", durationMs = 0L))

        assertEquals("Title", merged.title)
        assertEquals("Artist", merged.artist)
        assertEquals("", merged.album)
        assertTrue(!merged.needsMetadataFill)
    }

    @Test
    fun `history rows keep their play count and timestamp when metadata is filled`() {
        val row = YouTubeRecentEntity(
            videoId = videoId,
            title = videoId,
            artist = "",
            album = "",
            durationMs = 0L,
            thumbnailUrl = fallbackThumbnailUrl(videoId),
            playedAt = 1234L,
            playCount = 7,
        )

        val merged = row.filledWith(placeholder().filledWith(song()))

        assertEquals("Song", merged.title)
        assertEquals("Artist", merged.artist)
        assertEquals("Album", merged.album)
        assertEquals(180_000L, merged.durationMs)
        assertEquals(1234L, merged.playedAt)
        assertEquals(7, merged.playCount)
    }

    @Test
    fun `a local track never asks for metadata`() {
        val local = placeholder().copy(isRemote = false, remoteId = null, title = "", artist = "")

        assertFalse(local.needsMetadataFill)
        assertNull(local.remoteId)
    }
}