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
