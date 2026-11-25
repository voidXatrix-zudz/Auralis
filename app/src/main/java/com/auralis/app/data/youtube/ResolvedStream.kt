package com.auralis.app.data.youtube

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The label shown when nothing about the audio format could be determined. */
const val AUDIO_UNKNOWN = "AUDIO"

/**
 * One resolved, directly playable audio stream: the signed URL together with the audio format the
 * winning resolver actually selected.
 *
 * This is authoritative — unlike [Track.ext]/[Track.mime], which only ever carry a placeholder for
 * streamed songs. [format] is a short uppercase label ("WEBM", "AAC", "FLAC", "MP3", "OPUS", or
 * [AUDIO_UNKNOWN]); [mimeType] and [codec] are kept separate and verbatim so the Audio Info sheet can
 * show the real details instead of a guess.
 */
data class ResolvedStream(
    val url: String,
    val format: String,
    val mimeType: String? = null,
    val codec: String? = null,
    val extension: String? = null,
    /** Stream bitrate in bits per second, or null when the provider did not report one. */
    val bitrate: Int? = null,
    /** Sample bit depth when the provider reports one (lossless, e.g. FLAC); null otherwise. */
    val bitDepth: Int? = null,
    /** Which resolver produced this stream; drives retry routing and diagnostics. */
    val resolver: ResolverKind? = null,
) {
    /** "WEBM · OPUS", "AAC · 128 kbps", or just "AUDIO" when nothing else is known. */
    val displayLabel: String
        get() {
            val parts = buildList {
                add(format.takeIf { it.isNotBlank() } ?: AUDIO_UNKNOWN)
                shortCodecLabel(codec, mimeType)?.takeIf { it != format }?.let(::add)
                bitrate?.takeIf { it > 0 }?.let { add(formatBitrateLabel(it)) }
            }
            return parts.joinToString(" · ")
        }

    /**
     * The Now Playing timeline label: the real codec (not the container) plus its most useful detail
     * — bit rate for a lossy stream ("OPUS · 160 kbps", "AAC · 160 kbps"), or bit depth for a
     * lossless one ("FLAC · 24-bit"). Falls back to the container only when nothing names the codec.
     */
    val nowPlayingLabel: String
        get() {
            val base = shortCodecLabel(codec, mimeType)
                ?: format.takeIf { it.isNotBlank() && it != AUDIO_UNKNOWN }
                ?: AUDIO_UNKNOWN
            val detail = when {
                bitDepth != null && bitDepth > 0 -> "$bitDepth-bit"
                bitrate != null && bitrate > 0 -> formatBitrateLabel(bitrate)
                else -> null
            }
            return if (detail != null) "$base · $detail" else base
        }
}

/**
 * Turns raw container/codec hints from any provider into one short, stable label.
 *
 * WebM is reported as WEBM even though YouTube's WebM audio almost always carries Opus, matching what
 * the provider actually served; Opus only becomes OPUS when it is not inside a WebM container. The
 * label set is intentionally small: WEBM, AAC, FLAC, MP3, OPUS, and [AUDIO_UNKNOWN] for anything the
 * hints cannot identify.
 */
fun normalizeAudioFormat(
    mimeType: String? = null,
    codec: String? = null,
    extension: String? = null,
    hint: String? = null,
): String {
    val haystack = listOfNotNull(mimeType, codec, extension, hint)
        .joinToString(" ")
        .lowercase(Locale.US)
    if (haystack.isBlank()) return AUDIO_UNKNOWN
    return when {
        "webm" in haystack -> "WEBM"
        "flac" in haystack -> "FLAC"
        "mp3" in haystack -> "MP3"
        "opus" in haystack -> "OPUS"
        "aac" in haystack || "mp4a" in haystack || "m4a" in haystack || "audio/mp4" in haystack -> "AAC"
        "mpeg" in haystack -> "MP3"
        else -> AUDIO_UNKNOWN
    }
}

/**
 * Short, human codec label for the Audio Info sheet ("opus" → "OPUS", "mp4a.40.2" → "AAC"). Falls
 * back to the uppercased codec string, or null when neither hint names a codec.
 */
fun shortCodecLabel(codec: String?, mimeType: String? = null): String? {
    val codecLc = codec.orEmpty().lowercase(Locale.US)
    val mimeLc = mimeType.orEmpty().lowercase(Locale.US)
    val both = "$codecLc $mimeLc"
    return when {
        both.isBlank() -> null
        "opus" in both -> "OPUS"
        "flac" in both -> "FLAC"
        "mp3" in both || "mpeg" in both -> "MP3"
        "vorbis" in both -> "VORBIS"
        "mp4a" in both || "aac" in both -> "AAC"
        codecLc.isNotBlank() -> codecLc.uppercase(Locale.US)
        else -> null
    }
}

/** "128 kbps" for the Audio Info header; the input is in bits per second. */
fun formatBitrateLabel(bps: Int): String =
    if (bps >= 1_000) "${(bps / 1_000.0).roundToInt()} kbps" else "$bps bps"

/**
 * Process-wide, session-only memory of the last resolved stream per video id.
 *
 * The resolver publishes here right after it wins a race (and on a cache hit); the playback state
 * reads the entry for the current song so the Now Playing label and Audio Info describe the stream
 * that is actually playing. Keying by video id means a track change never shows the previous song's
 * format: an unresolved song reads as null until its own entry appears.
 *
 * Bounded so a long shuffle session cannot grow it without limit; the oldest entries are dropped
 * first, which is harmless because the in-memory URL cache holds the playable stream independently.
 */
object ResolvedStreams {
    private const val MAX_ENTRIES = 32

    private val _streams = MutableStateFlow<Map<String, ResolvedStream>>(emptyMap())
    val streams: StateFlow<Map<String, ResolvedStream>> = _streams.asStateFlow()

    /** Records (or replaces) [videoId]'s resolved stream, keeping the map bounded. */
    fun publish(videoId: String, stream: ResolvedStream) {
        if (videoId.isBlank()) return
        _streams.update { current ->
            val next = LinkedHashMap<String, ResolvedStream>(current.size + 1)
            current.forEach { (key, value) -> if (key != videoId) next[key] = value }
            next[videoId] = stream
            while (next.size > MAX_ENTRIES) {
                next.remove(next.keys.first())
            }
            next
        }
    }

    /** The last resolved stream for [videoId], or null when it has not resolved yet. */
    fun current(videoId: String?): ResolvedStream? = videoId?.let { _streams.value[it] }
}
