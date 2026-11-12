package com.auralis.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.StreamQuality

/**
 * Maps the current connection and the user's quality settings to an effective [StreamQuality].
 *
 * Wi-Fi/Ethernet use [AppSettings.wifiStreamQuality]; cellular and metered Wi-Fi use
 * [AppSettings.mobileStreamQuality]. "Data saver" forces [StreamQuality.LOW] on cellular and asks
 * callers to skip prefetch there. Local files never consult this.
 */
object NetworkQuality {
    /** True when the active connection is cellular or a metered Wi-Fi network. */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        val unmeteredWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (unmeteredWifi) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    /** The quality to stream at right now, honouring Data saver. */
    fun effective(context: Context, settings: AppSettings): StreamQuality {
        val metered = isMetered(context)
        if (metered && settings.dataSaverEnabled) return StreamQuality.LOW
        return if (metered) settings.mobileStreamQuality else settings.wifiStreamQuality
    }

    /** Whether prefetching the next song is allowed on the current connection. */
    fun allowPrefetch(context: Context, settings: AppSettings): Boolean =
        !(settings.dataSaverEnabled && isMetered(context))
}
