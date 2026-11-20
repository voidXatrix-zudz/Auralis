package com.auralis.app.data.youtube

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.auralis.app.AppContainer
import com.auralis.app.logging.AppLog

/** Process-wide container handle so the live-sync worker can reach the app graph on its own. */
object LiveSyncGraph {
    @Volatile
    var container: AppContainer? = null
}

/**
 * The low-frequency background tier for live sync (WorkManager, ≥15 min, network-constrained). It
 * performs one full reconciliation so an account changed while the app was closed is folded in the
 * next time the device lets background work run. Failures are logged, never retried in a tight loop.
 */
class LiveSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = LiveSyncGraph.container ?: return Result.success()
        return try {
            container.liveSync.runBackgroundSync()
            Result.success()
        } catch (error: Throwable) {
            AppLog.w("LiveSync", "Background sync failed", error)
            // A transient failure should not turn into a WorkManager retry storm; the periodic
            // schedule will run again. The manager's own backoff covers the in-process case.
            Result.success()
        }
    }
}
