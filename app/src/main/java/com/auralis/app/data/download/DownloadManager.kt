package com.auralis.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auralis.app.AppContainer
import com.auralis.app.data.db.DownloadEntity
import com.auralis.app.data.youtube.YOUTUBE_TRACK_PREFIX
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Process-wide container handle so the download worker can reach the app graph offline. */
object DownloadGraph {
    @Volatile
    var container: AppContainer? = null
}

/** Enqueues offline downloads as WorkManager jobs (max two stream at once). */
class DownloadManager(private val context: Context) {
    fun enqueue(track: Track) {
        // Never let a bad WorkManager state take the caller (UI thread) down with it.
        runCatching {
            ensureChannel(context)
            val videoId = track.remoteId ?: track.id.removePrefix(YOUTUBE_TRACK_PREFIX)
            if (videoId.isBlank() || DownloadGraph.container?.database?.downloadDao() == null) return
            val data = workDataOf(
                KEY_VIDEO_ID to videoId,
                KEY_TITLE to track.title,
                KEY_ARTIST to track.artist,
                KEY_ALBUM to track.album,
                KEY_DURATION to track.durationMs,
                KEY_THUMB to track.artworkUrl,
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(videoId), ExistingWorkPolicy.KEEP, request)
        }.onFailure { AppLog.e("Downloads", "Could not enqueue download", it) }
    }

    fun cancel(videoId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(videoId)) }
            .onFailure { AppLog.e("Downloads", "Could not cancel download", it) }
    }

    /** Deletes a finished download: file, sidecar cover and the Room row. */
    suspend fun delete(videoId: String) {
        val container = DownloadGraph.container ?: return
        val entry = container.database.downloadDao().get(videoId) ?: return
        withContext(Dispatchers.IO) {
            runCatching { File(entry.filePath).delete() }
            entry.artPath?.let { runCatching { File(it).delete() } }
        }
        container.database.downloadDao().delete(videoId)
    }

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_DURATION = "durationMs"
        const val KEY_THUMB = "thumb"
        const val CHANNEL_ID = "auralis_downloads"
        const val CANCEL_ACTION = "com.auralis.app.action.CANCEL_DOWNLOAD"
        const val NOTIFICATION_ID = 4201
        const val MAX_ATTEMPTS = 2
        fun workName(videoId: String) = "auralis_download_$videoId"
        fun notificationId(videoId: String) = NOTIFICATION_ID + (videoId.hashCode() and 0x7FF)

        /** Idempotent: safe to call before every notify, and once at app start. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

/** Cancels one in-flight download from the progress notification's "Cancel" action. */
class DownloadCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val videoId = intent.getStringExtra(DownloadManager.KEY_VIDEO_ID) ?: return
        DownloadManager(context).cancel(videoId)
    }
}

/**
 * Streams one YouTube song into the app's music folder. Two downloads may run at once; a failed
 * attempt is retried once, then reported through the existing Logs screen.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    // Reuse the process-wide client (shared connection pool) with a fallback for the rare case the
    // graph has not been published yet.
    private val fallbackHttp by lazy { OkHttpClient() }
    private val http: OkHttpClient get() = DownloadGraph.container?.playbackHttp ?: fallbackHttp

    override suspend fun doWork(): Result = try {
        slots.withPermit {
        val videoId = inputData.getString(DownloadManager.KEY_VIDEO_ID) ?: return@withPermit Result.failure()
        val container = DownloadGraph.container ?: return@withPermit Result.failure()
        val dao = container.database.downloadDao()
        if (dao.exists(videoId)) return@withPermit Result.success()

        val title = inputData.getString(DownloadManager.KEY_TITLE).orEmpty()
        val artist = inputData.getString(DownloadManager.KEY_ARTIST).orEmpty()
        val album = inputData.getString(DownloadManager.KEY_ALBUM).orEmpty()
        val duration = inputData.getLong(DownloadManager.KEY_DURATION, 0L)
        val thumb = inputData.getString(DownloadManager.KEY_THUMB)

        return@withPermit try {
            runCatching { setForeground(foreground(title, videoId, 0f)) }
            val resolved = container.youtube.resolveBestAudio(videoId)
            val dir = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AURALIS").apply { mkdirs() }
            val base = sanitize(if (artist.isBlank()) title else "$artist - $title")
            val target = uniqueFile(dir, base, resolved.extension)

            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(resolved.url).header("User-Agent", UA).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Empty audio response")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) runCatching {
                                    setProgress(workDataOf("p" to (copied.toFloat() / total)))
                                    setForegroundAsync(foreground(title, videoId, copied.toFloat() / total)).get()
                                }
                            }
                        }
                    }
                }
            }

            val art = thumb?.takeIf { it.isNotBlank() }?.let { url -> downloadArt(dir, base, url) }
            dao.upsert(
                DownloadEntity(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    filePath = target.absolutePath,
                    artPath = art,
                    format = resolved.format,
                    sizeBytes = target.length(),
                    downloadedAt = System.currentTimeMillis(),
                ),
            )
            runCatching { notifyDone(title, videoId) }
            Result.success()
        } catch (error: Throwable) {
            AppLog.e("Downloads", "Download failed for $videoId", error)
            if (runAttemptCount + 1 < DownloadManager.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
    } catch (error: Throwable) {
        // Never let setup (DB, WorkManager, semaphore) crash the worker process.
        AppLog.e("Downloads", "Download worker failed", error)
        Result.failure()
    }

    private fun downloadArt(dir: File, base: String, url: String): String? = runCatching {
        val file = uniqueFile(dir, base, "jpg")
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { response ->
            val body = response.body ?: return@runCatching null
            file.outputStream().use { body.byteStream().copyTo(it) }
        }
        file.absolutePath
    }.getOrNull()

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var index = 1
        while (candidate.exists()) candidate = File(dir, "$base ($index).$ext").also { index++ }
        return candidate
    }

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "download" }

    private fun foreground(title: String, videoId: String, progress: Float): ForegroundInfo {
        DownloadManager.ensureChannel(applicationContext)
        val cancelIntent = android.app.PendingIntent.getBroadcast(
            applicationContext,
            videoId.hashCode(),
            android.content.Intent(applicationContext, DownloadCancelReceiver::class.java)
                .setAction(DownloadManager.CANCEL_ACTION)
                .putExtra(DownloadManager.KEY_VIDEO_ID, videoId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .apply { if (progress in 0f..1f) setProgress(100, (progress * 100).toInt(), false) }
            .build()
        val id = DownloadManager.notificationId(videoId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun notifyDone(title: String, videoId: String) {
        DownloadManager.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(DownloadManager.notificationId(videoId), notification) }
    }

    private companion object {
        val slots = Semaphore(2)
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
