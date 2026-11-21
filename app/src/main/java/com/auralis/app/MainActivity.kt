package com.auralis.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.auralis.app.logging.CrashHandler
import com.auralis.app.playback.PlaybackService
import com.auralis.app.ui.AppViewModel
import com.auralis.app.ui.AuralisRoot

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory((application as AuralisApp).container) }
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Downloads still run when this is denied (the notification just stays silent), so the result
    // is intentionally ignored rather than gating anything.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AuralisApp).container
        // Let Clerk hand OAuth (Google) results back to this Activity once it is attached. This is
        // non-essential: if it fails the UI must still draw, so the failure is recorded and skipped.
        runCatching { container.clerkAuth.attachActivity(this) }
            .onFailure {
                Log.w(TAG, "Could not attach Clerk activity", it)
                CrashHandler.writeStartupWarning(this, "clerk.attachActivity", it)
            }
        runCatching { requestNotificationPermissionIfNeeded() }
            .onFailure { Log.w(TAG, "Notification permission request failed", it) }
        // Open the local database here, once, so a broken migration or corrupt file shows the
        // recovery screen instead of crashing the launch deep inside the ViewModel.
        val databaseFailure = container.databaseFailureOrNull()
        if (databaseFailure != null) {
            Log.e(TAG, "Local database could not be opened", databaseFailure)
            CrashHandler.writeStartupWarning(this, "database.open", databaseFailure)
        }
        setContent {
            if (databaseFailure != null) {
                com.auralis.app.ui.DatabaseRecoveryScreen(container, databaseFailure)
            } else {
                AuralisRoot(viewModel)
            }
        }
    }

    /** Android 13+ needs an explicit grant before download notifications can be shown. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onStart() {
        super.onStart()
        if (controllerFuture != null) return
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future && !isFinishing && !isDestroyed) {
                runCatching { future.get() }
                    .onSuccess { (application as AuralisApp).container.playback.attach(it) }
                    .onFailure { Log.w(TAG, "Could not connect to playback service", it) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        runCatching { (application as AuralisApp).container.autoScanner.onResume() }
            .onFailure { Log.w(TAG, "autoScanner.onResume failed", it) }
        // Finish an update install after the user granted "install unknown apps", if needed.
        runCatching { viewModel.onAppResumed() }
            .onFailure { Log.w(TAG, "onAppResumed failed", it) }
    }

    override fun onStop() {
        runCatching { viewModel.onAppBackgrounded() }
            .onFailure { Log.w(TAG, "onAppBackgrounded failed", it) }
        val future = controllerFuture
        controllerFuture = null
        runCatching { (application as AuralisApp).container.playback.attach(null) }
            .onFailure { Log.w(TAG, "playback detach failed", it) }
        future?.let { MediaController.releaseFuture(it) }
        super.onStop()
    }

    private companion object { const val TAG = "AuralisActivity" }
}
