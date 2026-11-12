package com.auralis.app.data.metadata

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.download.DownloadGraph
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Batch tag fixing: for every local song, look up metadata through the enabled providers and fill
 * only the fields that are still empty (unless the user enabled "Overwrite existing tags"). Runs as
 * a WorkManager job with a progress notification, one provider call per second to stay polite.
 */
class AutoTagManager(private val context: Context) {
    fun start() {
        runCatching {
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<AutoTagWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { AppLog.e("Metadata", "Could not start Auto-fix tags", it) }
    }

    companion object {
        const val WORK_NAME = "auralis_auto_tag"
        const val CHANNEL_ID = "auralis_tags"
        const val NOTIFICATION_ID = 4310

        /** Idempotent; safe to call before every notify. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Tag fixes", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

/** The batch worker. One track per second; a failure on one track never stops the run. */
class AutoTagWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val container = DownloadGraph.container ?: return Result.success()
        val editor = container.metadata
        val settings = container.settings.settings
        val current = settings.first()
        val overwrite = current.overwriteExistingTags

        val tracks = withContext(Dispatchers.IO) {
            container.database.trackDao().getAllNow().map { it.toDomain() }
        }
        if (tracks.isEmpty()) return Result.success()

        runCatching { setForeground(foreground("Preparing…", 0, tracks.size)) }
        var changed = 0
        tracks.forEachIndexed { index, track ->
            if (isStopped) return Result.success()
            runCatching { fixOne(container, editor, track, overwrite) }
                .onSuccess { if (it) changed++ }
                .onFailure { AppLog.w("Metadata", "Auto-fix skipped ${track.title}", it) }
            runCatching { setForeground(foreground(track.title, index + 1, tracks.size)) }
            // One lookup per second so we never hammer free providers.
            delay(TimeUnit.SECONDS.toMillis(1))
        }
        notifyDone(changed)
        Result.success()
    } catch (error: Throwable) {
        AppLog.e("Metadata", "Auto-fix tags failed", error)
        Result.failure()
    }

    /** Returns true when the file was rewritten with new data. */
    private suspend fun fixOne(
        container: com.auralis.app.AppContainer,
        editor: MetadataEditor,
        track: Track,
        overwrite: Boolean,
    ): Boolean {
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return false
        val read = runCatching { editor.read(uri, track.displayName) }.getOrNull() ?: return false
        if (read.format == TagFormat.UNSUPPORTED) return false
        val existing = read.tag

        val candidates = container.metadataRepository.search(
            title = track.title.ifBlank { track.displayName.substringBeforeLast('.') },
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            durationMs = track.durationMs.takeIf { it > 0 },
        )
        val best = candidates.firstOrNull() ?: return false

        val merged = TagData(
            title = pick(existing.title, best.title, overwrite),
            artist = pick(existing.artist, best.artists, overwrite),
            album = pick(existing.album, best.album, overwrite),
            albumArtist = pick(existing.albumArtist, best.albumArtist.ifBlank { best.artists }, overwrite),
            genre = pick(existing.genre, best.genre, overwrite),
            year = existing.year ?: best.year,
            trackNumber = existing.trackNumber ?: best.trackNumber,
            discNumber = existing.discNumber,
            composer = existing.composer,
            artwork = existing.artwork,
            removeArtwork = false,
        )
        // Fill missing artwork from the best candidate (kept out of the file unless it was empty).
        val withArt = if (existing.artwork == null && !best.coverUrl.isNullOrBlank()) {
            downloadCover(best.coverUrl)?.let { merged.copy(artwork = it) } ?: merged
        } else {
            merged
        }

        if (withArt == existing) return false
        editor.write(uri, track.displayName, withArt)
        return true
    }

    private suspend fun downloadCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.getInputStream().use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= 12 * 1024 * 1024 }
    }

    private fun pick(existing: String, candidate: String, overwrite: Boolean): String = when {
        candidate.isBlank() -> existing
        overwrite -> candidate
        existing.isBlank() -> candidate
        else -> existing
    }

    private fun foreground(title: String, done: Int, total: Int): ForegroundInfo {
        AutoTagManager.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, AutoTagManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Fixing tags")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AutoTagManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AutoTagManager.NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(changed: Int) {
        runCatching {
            AutoTagManager.ensureChannel(applicationContext)
            val notification = NotificationCompat.Builder(applicationContext, AutoTagManager.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Tags fixed")
                .setContentText(if (changed == 0) "Nothing to change" else "$changed songs updated")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(AutoTagManager.NOTIFICATION_ID, notification)
        }
    }
}
