package com.auralis.app.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Name of the Bluetooth audio output currently carrying media, or null when playback is routed
 * elsewhere. Updates live as devices connect and disconnect.
 *
 * The name comes from [AudioDeviceInfo.getProductName], which is the same label the system
 * Bluetooth settings show and does not require the BLUETOOTH_CONNECT permission.
 */
@Composable
fun rememberBluetoothAudioOutputName(): String? {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var name by remember(audioManager) {
        mutableStateOf(audioManager?.activeBluetoothOutputName())
    }
    DisposableEffect(audioManager) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                name = audioManager.activeBluetoothOutputName()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                name = audioManager.activeBluetoothOutputName()
            }
        }
        val registered = runCatching {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        name = audioManager.activeBluetoothOutputName()
        onDispose {
            if (registered) runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
        }
    }
    return name
}

private fun AudioManager.activeBluetoothOutputName(): String? = runCatching {
    val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // On API 33+ this list is ordered by preference, so the first entry is the route that
        // media is actually using. If it is not Bluetooth, no Bluetooth capsule should show.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        getAudioDevicesForAttributes(attributes).firstOrNull()
    } else {
        // Older releases do not expose the active route; the first connected Bluetooth sink is
        // the best available answer.
        getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.isBluetoothAudio() }
    }
    active
        ?.takeIf { it.isBluetoothAudio() }
        ?.productName
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()

private fun AudioDeviceInfo.isBluetoothAudio(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
