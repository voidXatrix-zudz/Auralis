package com.auralis.app.data.sources

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.auralis.app.AuralisApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Runs the incremental scan while the app process is not open.
 *
 * Two jobs are registered:
 * - a periodic safety net (every 6 hours), and
 * - a content-URI triggered one-shot that re-arms itself after each run, so a completed download or a
 *   file copied from a PC is picked up shortly after MediaStore publishes it.
 *
 * Both honour the "Auto-scan new songs" setting.
 */
class AutoScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AuralisApp ?: return Result.success()
        val container = app.container
        val enabled = runCatching { container.settings.settings.first().autoScanEnabled }.getOrDefault(true)
        if (!enabled) {
            Log.i(TAG, "worker skipped: auto-scan disabled")
            return Result.success()
        }
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching { container.scanner.scanIncremental() }
            .onSuccess { Log.i(TAG, "worker scan: ${it.tracks} new/changed in ${System.currentTimeMillis() - startedAt} ms") }
            .onFailure { Log.w(TAG, "worker scan failed", it) }
        // Re-arm the content-triggered job; it stays dormant until MediaStore changes again.
        scheduleContentTriggered(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return outcome.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val TAG = "AuralisAutoScan"
        const val PERIODIC_WORK = "auralis-auto-scan-periodic"
        const val CONTENT_WORK = "auralis-auto-scan-content"

        /** Registers the periodic safety net and the self-rearming content-triggered scan. */
        fun schedule(context: Context) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val periodic = PeriodicWorkRequestBuilder<AutoScanWorker>(6, TimeUnit.HOURS).build()
            runCatching {
                manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
            }.onFailure { Log.w(TAG, "Could not schedule periodic scan", it) }
            scheduleContentTriggered(context, ExistingWorkPolicy.KEEP)
        }

        private fun scheduleContentTriggered(context: Context, policy: ExistingWorkPolicy) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                .build()
            val request = OneTimeWorkRequestBuilder<AutoScanWorker>().setConstraints(constraints).build()
            runCatching {
                manager.enqueueUniqueWork(CONTENT_WORK, policy, request)
            }.onFailure { Log.w(TAG, "Could not schedule content-triggered scan", it) }
        }
    }
}
