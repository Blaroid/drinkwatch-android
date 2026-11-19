package com.drinkwatch.app.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.drinkwatch.app.App

/**
 * Asks the system whether a price alert can actually reach the screen.
 *
 * `NotificationManager.notify()` returns `Unit` and does not throw when the notification
 * is dropped — POST_NOTIFICATIONS denied on Android 13+, or the "Price alerts" channel
 * muted — so delivery has to be established *before* posting rather than inferred from
 * the call returning. The worker and the You tab both read the same verdict from here.
 */
object AlertGate {

    /**
     * True when alerts would be dropped silently: no permission at all, or the channel
     * switched off. A blocked channel reports `IMPORTANCE_NONE`; null only means the
     * channel does not exist yet, which is not the same thing as muted.
     */
    fun isBlocked(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(App.CHANNEL_ALERTS)
        return Notifications.alertsBlocked(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelImportance = channel?.importance,
        )
    }
}
