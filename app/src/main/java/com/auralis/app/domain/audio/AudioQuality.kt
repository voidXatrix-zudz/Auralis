package com.auralis.app.domain.audio

import com.auralis.app.domain.model.Track

/**
 * Quality badge rules. A badge is shown only when the file's own properties justify it:
 * LOSSLESS for lossless codecs, HI-RES only for lossless above 16-bit or above 44.1 kHz.
 */
object AudioQuality {
    private val LOSSLESS_CODECS = listOf("flac", "alac", "pcm", "wav", "aiff", "ape", "wavpack", "lpcm")

    fun isLossless(lossless: Boolean, codec: String?): Boolean {
        if (lossless) return true
        val c = codec.orEmpty().lowercase()
        return LOSSLESS_CODECS.any { c.contains(it) }
    }

    fun isHiRes(lossless: Boolean, codec: String?, bitDepth: Int?, sampleRateHz: Int?): Boolean {
        if (!isLossless(lossless, codec)) return false
        return (bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 44_100
    }
}

val Track.isLossless: Boolean get() = AudioQuality.isLossless(lossless, codec)

val Track.isHiRes: Boolean get() = AudioQuality.isHiRes(lossless, codec, bitDepth, sampleRateHz)
