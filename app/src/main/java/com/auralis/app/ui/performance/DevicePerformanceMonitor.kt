package com.auralis.app.ui.performance

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-lifetime source of [DeviceConditions]. Listens to Battery Saver changes and, on API 29+,
 * thermal status changes, so Automatic can step down/up without polling.
 */
class DevicePerformanceMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager: PowerManager? =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _conditions = MutableStateFlow(readConditions())
    val conditions: StateFlow<DeviceConditions> = _conditions.asStateFlow()

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    init {
        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { refresh() }
            powerManager?.addThermalStatusListener(listener)
            thermalListener = listener
        }
    }

    fun refresh() {
        _conditions.value = readConditions()
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(powerSaveReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
        }
    }

    private fun readConditions(): DeviceConditions {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: 0
        } else {
            0
        }
        return DeviceConditions(
            isLowRamDevice = manager?.isLowRamDevice == true,
            totalMemBytes = memory.totalMem,
            availMemBytes = memory.availMem,
            memoryClassMb = manager?.memoryClass ?: 128,
            cores = Runtime.getRuntime().availableProcessors(),
            sdkInt = Build.VERSION.SDK_INT,
            batterySaver = powerManager?.isPowerSaveMode == true,
            thermalStatus = thermal,
        )
    }
}
