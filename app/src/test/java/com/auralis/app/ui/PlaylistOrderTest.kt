package com.auralis.app.ui

import com.auralis.app.fakeTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderTest {

    private fun item(id: String, entryId: Long, position: Int, remote: Boolean = false) =
        PlaylistItem(entryId, remote, fakeTrack(id = id), position)

    @Test
    fun newestAddedSongIsOnTopAndOldestAtTheBottom() {
        val items = listOf(item("a", 1, 0), item("b", 2, 1), item("c", 3, 2))
        assertEquals(listOf("c", "b", "a"), items.newestFirst().map { it.track.id })
    }

    @Test
    fun localAndStreamedSongsInterleaveByWhenTheyWereAdded() {
        // One shared counter: a(local,0) b(streamed,1) c(local,2) d(streamed,3)
        val items = listOf(item("a", 1, 0), item("c", 2, 2), item("b", 1, 1, remote = true), item("d", 2, 3, remote = true))
        assertEquals(listOf("d", "c", "b", "a"), items.newestFirst().map { it.track.id })
    }

    @Test
    fun legacyTiesResolveDeterministically() {
        val items = listOf(item("local", 5, 4), item("stream", 2, 4, remote = true))
        assertEquals(listOf("stream", "local"), items.newestFirst().map { it.track.id })
        assertEquals(listOf("stream", "local"), items.reversed().newestFirst().map { it.track.id })
    }

    @Test
    fun aMoveKeepsTheTopRowHighestSoTheNextAddedSongLandsOnTop() {
        val size = 4
        val stored = (0 until size).map { positionForDisplayIndex(it, size) }
        assertEquals(listOf(3, 2, 1, 0), stored)
        // The next insert uses max + 1, which is above every existing row.
        val nextInsert = stored.max() + 1
        assertEquals(4, nextInsert)
    }
}
