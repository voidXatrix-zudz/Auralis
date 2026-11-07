package com.auralis.app

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.auralis.app.playback.PlaybackService
import com.auralis.app.ui.AppViewModel
import com.auralis.app.ui.AuralisRoot

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory((application as AuralisApp).container) }
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuralisRoot(viewModel) }
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
        (application as AuralisApp).container.autoScanner.onResume()
    }

    override fun onStop() {
        val future = controllerFuture
        controllerFuture = null
        (application as AuralisApp).container.playback.attach(null)
        future?.let { MediaController.releaseFuture(it) }
        super.onStop()
    }

    private companion object { const val TAG = "AuralisActivity" }
}
