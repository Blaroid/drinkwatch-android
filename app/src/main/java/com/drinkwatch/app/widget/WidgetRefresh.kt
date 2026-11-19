package com.drinkwatch.app.widget

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.drinkwatch.app.App
import com.drinkwatch.app.R
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the home-screen widget warm and turns new server-side alerts into local
 * notifications. Runs on WorkManager (15 min floor, we ask for 30).
 */
object WidgetRefresh {

    private const val PERIODIC = "drinkwatch-widget"
    private const val IMMEDIATE = "drinkwatch-widget-now"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = ServiceLocator.prefs(applicationContext)
        val api = ServiceLocator.api(applicationContext)

        val fetch = runCatching {
            // Same path as the screens: a renamed tracked drink is pruned and
            // retried instead of failing every run forever.
            api.widgetItems(prefs.lat, prefs.lon, prefs.radiusKm, prefs.trackedSlugs, cache = true)
        }

        runCatching { PriceWidget().updateAll(applicationContext) }
        runCatching { pollNotifications(api, prefs) }

        // No cache and no network: let WorkManager retry with backoff instead of
        // silently showing nothing forever.
        val stale = prefs.widgetCache.isNullOrBlank() ||
            System.currentTimeMillis() - prefs.widgetCacheAt > java.util.concurrent.TimeUnit.HOURS.toMillis(6)
        if (fetch.isFailure && stale) Result.retry() else Result.success()
    }

    private suspend fun pollNotifications(api: Api, prefs: com.drinkwatch.app.data.Prefs) {
        if (prefs.token.isNullOrBlank()) return
        val notifications = runCatching { api.notifications() }.getOrNull() ?: return
        val newest = notifications.filter { it.id > prefs.lastSeenNotificationId && it.readAt == null }
        if (newest.isEmpty()) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, com.drinkwatch.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        var newestPosted = prefs.lastSeenNotificationId
        newest.take(3).forEach { item ->
            val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            if (runCatching { manager.notify(item.id, notification) }.isSuccess) {
                newestPosted = maxOf(newestPosted, item.id)
            }
        }
        // Only advance past what was actually surfaced; the rest is retried next run.
        prefs.lastSeenNotificationId = newestPosted
    }
}
