package com.auralis.app.data.youtube

import com.auralis.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One audio-only stream from an InnerTube `player` response. */
data class InnerTubeAudioFormat(
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val url: String,
) {
    /** True for the Opus anchors; false for AAC/M4A. */
    val isOpus: Boolean get() = mimeType.contains("opus", ignoreCase = true) ||
        itag == 249 || itag == 250 || itag == 251
}

/**
 * Keyless InnerTube `player` resolver. It is the fallback used when NewPipeExtractor cannot run
 * (e.g. it throws a [LinkageError] on an old WebView/runtime) and, from Part M, one of the racing
 * stream sources.
 *
 * Several clients are tried in order — ANDROID_VR, IOS, then ANDROID_MUSIC — because YouTube
 * alternates which clients hand out direct (unciphered) audio URLs. A response whose formats only
 * carry a `signatureCipher` is skipped: decoding it needs the player JS, which NewPipe does.
 */
class InnerTubePlayer(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Direct playable audio URLs for [videoId], best-effort; empty when none could be read. */
    suspend fun audioFormats(videoId: String): List<InnerTubeAudioFormat> = withContext(Dispatchers.IO) {
        for (client in CLIENTS) {
            val formats = runCatching { request(videoId, client) }
                .onFailure { AppLog.d(TAG, "InnerTube player (${client.name}) failed: ${it.message}") }
                .getOrDefault(emptyList())
            if (formats.isNotEmpty()) {
                AppLog.d(TAG, "InnerTube player (${client.name}) yielded ${formats.size} audio formats")
                return@withContext formats
            }
        }
        emptyList()
    }

    private fun request(videoId: String, client: PlayerClient): List<InnerTubeAudioFormat> {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    client.deviceModel?.let { put("deviceModel", it) }
                    client.androidSdk?.let { put("androidSdkVersion", it) }
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.nameId)
            .header("X-YouTube-Client-Version", client.version)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return emptyList()
            return parseFormats(root)
        }
    }

    private fun parseFormats(root: JsonObject): List<InnerTubeAudioFormat> {
        val streaming = root["streamingData"]?.jsonObject() ?: return emptyList()
        val out = ArrayList<InnerTubeAudioFormat>()
        for (key in listOf("adaptiveFormats", "formats")) {
            val array = streaming[key]?.jsonArray() ?: continue
            for (entry in array) {
                val format = entry.jsonObject() ?: continue
                val mimeType = format["mimeType"]?.jsonString() ?: continue
                if (!mimeType.startsWith("audio/")) continue
                // A ciphered stream has no direct URL; skip it rather than hand Media3 garbage.
                val url = format["url"]?.jsonString()?.takeIf { it.isNotBlank() } ?: continue
                val itag = format["itag"]?.jsonString()?.toIntOrNull() ?: continue
                val bitrate = format["bitrate"]?.jsonString()?.toIntOrNull() ?: 0
                out += InnerTubeAudioFormat(itag = itag, mimeType = mimeType, bitrate = bitrate, url = url)
            }
        }
        return out
    }

    private fun JsonElement?.jsonObject(): JsonObject? = this as? JsonObject
    private fun JsonElement?.jsonArray(): JsonArray? = this as? JsonArray
    private fun JsonElement?.jsonString(): String? = (this as? JsonPrimitive)?.content

    private data class PlayerClient(
        val name: String,
        val nameId: String,
        val version: String,
        val userAgent: String,
        val deviceModel: String? = null,
        val androidSdk: Int? = null,
    )

    private companion object {
        const val TAG = "YouTube"
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val CLIENTS = listOf(
            PlayerClient(
                name = "ANDROID_VR",
                nameId = "28",
                version = "1.60.19",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; " +
                    "en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip",
                deviceModel = "Quest 3",
                androidSdk = 32,
            ),
            PlayerClient(
                name = "IOS",
                nameId = "5",
                version = "19.45.4",
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
                deviceModel = "iPhone16,2",
            ),
            PlayerClient(
                name = "ANDROID_MUSIC",
                nameId = "21",
                version = "7.27.52",
                userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip",
                androidSdk = 34,
            ),
        )
    }
}
