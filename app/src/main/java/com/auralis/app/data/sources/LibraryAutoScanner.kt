package com.auralis.app.data.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.auralis.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the library in sync without manual rescans.
 *
 * - While the app process is alive, a debounced [ContentObserver] on the audio MediaStore triggers a
 *   lightweight fingerprint diff ([MusicScanner.scanIncremental]) instead of a full scan.
 * - [onResume] runs the same diff so files copied while the app was closed show up immediately.
 * - SAF folders cannot be observed reliably, so the same on-resume diff walks the folder listing.
 * - The worker (see `AutoScanWorker`) covers the app being fully closed.
 *
 * All work is serialized through [scanMutex]; concurrent triggers collapse into one scan.
 */
class LibraryAutoScanner(
    private val context: Context,
    private val scanner: MusicScanner,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())

    private val _completed = MutableSharedFlow<MusicScanner.ScanResult>(extraBufferCapacity = 4)
    /** Emits after each automatic scan, mostly for diagnostics/toasts if ever needed. */
    val completed: SharedFlow<MusicScanner.ScanResult> = _completed.asSharedFlow()

    @Volatile private var enabled = true
    @Volatile private var started = false
    private var registered = false
    private var debounceJob: Job? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
        override fun onChange(selfChange: Boolean) = schedule()
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.settings.collect { current ->
                val shouldRun = current.autoScanEnabled
                if (shouldRun != enabled) {
                    enabled = shouldRun
                    Log.i(TAG, "auto-scan ${if (shouldRun) "enabled" else "disabled"}")
                }
                if (shouldRun) registerObserver() else unregisterObserver()
            }
        }
        scanNow("startup")
    }

    /** Called from `MainActivity.onResume`; picks up files added while the app was backgrounded. */
    fun onResume() = schedule(delayMs = 400L)

    /** Immediately runs an incremental scan. Safe to call for an in-app download finishing. */
    fun scanNow(reason: String) {
        if (!enabled) return
        scope.launch {
            scanMutex.withLock {
                val startedAt = System.currentTimeMillis()
                runCatching { scanner.scanIncremental() }
                    .onSuccess { result ->
                        Log.i(TAG, "scan($reason) finished in ${System.currentTimeMillis() - startedAt} ms: ${result.tracks} new/changed, ${result.failedSources} failed")
                        if (result.tracks > 0) _completed.tryEmit(result)
                    }
                    .onFailure { Log.w(TAG, "scan($reason) failed", it) }
            }
        }
    }

    private fun schedule(delayMs: Long = DEBOUNCE_MS) {
        if (!enabled) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            scanNow("content-observer")
        }
    }

    private fun registerObserver() {
        if (registered) return
        if (!hasAudioPermission()) {
            Log.i(TAG, "auto-scan waiting for audio read permission")
            return
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            registered = true
            Log.i(TAG, "MediaStore observer registered")
        }.onFailure { Log.w(TAG, "Could not register MediaStore observer", it) }
    }

    private fun unregisterObserver() {
        if (!registered) return
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
        debounceJob?.cancel()
        Log.i(TAG, "MediaStore observer unregistered")
    }

    /** Called after a permission grant so the observer can attach without waiting for a settings change. */
    fun onPermissionGranted() {
        if (enabled) {
            registerObserver()
            scanNow("permission-granted")
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "AuralisAutoScan"
        const val DEBOUNCE_MS = 1_500L
    }
}
