package com.auralis.app.ui.components

import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.youtube.YtPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker must show every playlist exactly once: a local playlist that mirrors a YouTube
 * playlist is one row (the local one, which writes Room and the account together), and an
 * account playlist without a mirror is its own row.
 */
class PlaylistPickerTest {

    private fun local(id: Long, name: String, remoteId: String? = null) =
        PlaylistEntity(id = id, name = name, createdAt = 0L, updatedAt = 0L, remoteId = remoteId)

    private fun account(id: String, title: String, count: Int = 0) =
        YtPlaylist(id = id, title = title, thumbnailUrl = null, trackCount = count, owner = "")

    @Test
    fun `a mirrored account playlist is shown once as the local row`() {
        val rows = playlistPickRows(
            localPlaylists = listOf(local(7, "Road trip", remoteId = "PLabc")),
            accountPlaylists = listOf(account("VLPLabc", "Road trip")),
        )

        assertEquals(1, rows.size)
        assertEquals(7L, rows.single().localPlaylistId)
        assertNull(rows.single().accountPlaylist)
    }

    @Test
    fun `a mirrored playlist is still matched when the account id has no VL prefix`() {
        val rows = playlistPickRows(
            localPlaylists = listOf(local(1, "Focus", remoteId = "PLxyz")),
            accountPlaylists = listOf(account("PLxyz", "Focus")),
        )

        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().localPlaylistId)
    }

    @Test
    fun `account playlists without a local mirror are appended`() {
        val rows = playlistPickRows(
            localPlaylists = listOf(local(1, "On device")),
            accountPlaylists = listOf(account("VLPLremote", "Account only", count = 12)),
        )

        assertEquals(2, rows.size)
        assertNotNull(rows[0].localPlaylistId)
        assertEquals("Account only", rows[1].name)
        assertNotNull(rows[1].accountPlaylist)
        assertNull(rows[1].localPlaylistId)
    }

    @Test
    fun `rows are grouped locals first and alphabetical inside each source`() {
        val rows = playlistPickRows(
            localPlaylists = listOf(local(1, "Zebra"), local(2, "Apple")),
            accountPlaylists = listOf(account("VLPLb", "Beta"), account("VLPLa", "Alpha")),
        )

        assertEquals(listOf("Apple", "Zebra", "Alpha", "Beta"), rows.map { it.name })
    }

    @Test
    fun `duplicate account ids collapse to one row`() {
        val rows = playlistPickRows(
            localPlaylists = emptyList(),
            accountPlaylists = listOf(account("VLPLsame", "Same"), account("PLsame", "Same")),
        )

        assertEquals(1, rows.size)
        assertEquals("Same", rows.single().name)
    }

    @Test
    fun `the origin tells a user whether a playlist lives on the device or the account`() {
        val rows = playlistPickRows(
            localPlaylists = listOf(local(1, "Local only"), local(2, "Synced", remoteId = "PLs")),
            accountPlaylists = listOf(account("VLPLr", "Remote", count = 4)),
        )

        val byName = rows.associateBy { it.name }
        assertEquals("On this device", byName.getValue("Local only").origin)
        assertEquals("Synced to YouTube Music", byName.getValue("Synced").origin)
        assertTrue(byName.getValue("Remote").origin.contains("4 songs"))
    }
}