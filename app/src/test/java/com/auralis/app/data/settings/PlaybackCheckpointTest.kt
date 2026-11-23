package com.auralis.app.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checkpoint's queue JSON is the only place a corrupt store could hurt the launch; prove it is total. */
class PlaybackCheckpointTest {

    private fun item(id: String) = CheckpointTrack(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        durationMs = 123_000L,
        artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        isRemote = true,
        remoteId = id,
    )

    @Test
    fun `queue round-trips through JSON`() {
        val items = listOf(item("a"), item("b"))
        val decoded = PlaybackCheckpointCodec.decode(PlaybackCheckpointCodec.encode(items))
        assertEquals(items, decoded)
    }

    @Test
    fun `queue is capped at fifty entries on encode and decode`() {
        val many = (1..80).map { item("v$it") }
        val encoded = PlaybackCheckpointCodec.encode(many)
        assertEquals(PlaybackCheckpointCodec.MAX_QUEUE, PlaybackCheckpointCodec.decode(encoded).size)
    }

    @Test
    fun `malformed JSON decodes to an empty list`() {
        assertTrue(PlaybackCheckpointCodec.decode("{not json").isEmpty())
        assertTrue(PlaybackCheckpointCodec.decode("[]").isEmpty())
    }

    @Test
    fun `blank or missing payload decodes to an empty list`() {
        assertTrue(PlaybackCheckpointCodec.decode(null).isEmpty())
        assertTrue(PlaybackCheckpointCodec.decode("").isEmpty())
    }

    @Test
    fun `an old store with only the five original keys still loads`() {
        val prefs = mutablePreferencesOf(
            PlaybackSnapshotKeys.trackId to "local-1",
            PlaybackSnapshotKeys.positionMs to 42_000L,
            PlaybackSnapshotKeys.wasPlaying to true,
            PlaybackSnapshotKeys.shuffle to true,
            PlaybackSnapshotKeys.repeatMode to 2,
        )
        val snapshot = playbackSnapshotFrom(prefs)!!
        assertEquals("local-1", snapshot.trackId)
        assertEquals(42_000L, snapshot.positionMs)
        assertEquals(2, snapshot.repeatMode)
        assertNull(snapshot.queueJson)
        assertEquals(0L, snapshot.durationMs)
    }

    @Test
    fun `an empty store has no snapshot`() {
        assertNull(playbackSnapshotFrom(mutablePreferencesOf()))
    }

    @Test
    fun `a remote checkpoint rebuilds a streamable Track without a URL`() {
        val track = item("abc").toTrack()
        assertEquals("yt:abc", track.id)
        assertEquals("auralis-yt://abc", track.uri)
        assertTrue(track.isRemote)
        assertEquals("abc", track.remoteId)
    }

    @Test
    fun `a local checkpoint without a library URI is display-only`() {
        val checkpoint = CheckpointTrack(id = "local-1", title = "T", artist = "A", album = "Al")
        val track = checkpoint.toTrack()
        assertEquals("", track.uri)
        assertEquals("T", track.title)
        assertFalse(track.isRemote)
    }

    @Test
    fun `a Track maps back to its display checkpoint`() {
        val track = item("xyz").toTrack()
        val back = track.toCheckpointTrack()
        assertEquals("yt:xyz", back.id)
        assertEquals("xyz", back.remoteId)
        assertTrue(back.isRemote)
        assertEquals(track.durationMs, back.durationMs)
    }
}
