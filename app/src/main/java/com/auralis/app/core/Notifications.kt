package com.auralis.app.core

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts a notification only when the app has been granted [Manifest.permission.POST_NOTIFICATIONS]
 * (a runtime permission from API 33). The explicit check means the call can never raise
 * SecurityException on a device where the user declined it, and satisfies lint's MissingPermission
 * check without a suppression.
 */
fun notifyIfPermitted(context: Context, id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    NotificationManagerCompat.from(context).notify(id, notification)
}
