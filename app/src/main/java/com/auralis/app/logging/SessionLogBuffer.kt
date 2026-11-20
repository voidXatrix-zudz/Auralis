package com.auralis.app.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, file-backed ring buffer for YouTube-account events only (last [maxLines] lines).
 *
 * It lives beside the rotating app log but is never touched by artwork/radio noise, so a session
 * lost days ago is still explainable. Every line passes through [SessionLogSanitizer]; callers must
 * still pass names/flags rather than values.
 *
 * The class is intentionally free of Android imports so a JVM test can simulate an app restart by
 * constructing a second instance over the same file.
 */
class SessionLogBuffer(
    private val file: File,
    private val maxLines: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    fun record(tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val line = "${stamp.get()!!.format(Date(clock()))} $tag: ${SessionLogSanitizer.redact(message)}"
                file.appendText(line + "\n")
                trimLocked()
            }
        }
    }

    /** Oldest-first, newest last; callers usually take the last 50 for the export. */
    fun lines(limit: Int = 50): List<String> {
        val safeLimit = limit.coerceAtLeast(0)
        return synchronized(lock) {
            runCatching { file.readLines().takeLast(safeLimit) }.getOrDefault(emptyList())
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }

    private fun trimLocked() {
        val all = file.readLines()
        if (all.size <= maxLines) return
        file.writeText(all.takeLast(maxLines).joinToString("\n", postfix = "\n"))
    }
}