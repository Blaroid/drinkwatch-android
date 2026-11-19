package com.drinkwatch.app.widget

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
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
import com.drinkwatch.app.data.AlertGate
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Notifications
import com.drinkwatch.app.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "WidgetWorker"

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

        // Same path as the screens: a renamed tracked drink is pruned and
        // retried instead of failing every run forever.
        runCatching {
            api.widgetItems(prefs.lat, prefs.lon, prefs.radiusKm, prefs.trackedSlugs, cache = true)
        }

        runCatching { PriceWidget().updateAll(applicationContext) }
        runCatching { pollNotifications(api, prefs) }

        // The cache timestamp only moves on a live answer, so a run that quietly fell
        // back to the copy on disk still counts as stale. While it is stale, ask for a
        // retry with backoff instead of keeping the widget on old prices forever; the
        // 6-hour window keeps a long outage from retrying every 30 minutes.
        val stale = prefs.widgetCache.isNullOrBlank() ||
            System.currentTimeMillis() - prefs.widgetCacheAt > TimeUnit.HOURS.toMillis(6)
        if (stale) Result.retry() else Result.success()
    }

    private suspend fun pollNotifications(api: Api, prefs: com.drinkwatch.app.data.Prefs) {
        if (prefs.token.isNullOrBlank()) return
        // Ask the system first: notify() reports nothing when it drops an alert, so a
        // blocked permission or muted channel would otherwise look like a delivery.
        val blocked = AlertGate.isBlocked(applicationContext)
        val notifications = runCatching { api.notifications() }.getOrNull() ?: return
        // The page is the newest PAGE_LIMIT alerts and cannot be walked back, so a page
        // that stops above the cursor means the alerts between them are not fetchable at
        // all. Say so instead of letting the run look like it caught up: everything
        // older than the window is behind the cursor from here on and stays unposted.
        val coverage = Notifications.coverage(notifications, prefs.lastSeenNotificationId)
        if (coverage is Notifications.Coverage.Truncated) {
            Log.w(
                TAG,
                "alert backlog deeper than one page: ids " + coverage.missingFrom + ".." +
                    (coverage.oldestVisible - 1) + " are outside the window",
            )
        }
        // Deliberately not filtered on readAt: the cursor is what decides delivery, so
        // reading an alert in the You tab can never suppress its notification.
        val pending = when (
            val delivery = Notifications.delivery(notifications, prefs.lastSeenNotificationId, blocked)
        ) {
            // Nothing owed, or the phone is dropping alerts: leave the cursor alone so the
            // batch is still pending once the user turns notifications back on.
            Notifications.Delivery.Idle, Notifications.Delivery.Blocked -> return
            is Notifications.Delivery.Post -> delivery.items
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, com.drinkwatch.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val posted = mutableSetOf<Int>()
        pending.forEach { item ->
            val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            if (runCatching { manager.notify(item.id, notification) }.isSuccess) posted += item.id
        }
        // Only advance past what was actually surfaced; the rest of the batch is retried
        // next run, oldest first.
        prefs.lastSeenNotificationId = Notifications.advancedCursor(
            pendingIds = pending.map { it.id },
            postedIds = posted,
            previous = prefs.lastSeenNotificationId,
        )
    }
}
