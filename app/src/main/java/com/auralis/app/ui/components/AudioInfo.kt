package com.auralis.app.ui.components

import com.auralis.app.domain.audio.isHiRes
import com.auralis.app.domain.audio.isLossless
import com.auralis.app.domain.model.Track

/** Text for the audio-quality capsule, or null when the file has nothing worth showing. */
data class QualityBadgeContent(val tag: String?, val details: String)

fun Track.qualityBadgeContent(): QualityBadgeContent? {
    val parts = listOfNotNull(
        codec?.takeIf { it.isNotBlank() }?.uppercase(),
        bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
        sampleRateHz?.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000f) },
    )
    val tag = when {
        isHiRes -> "HI-RES"
        isLossless -> "LOSSLESS"
        else -> null
    }
    if (tag == null && parts.isEmpty()) return null
    return QualityBadgeContent(tag, parts.joinToString(" · "))
}
