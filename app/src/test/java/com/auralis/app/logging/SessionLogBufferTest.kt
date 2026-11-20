package com.auralis.app.logging

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T2: the YouTube session log is a file, so it survives a process restart. */
class SessionLogBufferTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("auralis-session-log").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `lines persist across a simulated restart`() {
        val file = File(directory, "youtube_session.log")
        val before = SessionLogBuffer(file, clock = { 1_700_000_000_000 })
        before.record("app", "start session=yes expired=no")

        // A new process constructs a new buffer over the same file.
        val after = SessionLogBuffer(file)

        val lines = after.lines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("app: start session=yes expired=no"))
    }

    @Test
    fun `only the newest max lines are kept`() {
        val file = File(directory, "youtube_session.log")
        val buffer = SessionLogBuffer(file, maxLines = 300)
        repeat(310) { index -> buffer.record("load", "event $index") }

        val lines = buffer.lines(limit = 1_000)
        assertEquals(300, lines.size)
        assertTrue(lines.first().endsWith("event 10"))
        assertTrue(lines.last().endsWith("event 309"))
    }

    @Test
    fun `secrets are redacted on the way in and out`() {
        val file = File(directory, "youtube_session.log")
        val buffer = SessionLogBuffer(file)
        buffer.record("auth", "401 Cookie: SAPISID=super-secret; SID=other-secret")

        val line = buffer.lines().single()
        assertTrue(line.contains("SAPISID=<redacted>"))
        assertTrue(line.contains("SID=<redacted>"))
        assertTrue(!line.contains("super-secret"))
        assertTrue(!line.contains("other-secret"))
    }
}