package com.auralis.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.auralis.app.AuralisApp
import com.auralis.app.data.auth.ClerkAuthRepository
import com.auralis.app.data.youtube.SessionClearRules
import com.auralis.app.data.youtube.YouTubeAuth
import com.auralis.app.data.youtube.YouTubeAuthStore
import com.auralis.app.data.youtube.clearYouTubeWebSessionCookies
import com.auralis.app.logging.AppLog
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class YouTubeEnvelope(val version: Int, val ownerId: String, val data: YouTubeAuth)

/** Keeps the encrypted local YouTube session aligned with the signed-in Clerk account. */
class YouTubeConnectionSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val auth: ClerkAuthRepository,
    private val store: YouTubeAuthStore,
    private val client: SyncClient,
    private val liveSync: com.auralis.app.data.youtube.LiveSyncManager,
) {
    private val restoreMutex = Mutex()
    private val restoreResults = mutableMapOf<String, Boolean>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun start() {
        scope.launch {
            kotlinx.coroutines.flow.combine(auth.isInitialized, auth.user) { ready, user -> ready to user }
                .collect { (ready, user) ->
                    if (!ready) return@collect
                    if (user == null) {
                        restoreMutex.withLock { restoreResults.clear() }
                        if (store.ownerId() != null || store.snapshot() != null) clearForClerkSignOut()
                    } else {
                        restoreForUser(user.id)
                    }
                }
        }
        scope.launch {
            store.auth.drop(1).collect { session ->
                if (session != null) scheduleUpload()
            }
        }
    }

    suspend fun restoreForUser(userId: String): Boolean = restoreMutex.withLock {
        restoreResults[userId]?.let { return@withLock it }
        val restored = restoreForUserOnce(userId)
        if (restored) restoreResults[userId] = true
        restored
    }

    private suspend fun restoreForUserOnce(userId: String): Boolean {
        if (store.pendingDeleteOwnerHash() == hash(userId)) {
            performDelete()
            return true
        }
        val local = store.snapshot()
        if (local != null && store.ownerId() == userId) {
            val response = client.fetchConnections(userId)
            if (response?.successful == true) {
                val connections = response.body?.let(client::decodeConnections)
                if (connections?.providers?.containsKey("youtube") == false) scheduleUpload()
            }
            return true
        }
        if (local != null || store.hasStoredBlob() || store.ownerId() != null) {
            store.clear(SessionClearRules.USER_MISMATCH)
            withContext(Dispatchers.Main) { clearYouTubeWebSessionCookies() }
            liveSync.onYouTubeAccountChanged()
        }
        val response = client.fetchConnections(userId) ?: return false
        if (!response.successful) {
            if (response.status >= 500) logFailure(response.status)
            return false
        }
        val payload = response.body?.let(client::decodeConnections)?.providers?.get("youtube")?.payload
            ?: return false
        val envelope = runCatching { json.decodeFromString(YouTubeEnvelope.serializer(), payload) }.getOrNull()
            ?: return false
        if (envelope.version != 1 || envelope.ownerId != userId || envelope.data.cookie.isBlank()) return false
        if (auth.currentUserId() != userId) return false
        store.bindOwner(userId)
        store.recordUploaded(hash(payload))
        liveSync.onYouTubeAccountChanged()
        store.save(envelope.data)
        AppLog.i(TAG, "YouTube login restored")
        return true
    }

    fun onYouTubeLogin(ownerId: String?) {
        if (ownerId.isNullOrBlank()) return
        store.bindOwner(ownerId)
        store.setPendingDeleteOwnerHash(null)
        scheduleUpload()
    }

    fun disconnect(ownerId: String?) {
        if (ownerId.isNullOrBlank()) return
        val ownerHash = hash(ownerId)
        store.setPendingDeleteOwnerHash(ownerHash)
        scope.launch {
            val result = if (auth.currentUserId() == ownerId) client.deleteConnection("youtube", ownerId) else null
            if (result?.successful == true) {
                store.setPendingDeleteOwnerHash(null)
            } else {
                if (result != null && result.status >= 500) logFailure(result.status)
                enqueueDelete()
            }
        }
    }

    suspend fun performUpload(): Boolean {
        val owner = store.ownerId() ?: return true
        if (auth.currentUserId() != owner) return true
        val data = store.snapshot() ?: return true
        val payload = json.encodeToString(YouTubeEnvelope.serializer(), YouTubeEnvelope(1, owner, data))
        val digest = hash(payload)
        if (digest == store.lastUploadedHash()) return true
        val lastAttempt = store.lastUploadAttemptAt()
        val remaining = UPLOAD_INTERVAL_MS - (System.currentTimeMillis() - lastAttempt)
        if (lastAttempt > 0 && remaining > 0) {
            enqueueUpload(remaining)
            return true
        }
        store.recordUploadAttempt(System.currentTimeMillis())
        val result = client.saveConnection("youtube", payload, owner)
        return when {
            result?.successful == true -> {
                store.recordUploaded(digest)
                AppLog.i(TAG, "YouTube login saved")
                true
            }
            result == null || result.status >= 500 || result.status == 429 -> {
                if (result != null) logFailure(result.status) else AppLog.w(TAG, "YouTube sync failed")
                false
            }
            else -> {
                logFailure(result.status)
                true
            }
        }
    }

    suspend fun performDelete(): Boolean {
        val ownerHash = store.pendingDeleteOwnerHash() ?: return true
        val owner = auth.currentUserId() ?: return true
        if (hash(owner) != ownerHash) return true
        val result = client.deleteConnection("youtube", owner)
        return when {
            result?.successful == true -> {
                store.setPendingDeleteOwnerHash(null)
                true
            }
            result == null || result.status >= 500 || result.status == 429 -> false
            else -> { logFailure(result.status); true }
        }
    }

    private suspend fun clearForClerkSignOut() {
        store.clear(SessionClearRules.CLERK_SIGN_OUT)
        withContext(Dispatchers.Main) { clearYouTubeWebSessionCookies() }
        liveSync.onYouTubeAccountChanged()
    }

    private fun scheduleUpload() {
        val owner = store.ownerId() ?: return
        if (auth.currentUserId() != owner || store.snapshot() == null) return
        val wait = (UPLOAD_INTERVAL_MS - (System.currentTimeMillis() - store.lastUploadAttemptAt()))
            .coerceAtLeast(0L)
        enqueueUpload(wait)
    }

    private fun enqueueUpload(delayMs: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<YouTubeConnectionWorker>()
            .setInputData(androidx.work.workDataOf("operation" to "upload"))
            .setConstraints(constraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueueDelete() {
        val request = OneTimeWorkRequestBuilder<YouTubeConnectionWorker>()
            .setInputData(androidx.work.workDataOf("operation" to "delete"))
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(DELETE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun logFailure(status: Int) = AppLog.w(TAG, "YouTube sync failed (HTTP $status)")

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AccountSync"
        const val UPLOAD_INTERVAL_MS = 60_000L
        const val UPLOAD_WORK = "youtube-connection-upload"
        const val DELETE_WORK = "youtube-connection-delete"
    }
}

class YouTubeConnectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AuralisApp ?: return Result.failure()
        val sync = app.container.youtubeConnectionSync
        return try {
            val succeeded = when (inputData.getString("operation")) {
                "delete" -> sync.performDelete()
                else -> sync.performUpload()
            }
            if (succeeded) Result.success() else Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
