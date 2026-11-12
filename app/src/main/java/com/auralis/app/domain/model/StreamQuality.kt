package com.auralis.app.domain.model

/** Which resolver stack the player prefers for YouTube streams (Part H/M). */
enum class StreamSourceMode { AUTO, NEWPIPE, INNERTUBE }

/**
 * A network streaming quality tier. [approxKbps] is the target used to pick the closest audio-only
 * stream at or below it; [BEST] means "highest available".
 */
enum class StreamQuality(val label: String, val approxKbps: Int) {
    LOW("Low", 50),
    NORMAL("Normal", 96),
    HIGH("High", 160),
    BEST("Best", Int.MAX_VALUE);

    companion object {
        fun fromIndex(index: Int): StreamQuality = entries.getOrElse(index) { NORMAL }
    }
}
