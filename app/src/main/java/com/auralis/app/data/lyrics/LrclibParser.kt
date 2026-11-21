package com.auralis.app.data.lyrics

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Pure parsing for LRCLIB responses, split out of [LrclibClient] so the response shapes can be
 * pinned with fixtures in unit tests.
 *
 * [org.json] is only a stub on the JVM, so the whole lyrics pipeline parses with
 * `kotlinx.serialization.json`, which is already a project dependency and works in unit tests too.
 * Every function returns a "no result" outcome for empty or malformed input and never throws.
 */
internal object LrclibParser {

    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    /** `/api/get` body to a hit, or `null` when the body is empty/malformed/has no lyrics. */
    fun parseGetBody(body: String?): LrclibResult.Found? {
        val json = body?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return null
        return fromObject(json)
    }

    /**
     * `/api/search` body to its best hit: closest duration wins, a result carrying timestamps beats
     * a plain one. Empty, malformed or all-lyric-less bodies report [LrclibResult.NotFound].
     */
    fun parseSearchBody(body: String?, title: String, seconds: Int?): LrclibResult {
        val array = body?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonArray
            ?: return LrclibResult.NotFound
        var best: LrclibResult.Found? = null
        var bestScore = Int.MIN_VALUE
        for (element in array) {
            val json = element as? JsonObject ?: continue
            val found = fromObject(json) ?: continue
            var score = if (found.synced != null) 1_000 else 0
            if ((json.str("trackName") ?: "").equals(title, ignoreCase = true)) score += 200
            val duration = json.double("duration") ?: -1.0
            if (seconds != null && duration > 0) {
                score -= abs(duration - seconds).toInt().coerceAtMost(120)
            }
            if (score > bestScore) {
                bestScore = score
                best = found
            }
        }
        return best ?: LrclibResult.NotFound
    }

    private fun fromObject(json: JsonObject): LrclibResult.Found? {
        val synced = json.str("syncedLyrics")
        val plain = json.str("plainLyrics")
        return if (synced != null || plain != null) LrclibResult.Found(synced, plain) else null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
