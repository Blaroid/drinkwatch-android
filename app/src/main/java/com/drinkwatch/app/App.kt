package com.drinkwatch.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.drinkwatch.app.widget.WidgetRefresh

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        WidgetRefresh.schedule(this)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Price alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "A tracked drink dropped under your threshold" }
        val background = NotificationChannel(
            CHANNEL_BACKGROUND,
            "Background updates",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(alerts)
        manager.createNotificationChannel(background)
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_BACKGROUND = "background"
    }
}
