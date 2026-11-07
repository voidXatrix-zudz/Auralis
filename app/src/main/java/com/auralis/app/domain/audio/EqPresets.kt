package com.auralis.app.domain.audio

/** Ten-band equalizer layout and presets, identical to the AURALIS web reference. */
object EqPresets {
    val FREQS_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    const val CUSTOM = "Custom"

    private val PRESETS: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 4f),
        "Pop" to listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, -1f, -1f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
        "Loudness" to listOf(5f, 3f, 0f, 0f, -1f, 0f, -1f, -2f, 3f, 1f),
    )

    val names: List<String> get() = PRESETS.keys.toList()

    /** Gains in dB for [preset]; flat when the name is unknown (e.g. [CUSTOM]). */
    fun gains(preset: String): List<Float> = PRESETS[preset] ?: PRESETS.getValue("Flat")
}

/** Compact text form of the ten EQ gains, used by DataStore. */
object EqGainsCodec {
    fun encode(gains: List<Float>): String = gains.joinToString(",")

    /** Parses [text]; returns [fallback] unless it contains exactly one valid number per EQ band. */
    fun decode(text: String?, fallback: List<Float>): List<Float> {
        if (text.isNullOrBlank()) return fallback
        val parsed = text.split(",").map { it.trim().toFloatOrNull() }
        if (parsed.size != EqPresets.FREQS_HZ.size || parsed.any { it == null || it.isNaN() }) return fallback
        return parsed.map { it!!.coerceIn(-12f, 12f) }
    }
}

/** Compact text form of the per-device band levels (millibels) saved by the effects engine. */
object EqLevelsCodec {
    fun encode(levels: List<Int>): String = levels.joinToString(",")

    fun decode(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
