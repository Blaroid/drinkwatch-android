package com.drinkwatch.app.data

/**
 * Push cursor rules for price alerts.
 *
 * Two pieces of state look similar but mean different things: [Prefs.lastSeenNotificationId]
 * is the push cursor and is the only thing that decides what still needs a phone
 * notification, while `read_at` is the server-side "seen in the You tab" flag. The worker
 * must not consult `read_at`, or an in-app read can swallow the push.
 *
 * Kept pure so the ordering and the cursor maths can be unit-tested without Android.
 */
object Notifications {

    /** Three a run: enough to catch up without spraying a whole backlog at once. */
    const val MAX_PER_RUN = 3

    /**
     * Alerts still owed a notification: everything above the cursor, lowest id first.
     *
     * Ascending order is what makes the [limit] safe. The API returns newest-first, so
     * taking the newest rows and advancing the cursor to the highest of them would
     * strand every older unread row below the cursor forever.
     */
    fun pending(all: List<AppNotification>, cursor: Int, limit: Int = MAX_PER_RUN): List<AppNotification> =
        all.filter { it.id > cursor }.sortedBy { it.id }.take(limit)

    /** What one poll of the alert feed should do. */
    sealed interface Delivery {
        /** Nothing above the cursor: leave both the cursor and the phone alone. */
        data object Idle : Delivery

        /**
         * The system is holding alerts back, so this batch cannot be delivered now.
         * The cursor must not move: these alerts were never shown, and the next run
         * retries the same ones instead of walking past them forever.
         */
        data object Blocked : Delivery

        /** Post [items] oldest-first, then advance the cursor past whatever posted. */
        data class Post(val items: List<AppNotification>) : Delivery
    }

    /**
     * Decide a poll from the fetched feed, the cursor and the system's verdict.
     *
     * [blocked] has to be passed in rather than asked here (see [alertsBlocked]) because
     * this file stays free of Android; keeping the branch here is what makes the
     * "blocked runs never move the cursor" rule unit-testable.
     */
    fun delivery(
        all: List<AppNotification>,
        cursor: Int,
        blocked: Boolean,
        limit: Int = MAX_PER_RUN,
    ): Delivery {
        val due = pending(all, cursor, limit)
        return when {
            due.isEmpty() -> Delivery.Idle
            blocked -> Delivery.Blocked
            else -> Delivery.Post(due)
        }
    }

    /**
     * Whether the system will silently drop an alert.
     *
     * `notify()` returns `Unit` and does not throw when the app lacks POST_NOTIFICATIONS
     * or the alerts channel is muted, so a "successful" post says nothing about delivery
     * and the state has to be checked up front. A missing channel reads as null and is
     * not blocked: it only means this process has not created it yet.
     */
    fun alertsBlocked(notificationsEnabled: Boolean, channelImportance: Int?): Boolean =
        !notificationsEnabled || channelImportance == IMPORTANCE_NONE

    /** `NotificationManager.IMPORTANCE_NONE`, spelled out to keep this file Android-free. */
    const val IMPORTANCE_NONE = 0

    /**
     * Cursor to store after posting [postedIds] out of [pendingIds].
     *
     * Walks the batch in ascending order and stops at the first id that did not post, so
     * a failed notify is retried next run instead of being jumped over. The trade-off is
     * that an alert after the gap may be posted twice; a silent loss is worse.
     */
    fun advancedCursor(pendingIds: List<Int>, postedIds: Set<Int>, previous: Int): Int {
        var cursor = previous
        for (id in pendingIds.sorted()) {
            if (id !in postedIds) break
            cursor = maxOf(cursor, id)
        }
        return cursor
    }
}
