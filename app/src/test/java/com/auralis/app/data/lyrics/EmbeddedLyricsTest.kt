package com.auralis.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmbeddedLyricsTest {

    @Test
    fun `id3 USLT plain lyrics are decoded`() {
        val tag = id3Tag(listOf("USLT" to uslt("Hello from the tag")))
        val result = EmbeddedLyrics.decodeId3(tag)
        assertNotNull(result)
        assertEquals("Hello from the tag", result!!.text)
        assertEquals(false, result.synced)
    }

    @Test
    fun `id3 SYLT synchronized lyrics become LRC`() {
        val timing = ByteArrayOutputStream().apply {
            write("First line".toByteArray(Charsets.UTF_8)); write(0)
            write(beInt(12_000))
            write("Second line".toByteArray(Charsets.UTF_8)); write(0)
            write(beInt(16_500))
        }.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            write(3) // UTF-8
            write("eng".toByteArray(Charsets.US_ASCII))
            write(2) // milliseconds
            write(1) // lyrics
            write(0) // empty descriptor
            write(timing)
        }.toByteArray()
        val result = EmbeddedLyrics.decodeId3(id3Tag(listOf("SYLT" to payload)))
        assertNotNull(result)
        assertTrue(result!!.synced)
        assertTrue(result.text.contains("[00:12.00]First line"))
        assertTrue(result.text.contains("[00:16.50]Second line"))
    }

    @Test
    fun `TXXX unsynced lyrics are decoded`() {
        val payload = ByteArrayOutputStream().apply {
            write(3)
            write("UNSYNCEDLYRICS".toByteArray(Charsets.UTF_8))
            write(0)
            write("TXXX lyrics".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val result = EmbeddedLyrics.decodeId3(id3Tag(listOf("TXXX" to payload)))
        assertEquals("TXXX lyrics", result!!.text)
    }

    @Test
    fun `flac vorbis lyrics comment is decoded`() {
        val vendor = "AURALIS".toByteArray(Charsets.UTF_8)
        val lyrics = "LYRICS=Embedded FLAC line".toByteArray(Charsets.UTF_8)
        val comment = ByteArrayOutputStream().apply {
            write(leInt(vendor.size)); write(vendor)
            write(leInt(1))
            write(leInt(lyrics.size)); write(lyrics)
        }.toByteArray()
        val blockHeader = byteArrayOf(0x84.toByte(), 0, 0, 0)
        val length = comment.size
        blockHeader[1] = ((length shr 16) and 0xFF).toByte()
        blockHeader[2] = ((length shr 8) and 0xFF).toByte()
        blockHeader[3] = (length and 0xFF).toByte()
        val file = "fLaC".toByteArray(Charsets.US_ASCII) + blockHeader + comment
        val result = EmbeddedLyrics.decodeFlac(file)
        assertEquals("Embedded FLAC line", result!!.text)
    }

    @Test
    fun `mp4 lyr data atom is decoded`() {
        val text = "[00:01.00]MP4 lyric\n[00:03.00]Second".toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            write(beInt(16 + text.size))
            write("data".toByteArray(Charsets.US_ASCII))
            write(beInt(1)) // UTF-8
            write(beInt(0)) // locale
            write(text)
        }.toByteArray()
        val result = EmbeddedLyrics.decodeMp4Lyrics(payload)
        assertNotNull(result)
        assertTrue(result!!.synced)
        assertTrue(result.text.contains("MP4 lyric"))
    }

    private fun uslt(text: String): ByteArray = ByteArrayOutputStream().apply {
        write(3)
        write("eng".toByteArray(Charsets.US_ASCII))
        write(0)
        write(text.toByteArray(Charsets.UTF_8))
    }.toByteArray()

    private fun id3Tag(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, payload) in frames) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(beInt(payload.size))
            body.write(0); body.write(0)
            body.write(payload)
        }
        val bytes = body.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3); write(0); write(0)
            write(syncSafe(bytes.size))
        }.toByteArray()
        return header + bytes
    }

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun beInt(value: Int) = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun leInt(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )
}
