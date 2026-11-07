package com.auralis.app.core

import com.auralis.app.domain.model.Track
import java.util.Locale

/** m:ss, or h:mm:ss for one hour and longer. Negative or invalid input renders as 0:00. */
fun formatDuration(ms: Long): String {
    val totalSec = if (ms < 0) 0L else ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** e.g. "FLAC · 24-bit · 96 kHz · 1411 kbps", using only the properties the file provides. */
fun Track.formatSummary(): String {
    val parts = mutableListOf<String>()
    val name = (codec ?: ext).uppercase()
    parts += if (name == "MPEG 1 LAYER 3") "MP3" else name
    bitDepth?.let { parts += "$it-bit" }
    sampleRateHz?.let {
        val khz = String.format(Locale.US, "%.1f", it / 1000.0).removeSuffix(".0")
        parts += "$khz kHz"
    }
    bitrateBps?.let { parts += "${Math.round(it / 1000.0)} kbps" }
    return parts.joinToString(" · ")
}
