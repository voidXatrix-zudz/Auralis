package com.auralis.app.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

data class VisualizerFrame(
    val waveform: FloatArray = FloatArray(0),
    val spectrum: FloatArray = FloatArray(0),
    val active: Boolean = false,
    val error: String? = null,
)

/**
 * Real hardware Visualizer attached to the current ExoPlayer audio session. Capture is only enabled
 * while a Visualizer screen is visible to avoid needless battery use. RECORD_AUDIO is required by
 * the platform Visualizer API on most devices; the UI requests it before enabling capture.
 */
object VisualizerEngine {
    private const val TAG = "AuralisVisualizer"
    private var visualizer: Visualizer? = null
    private var sessionId: Int = 0
    private var appContext: Context? = null
    private val _frame = MutableStateFlow(VisualizerFrame())
    val frame: StateFlow<VisualizerFrame> = _frame.asStateFlow()

    /** 0 = emit every capture; otherwise the maximum frames per second delivered to the UI. */
    private var frameRateCap: Int = 0
    private var lastEmitMs: Long = 0L

    /**
     * Throttles how often captured frames reach the UI. High mode passes 0 (uncapped); Medium 60,
     * Low 30. The capture listener keeps running, but the recomposition rate follows the profile.
     */
    @Synchronized
    fun setFrameRateCap(fps: Int) {
        frameRateCap = fps.coerceAtLeast(0)
    }

    private fun emit(frame: VisualizerFrame) {
        if (frameRateCap > 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEmitMs < 1000L / frameRateCap) return
            lastEmitMs = now
        }
        _frame.value = frame
    }

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun setSession(id: Int) {
        sessionId = id
        if (visualizer != null && id > 0) {
            // A new audio session invalidates the old capture; recreate lazily on next screen use.
            releaseLocked()
            _frame.value = VisualizerFrame(error = null)
        }
    }

    @Synchronized
    fun start(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _frame.value = VisualizerFrame(error = "Microphone permission is required to read the audio visualizer on this device.")
            return false
        }
        if (sessionId <= 0) {
            _frame.value = VisualizerFrame(error = "Start a song first to attach the visualizer.")
            return false
        }
        if (visualizer != null) return true
        appContext = context.applicationContext
        return try {
            val sizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = sizeRange.getOrNull(1)?.toInt()?.coerceIn(128, 1024) ?: 1024
            val instance = Visualizer(sessionId)
            instance.captureSize = captureSize
            instance.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        val values = waveform?.let { bytes ->
                            FloatArray(bytes.size) { index -> bytes[index].toInt() / 128f }
                        } ?: FloatArray(0)
                        emit(_frame.value.copy(waveform = values, active = true, error = null))
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        val values = fft?.let { bytes ->
                            FloatArray(bytes.size / 2) { index ->
                                val real = bytes[index * 2].toInt()
                                val imaginary = bytes[index * 2 + 1].toInt()
                                val magnitude = kotlin.math.sqrt((real * real + imaginary * imaginary).toDouble()).toFloat() / 128f
                                (max(0f, magnitude) / 8f).coerceIn(0f, 1f)
                            }
                        } ?: FloatArray(0)
                        emit(_frame.value.copy(spectrum = values, active = true, error = null))
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                true,
            )
            instance.enabled = true
            visualizer = instance
            _frame.value = VisualizerFrame(active = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer could not be attached to session $sessionId", e)
            visualizer = null
            _frame.value = VisualizerFrame(error = "This device could not attach a visualizer to the active audio session.")
            false
        }
    }

    @Synchronized
    fun stop() {
        releaseLocked()
        _frame.value = VisualizerFrame()
    }

    private fun releaseLocked() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
    }
}
