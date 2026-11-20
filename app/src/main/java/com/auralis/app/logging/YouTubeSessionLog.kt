package com.auralis.app.logging

import android.content.Context
import java.io.File

/**
 * The app-wide YouTube session log: the last 300 account events, persisted so it survives a process
 * death or an app restart. It is independent of [AppLog] (and therefore of artwork/radio noise), and
 * only [SessionLogSanitizer]-cleaned lines are written.
 */
object YouTubeSessionLog {
    private const val FILE_NAME = "youtube_session.log"

    @Volatile
    private var buffer: SessionLogBuffer? = null

    fun init(context: Context) {
        if (buffer != null) return
        buffer = SessionLogBuffer(File(File(context.filesDir, "logs"), FILE_NAME))
    }

    /** Never throws: diagnostics must not become a crash source. */
    fun record(tag: String, message: String) {
        runCatching { buffer?.record(tag, message) }
    }

    fun entries(limit: Int = 50): List<String> =
        runCatching { buffer?.lines(limit) }.getOrDefault(emptyList()).orEmpty()

    fun clear() {
        runCatching { buffer?.clear() }
    }
}