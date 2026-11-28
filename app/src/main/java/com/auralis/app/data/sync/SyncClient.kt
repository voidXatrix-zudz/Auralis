package com.auralis.app.data.sync

import com.auralis.app.BuildConfig
import com.auralis.app.data.auth.ClerkAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ConnectionPayload(val payload: String)

@Serializable
data class ConnectionsReply(val providers: Map<String, ConnectionRecord> = emptyMap())

@Serializable
data class ConnectionRecord(val payload: String, val updatedAt: Long = 0L)

data class SyncHttpResult(val status: Int, val body: String?) {
    val successful: Boolean get() = status in 200..299
}

/** Authenticated HTTPS access to the account connection Worker. */
class SyncClient(
    private val baseUrl: String = BuildConfig.SYNC_WORKER_URL,
    private val auth: ClerkAuthRepository,
    client: OkHttpClient,
) {
    private val http = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { require(baseUrl.startsWith("https://")) }

    suspend fun fetchConnections(expectedUserId: String? = auth.currentUserId()): SyncHttpResult? =
        request("GET", "/connections", expectedUserId = expectedUserId)

    suspend fun saveConnection(provider: String, payload: String, expectedUserId: String? = auth.currentUserId()): SyncHttpResult? =
        request("PUT", "/connections/$provider", json.encodeToString(ConnectionPayload.serializer(), ConnectionPayload(payload)), expectedUserId)

    suspend fun deleteConnection(provider: String, expectedUserId: String? = auth.currentUserId()): SyncHttpResult? =
        request("DELETE", "/connections/$provider", expectedUserId = expectedUserId)

    fun decodeConnections(body: String): ConnectionsReply? =
        runCatching { json.decodeFromString(ConnectionsReply.serializer(), body) }.getOrNull()

    private suspend fun request(method: String, path: String, body: String? = null, expectedUserId: String? = null): SyncHttpResult? =
        withContext(Dispatchers.IO) {
            try {
                var token = auth.freshSessionToken(expectedUserId) ?: return@withContext null
                var response = execute(method, path, body, token)
                if (response?.status == 401) {
                    token = auth.freshSessionToken(expectedUserId) ?: return@withContext response
                    response = execute(method, path, body, token)
                }
                response
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }

    private fun execute(method: String, path: String, body: String?, token: String): SyncHttpResult? {
        val requestBody = body?.toRequestBody(JSON)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .apply { when (method) {
                "GET" -> get()
                "PUT" -> put(requestBody ?: "".toRequestBody(JSON))
                "DELETE" -> delete()
            } }
            .build()
        return http.newCall(request).execute().use { response ->
            SyncHttpResult(response.code, response.body?.string())
        }
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
