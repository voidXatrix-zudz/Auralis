package com.auralis.app.logging

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One parsed line from the rotating log file. [stackTrace] is the continuation, if any. */
data class LogEntry(
    val timestamp: String,
    val level: Char,
    val tag: String,
    val message: String,
    val stackTrace: String?,
) {
    val isError: Boolean get() = level == 'E' || level == 'F'
    val isWarning: Boolean get() = level == 'W'
}

/**
 * Small, dependency-free file logger.
 *
 * - Writes synchronously so an uncaught-exception handler can flush a crash before termination.
 * - Rotates at roughly 1 MB, keeping the newest current file plus one previous file.
 * - Redacts absolute paths, content/file URIs and e-mail addresses before writing.
 * - Never stores track titles, artist names, folder paths or account identifiers.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private const val CURRENT = "auralis.log"
    private const val PREVIOUS = "auralis.log.1"

    private val lock = Any()
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val header = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([VDIWEF])/([^:]+): (.*)$")
    private val contentUri = Regex("(?:content|file)://\\S+")
    private val absolutePath = Regex("(?<![\\w/])(?:[A-Za-z]:)?/(?:[^\\s/]+/)+([^\\s/]+)")
    private val windowsPath = Regex("[A-Za-z]:\\\\\\S+")
    private val email = Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")

    private var directory: File? = null
    private var installedHandler = false

    fun init(context: Context) {
        synchronized(lock) {
            if (directory == null) {
                directory = File(context.filesDir, "logs").apply { mkdirs() }
            }
            if (installedHandler) return
            installedHandler = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                write('E', "Crash", "Uncaught exception on thread ${thread.name}", throwable)
                val handler = previous
                if (handler != null) {
                    handler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    fun v(tag: String, message: String, error: Throwable? = null) = write('V', tag, message, error)
    fun d(tag: String, message: String, error: Throwable? = null) = write('D', tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = write('I', tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    /** Newest first, so the viewer can render the most recent entry at the top. */
    fun entries(limit: Int = 1_500): List<LogEntry> {
        val dir = directory ?: return emptyList()
        val entries = ArrayList<LogEntry>()
        synchronized(lock) {
            listOf(File(dir, PREVIOUS), File(dir, CURRENT)).forEach { file ->
                if (file.exists()) runCatching { parse(file.readLines(), entries) }
            }
        }
        return entries.takeLast(limit).asReversed()
    }

    fun clear() {
        val dir = directory ?: return
        synchronized(lock) {
            runCatching { File(dir, CURRENT).delete() }
            runCatching { File(dir, PREVIOUS).delete() }
        }
    }

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val dir = directory ?: return
        val safeMessage = redact(message)
        val safeStack = error?.stackTraceToString()?.let(::redact)
        val line = buildString {
            append(dateFormat.get()!!.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(safeMessage)
            if (!safeStack.isNullOrBlank()) append('\n').append(safeStack.trimEnd())
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                val file = File(dir, CURRENT)
                if (file.length() + line.length > MAX_BYTES) rotate(dir)
                file.appendText(line)
            }
        }
    }

    private fun rotate(dir: File) {
        val current = File(dir, CURRENT)
        val previous = File(dir, PREVIOUS)
        if (current.exists()) {
            runCatching { previous.delete() }
            runCatching { current.renameTo(previous) }
        }
    }

    private fun parse(lines: List<String>, into: MutableList<LogEntry>) {
        var timestamp: String? = null
        var level = 'I'
        var tag = ""
        var message = ""
        val stack = StringBuilder()
        fun flush() {
            if (timestamp != null) {
                into += LogEntry(
                    timestamp = timestamp!!,
                    level = level,
                    tag = tag,
                    message = message,
                    stackTrace = stack.toString().trim().ifBlank { null },
                )
            }
            stack.setLength(0)
        }
        for (line in lines) {
            val match = header.matchEntire(line)
            if (match != null) {
                flush()
                timestamp = match.groupValues[1]
                level = match.groupValues[2].firstOrNull() ?: 'I'
                tag = match.groupValues[3]
                message = match.groupValues[4]
            } else if (timestamp != null) {
                stack.append(line).append('\n')
            }
        }
        flush()
    }

    private fun redact(text: String): String = text
        .replace(contentUri, "<uri>")
        .replace(absolutePath) { match -> match.groupValues[1] }
        .replace(windowsPath) { match ->
            match.value.substringAfterLast('\\').ifBlank { "<path>" }
        }
        .replace(email, "<email>")
}
